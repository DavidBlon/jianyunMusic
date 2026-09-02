package com.ncm.app.plugin.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

sealed interface SsrfDecision {
    data object Allow : SsrfDecision
    data class Deny(val reason: String) : SsrfDecision

    val isAllow: Boolean get() = this is Allow
    val isDeny: Boolean get() = this is Deny
}

/**
 * SSRF 防护：协议白名单 + 受限端口 + DNS 解析后校验 IPv4/IPv6 目标。
 * 每次重定向后必须重新调用 [validateResolved]，防 DNS 重绑定/重定向进私网（GC #7）。
 */
class SsrfGuard(
    private val allowHttpsOnly: Boolean = true,
    private val restrictedPorts: Set<Int> = DEFAULT_RESTRICTED_PORTS
) {
    fun validate(url: String): SsrfDecision {
        val uri = try { URI(url) } catch (_: Exception) { return SsrfDecision.Deny("invalid url") }
        val scheme = uri.scheme?.lowercase() ?: return SsrfDecision.Deny("missing scheme")
        if (scheme == "http" && allowHttpsOnly) return SsrfDecision.Deny("http not allowed")
        if (scheme != "http" && scheme != "https") return SsrfDecision.Deny("non-http protocol")
        val host = uri.host ?: return SsrfDecision.Deny("missing host")
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        if (port in restrictedPorts) return SsrfDecision.Deny("restricted port $port")
        return if (isLoopbackOrLinkLocal(host) || isPrivateLiteral(host)) {
            SsrfDecision.Deny("local/private address")
        } else {
            SsrfDecision.Allow
        }
    }

    /** 解析后校验：每次重定向后调用。 */
    fun validateResolved(address: InetAddress, port: Int): SsrfDecision {
        if (port in restrictedPorts) return SsrfDecision.Deny("restricted port $port")
        return if (isBlockedResolvedAddress(address)) {
            SsrfDecision.Deny("local/private address")
        } else {
            SsrfDecision.Allow
        }
    }

    private fun isLoopbackOrLinkLocal(host: String): Boolean {
        if (host == "localhost") return true
        val lower = host.lowercase()
        if (lower.endsWith(".localhost")) return true
        if (lower.startsWith("fe80:")) return true
        if (lower == "::1") return true
        return false
    }

    private fun isPrivateLiteral(host: String): Boolean {
        if (!(host.contains(':') || host.all { it.isDigit() || it == '.' })) return false
        val literal = try { InetAddress.getByName(host) } catch (_: Exception) { return false }
        if (literal.isSiteLocalAddress || literal.isLoopbackAddress) return true
        if (literal !is Inet4Address) return false
        val hostAddress = literal.hostAddress ?: return false
        return hostAddress.startsWith("10.") ||
            hostAddress.startsWith("192.168.") ||
            (hostAddress.startsWith("172.") &&
                hostAddress.substringAfter("172.").substringBefore(".").toIntOrNull()
                    ?.let { it in 16..31 } == true)
    }

    private fun isBlockedResolvedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isMulticastAddress) return true
        val ipv4 = effectiveIpv4(address) ?: return address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress
        val bytes = ipv4.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes.getOrNull(1)?.toInt()?.and(0xff) ?: 0
        val third = bytes.getOrNull(2)?.toInt()?.and(0xff) ?: 0
        return first == 0 ||
            first == 10 ||
            first == 127 ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 198 && (second == 18 || second == 19)) ||
            (first == 192 && second == 0 && (third == 0 || third == 2)) ||
            (first == 198 && second == 51 && third == 100) ||
            (first == 203 && second == 0 && third == 113) ||
            (first == 100 && second in 64..127)
    }

    private fun effectiveIpv4(address: InetAddress): Inet4Address? {
        return when (address) {
            is Inet4Address -> address
            is Inet6Address -> {
                val raw = address.address
                if (raw.size != 16 || !IPV4_MAPPED_PREFIX.withIndex().all { (index, byte) -> raw[index] == byte }) {
                    null
                } else {
                    runCatching {
                        InetAddress.getByAddress(raw.copyOfRange(12, 16)) as? Inet4Address
                    }.getOrNull()
                }
            }
            else -> null
        }
    }

    companion object {
        val DEFAULT_RESTRICTED_PORTS: Set<Int> = setOf(22, 23, 25, 53, 110, 143, 3306, 3389, 5432, 6379, 11211)

        private val IPV4_MAPPED_PREFIX = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte()
        )
    }
}

/** OkHttp DNS 钩子：解析出的任一地址命中私网/回环即拒绝整次解析，防 DNS 重绑定。 */
class SsrfBlockingDns(
    private val ssrfGuard: SsrfGuard
) : okhttp3.Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = okhttp3.Dns.SYSTEM.lookup(hostname)
        if (addresses.any { ssrfGuard.validateResolved(it, 0).isDeny }) {
            throw UnknownHostException("blocked local/private address: $hostname")
        }
        return addresses
    }
}
