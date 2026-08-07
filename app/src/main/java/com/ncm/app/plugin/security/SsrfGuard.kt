package com.ncm.app.plugin.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

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
        if (address.isLoopbackAddress || address.isLinkLocalAddress) {
            return SsrfDecision.Deny("local address")
        }
        if (address.isSiteLocalAddress) return SsrfDecision.Deny("site-local address")
        return SsrfDecision.Allow
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
        val literal = try { InetAddress.getByName(host) } catch (_: Exception) { return false }
        if (literal.isSiteLocalAddress || literal.isLoopbackAddress) return true
        return literal is Inet4Address &&
            (literal.hostAddress.startsWith("10.") ||
                literal.hostAddress.startsWith("192.168.") ||
                (literal.hostAddress.startsWith("172.") &&
                    literal.hostAddress.substringAfter("172.").substringBefore(".").toIntOrNull()
                        ?.let { it in 16..31 } == true))
    }

    companion object {
        val DEFAULT_RESTRICTED_PORTS: Set<Int> = setOf(22, 23, 25, 53, 110, 143, 3306, 3389, 5432, 6379, 11211)
    }
}
