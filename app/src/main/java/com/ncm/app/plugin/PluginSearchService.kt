package com.ncm.app.plugin

import com.ncm.app.plugin.provider.PluginException
import com.ncm.app.plugin.provider.SearchOutcome
import com.ncm.app.plugin.runtime.PluginRuntime

/**
 * 把搜索路由到「当前选中的音乐来源」对应插件。
 * 单来源策略：搜索失败即失败，绝不静默回退到其他来源（GC #6）。
 */
class PluginSearchService(
    private val runtime: PluginRuntime,
    private val currentSource: () -> String?
) {
    suspend fun search(query: String, page: Int, type: String): Result<SearchOutcome> {
        val pluginId = currentSource()
            ?: return Result.failure(IllegalStateException("未选择在线音乐来源"))
        val provider = runtime.providerFor(pluginId)
            ?: return Result.failure(IllegalStateException("插件未装载：$pluginId"))
        return try {
            Result.success(provider.search(query, page, type))
        } catch (e: PluginException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(PluginException("SEARCH_FAILED", e.message ?: "搜索失败", retryable = true))
        }
    }
}
