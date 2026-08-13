package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.credential.isValidMusicSourceKey
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Adds the user credential only at the host-owned playback endpoint. The
 * JavaScript runtime receives this executor, never the credential itself.
 */
class AuthorizedPluginHttpExecutor(
    authorizedApiBaseUrl: String,
    private val credentialProvider: () -> String?,
    private val delegate: HttpExecutor
) {
    private val authorizedBaseUrl = authorizedApiBaseUrl.toHttpUrlOrNull()
    private val authorizedPlaybackPath = authorizedBaseUrl
        ?.encodedPath
        ?.trimEnd('/')
        ?.plus("/url")

    suspend fun execute(spec: HttpRequestSpec): HttpResult {
        val secret = runCatching { credentialProvider()?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return delegate(spec)
        if (!isAuthorizedPlaybackRequest(spec.url)) return delegate(spec)
        if (!isValidMusicSourceKey(secret)) {
            throw IllegalStateException("音乐来源密钥格式错误，请前往设置重新输入")
        }

        val hostHeaders = spec.headers
            .filterKeys { !it.equals(API_KEY_HEADER, ignoreCase = true) }
            .toMutableMap()
            .apply { put(API_KEY_HEADER, secret) }
        return delegate(spec.copy(headers = hostHeaders))
    }

    private fun isAuthorizedPlaybackRequest(url: String): Boolean {
        val base = authorizedBaseUrl ?: return false
        val target = url.toHttpUrlOrNull() ?: return false
        return target.scheme == base.scheme &&
            target.host.equals(base.host, ignoreCase = true) &&
            target.port == base.port &&
            target.encodedPath == authorizedPlaybackPath
    }

    private companion object {
        const val API_KEY_HEADER = "X-API-Key"
    }
}
