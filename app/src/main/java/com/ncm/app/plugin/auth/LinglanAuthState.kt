package com.ncm.app.plugin.auth

enum class LinglanAuthState {
    DISCONNECTED, VALIDATING, ACTIVE, STALE_OFFLINE, EXPIRED, REVOKED, ERROR
}

data class LinglanAuthInfo(
    val validUntilEpochMs: Long?,
    val lastVerifiedAtEpochMs: Long?,
    val capability: Set<String>
)

/** 宿主策略常量：距上次验证超过该时长需重新验证；不可由脚本修改（spec §8.1）。 */
const val REVALIDATION_INTERVAL_MS = 86_400_000L // 24h

/** 网络失败不能被当成密钥无效（GC #8）：仅服务端明确 401/403/撤销才进入对应状态。 */
fun nextStateForServerResponse(
    current: LinglanAuthState,
    httpCode: Int,
    bodyCode: Int?
): LinglanAuthState {
    val effective = bodyCode ?: httpCode
    return when {
        effective == 401 -> LinglanAuthState.EXPIRED
        effective == 403 -> LinglanAuthState.REVOKED
        httpCode == 0 -> LinglanAuthState.ERROR          // 网络失败
        effective == 200 -> LinglanAuthState.ACTIVE
        httpCode == 429 -> LinglanAuthState.ERROR        // 限流，非密钥无效
        else -> current
    }
}

fun shouldRevalidate(info: LinglanAuthInfo, nowMs: Long): Boolean {
    val last = info.lastVerifiedAtEpochMs ?: return true
    return nowMs - last > REVALIDATION_INTERVAL_MS
}
