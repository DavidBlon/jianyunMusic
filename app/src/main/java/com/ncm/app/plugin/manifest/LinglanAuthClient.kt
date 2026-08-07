package com.ncm.app.plugin.manifest

import com.google.gson.JsonParser
import com.ncm.app.plugin.auth.LinglanAuthState
import com.ncm.app.plugin.auth.nextStateForServerResponse

data class AuthValidationResult(
    val state: LinglanAuthState,
    val validUntilEpochMs: Long?,
    val message: String?
)

/**
 * 聆澜密钥校验客户端。HTTP 注入以便单测；密钥经请求头/短期令牌传递，不进查询参数（GC #4/#8）。
 * 生产端点与错误码映射在阶段 3/4 联调确认（spec §17）。
 */
class LinglanAuthClient(
    private val http: suspend (url: String, secret: String) -> String,
    private val endpoint: String = DEFAULT_ENDPOINT
) {
    suspend fun validate(secret: String): AuthValidationResult = try {
        val body = http(endpoint, secret)
        val root = JsonParser.parseString(body).asJsonObject
        val code = root.get("code")?.asInt ?: 200
        val state = nextStateForServerResponse(LinglanAuthState.VALIDATING, httpCode = 200, bodyCode = code)
        AuthValidationResult(
            state = state,
            validUntilEpochMs = root.get("expireAt")?.asLong,
            message = root.get("message")?.asString
        )
    } catch (_: Exception) {
        // 网络失败/超时/畸形响应都不能被当成密钥无效（GC #8）
        AuthValidationResult(LinglanAuthState.ERROR, null, "暂时无法连接验证服务")
    }

    companion object {
        /** 占位端点；联调确认后由调用方注入生产地址（不把密钥写入查询参数）。 */
        const val DEFAULT_ENDPOINT = "https://linglan.invalid/api/auth/validate"
    }
}
