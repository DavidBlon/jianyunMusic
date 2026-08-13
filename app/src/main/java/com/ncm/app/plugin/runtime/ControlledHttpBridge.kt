package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.security.SsrfDecision
import com.ncm.app.plugin.security.SsrfGuard

data class HttpRequestSpec(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
    val timeoutMs: Long = 10_000L
)

data class HttpResult(
    val status: Int,
    val headers: Map<String, String>,
    val data: ByteArray
)

/** 低层网络执行器：生产用 OkHttp（NeteaseApp 组装），单测用假 Map。 */
typealias HttpExecutor = suspend (HttpRequestSpec) -> HttpResult

/**
 * 插件受控 HTTP 桥：前置 SSRF 校验 + 宿主逐跳重定向（每跳重新校验目标，GC #7）
 * + 响应体上限。生产 OkHttp 执行器在 NeteaseApp 组装（支持取消与超时）。
 */
class ControlledHttpBridge(
    private val ssrfGuard: SsrfGuard,
    private val executor: HttpExecutor,
    private val maxResponseBytes: Int = 5 * 1024 * 1024,
    private val maxRedirects: Int = 5
) {
    suspend fun execute(spec: HttpRequestSpec): HttpResult {
        val decision = ssrfGuard.validate(spec.url)
        if (decision is SsrfDecision.Deny) throw IllegalStateException("blocked: ${decision.reason}")
        return executeWithRedirects(spec, remaining = maxRedirects)
    }

    private suspend fun executeWithRedirects(spec: HttpRequestSpec, remaining: Int): HttpResult {
        // OkHttp transparently advertises and decodes gzip only when callers do not set
        // Accept-Encoding themselves. Provider scripts frequently set it, which otherwise
        // exposes compressed bytes to the JS JSON parser.
        val transportSpec = spec.copy(
            headers = spec.headers.filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) }
        )
        val result = executor(transportSpec)
        if (result.data.size > maxResponseBytes) throw IllegalStateException("response body too large")
        if (!isRedirect(result.status) || remaining <= 0) return result
        val location = result.headers["location"] ?: return result
        val nextUrl = if (location.startsWith("http")) location else resolveRelative(spec.url, location)
        val redirectDecision = ssrfGuard.validate(nextUrl)
        if (redirectDecision is SsrfDecision.Deny) {
            throw IllegalStateException("blocked redirect: ${redirectDecision.reason}")
        }
        return executeWithRedirects(transportSpec.copy(url = nextUrl, body = null), remaining - 1)
    }

    private fun isRedirect(status: Int): Boolean = status in setOf(301, 302, 303, 307, 308)

    private fun resolveRelative(base: String, location: String): String {
        val baseUrl = java.net.URI(base)
        val resolved = baseUrl.resolve(location)
        return resolved.toString()
    }
}
