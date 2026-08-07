package com.ncm.app.plugin.provider

import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ResolvedMedia

/** 统一在线来源接口。所有实现都必须以 [pluginId] 标识自己，禁止在 Repository 中出现平台名判断（GC #6）。 */
interface MusicProvider {
    val pluginId: String

    suspend fun search(query: String, page: Int, type: String): SearchOutcome
    suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia
    suspend fun lyric(track: OnlineTrack): LyricOutcome
}

data class SearchOutcome(val items: List<OnlineTrack>, val isEnd: Boolean)

data class LyricOutcome(
    val rawLrc: String?,
    val translation: String?,
    val romaLrc: String?,
    val wordLrc: String?
)

/** 宿主错误：code 为稳定错误标识，message 可展示，retryable 供熔断与 UI 重试。 */
class PluginException(
    val code: String,
    override val message: String,
    val retryable: Boolean
) : Exception(message)
