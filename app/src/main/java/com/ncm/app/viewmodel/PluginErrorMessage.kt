package com.ncm.app.viewmodel

import com.ncm.app.plugin.provider.PluginException

/** Converts provider/runtime failures into short UI copy without exposing JavaScript stacks. */
internal fun pluginSearchErrorMessage(error: Throwable?): String? {
    error ?: return null
    val raw = error.message.orEmpty().trim()
    val lowered = raw.lowercase()
    return when {
        "source service rejected request (code 2001)" in lowered ->
            "当前在线来源暂时限制搜索，请稍后再试或更换来源"

        error is PluginException && error.code == "TIMEOUT" ||
            "timeout" in lowered || "timed out" in lowered ->
            "在线来源响应超时，请稍后重试"

        "not a function" in lowered ||
            "module not found" in lowered ||
            "not supported" in lowered ->
            "当前在线来源脚本与应用版本不兼容，请到设置中刷新来源"

        "blocked:" in lowered || "blocked redirect" in lowered ->
            "在线来源请求被安全策略拦截，请刷新或更换来源"

        "unable to resolve host" in lowered ||
            "failed to connect" in lowered ||
            "network" in lowered ||
            "socket" in lowered ->
            "在线来源暂时无法连接，请检查网络后重试"

        raw.isBlank() -> "在线来源搜索失败，请稍后重试"
        else -> raw.lineSequence().first().take(MAX_VISIBLE_ERROR_CHARS)
    }
}

private const val MAX_VISIBLE_ERROR_CHARS = 88
