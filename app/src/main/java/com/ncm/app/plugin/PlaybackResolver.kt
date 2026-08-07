package com.ncm.app.plugin

import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.provider.LyricOutcome
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.runtime.PluginRuntime
import com.ncm.app.plugin.security.SsrfDecision
import com.ncm.app.plugin.security.SsrfGuard

/** 播放解析服务：只消费已解析媒体描述，不感知平台细节（spec §4）。 */
class PlaybackResolver(
    private val runtime: PluginRuntime,
    private val ssrfGuard: SsrfGuard,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun resolve(track: OnlineTrack, quality: String?): Result<ResolvedMedia> {
        val provider = runtime.providerFor(track.key.pluginId)
            ?: return Result.failure(IllegalStateException("插件未装载：${track.key.pluginId}"))
        val media = provider.resolveMedia(track, quality)
        // 播放地址有时效性：过期即失败，不缓存/不落库（GC #11）
        if (media.expiresAtEpochMs != null && now() > media.expiresAtEpochMs) {
            return Result.failure(IllegalStateException("播放地址已过期"))
        }
        val decision = ssrfGuard.validate(media.url)
        if (decision is SsrfDecision.Deny) {
            return Result.failure(IllegalStateException("播放地址被安全策略拒绝"))
        }
        return Result.success(media)
    }

    suspend fun lyric(track: OnlineTrack): Result<LyricOutcome> {
        val provider = runtime.providerFor(track.key.pluginId)
            ?: return Result.failure(IllegalStateException("插件未装载：${track.key.pluginId}"))
        return Result.success(provider.lyric(track))
    }

    fun availableProvider(pluginId: String): MusicProvider? = runtime.providerFor(pluginId)
}
