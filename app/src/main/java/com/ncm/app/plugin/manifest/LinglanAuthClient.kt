package com.ncm.app.plugin.manifest

import com.google.gson.JsonParser
import com.ncm.app.plugin.auth.LinglanAuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class AuthValidationResult(
    val state: LinglanAuthState,
    val validUntilEpochMs: Long?,
    val message: String?
)

/** 授权校验客户端。密钥仅用于本次请求，不记录响应体、完整 URL 或密钥。 */
class LinglanAuthClient(
    private val http: suspend (url: String, secret: String) -> String,
    private val endpoint: String = DEFAULT_ENDPOINT
) {
    suspend fun validate(secret: String): AuthValidationResult {
        return try {
            val body = withContext(Dispatchers.IO) { http(requestUrl(), secret) }
            val root = JsonParser.parseString(body).asJsonObject
            when {
                root.has("currentkey") -> AuthValidationResult(
                    LinglanAuthState.ERROR,
                    null,
                    "密钥验证服务配置错误，请稍后重试"
                )

                !root.has("code") -> AuthValidationResult(
                    LinglanAuthState.ERROR,
                    null,
                    "密钥验证响应无效"
                )

                else -> {
                    val code = root.get("code").asInt
                    val message = root.get("message")?.asString?.trim().orEmpty()
                    val state = when {
                        code == 200 -> LinglanAuthState.ACTIVE
                        // The probe deliberately omits song parameters. A non-auth 400 means
                        // the credential passed the key middleware and reached the music API.
                        code == 400 && !message.contains("密钥") -> LinglanAuthState.ACTIVE
                        code == 401 -> LinglanAuthState.EXPIRED
                        code == 403 -> LinglanAuthState.REVOKED
                        else -> LinglanAuthState.ERROR
                    }
                    AuthValidationResult(
                        state = state,
                        validUntilEpochMs = root.get("expireAt")?.asLong,
                        message = message.takeIf {
                            state != LinglanAuthState.ACTIVE && it.isNotBlank()
                        }
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 网络、响应格式或服务地址异常统一映射为可重试错误。
            AuthValidationResult(
                LinglanAuthState.ERROR,
                null,
                "无法验证密钥，请检查网络后重试"
            )
        }
    }

    internal fun requestUrl(): String {
        val parsed = endpoint.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("\u6388\u6743\u670d\u52a1\u5730\u5740\u65e0\u6548")
        return parsed.toString()
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://linglan.invalid/api/auth/validate"
    }
}
