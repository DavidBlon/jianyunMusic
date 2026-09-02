package com.ncm.app.plugin

import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.provider.LyricOutcome
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.runtime.PluginRuntime
import com.ncm.app.plugin.security.SsrfDecision
import com.ncm.app.plugin.security.SsrfGuard
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

internal fun httpsPlaybackVariant(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!uri.scheme.equals("http", ignoreCase = true) || uri.host.isNullOrBlank()) return null
    if (uri.port != -1 && uri.port != 80) return null
    return URI("https", uri.userInfo, uri.host, -1, uri.path, uri.query, uri.fragment).toString()
}

data class ResolvedPlayback(
    val track: OnlineTrack,
    val media: ResolvedMedia,
    val usedFallback: Boolean
)

/** 播放解析服务：只消费已解析媒体描述，不感知平台细节（spec §4）。 */
class PlaybackResolver(
    private val runtime: PluginRuntime,
    private val ssrfGuard: SsrfGuard,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val restoreProvider: (String) -> MusicProvider? = { null },
    private val installProvider: suspend (String) -> MusicProvider? = { null },
    private val fallbackProviders: () -> List<MusicProvider> = { runtime.availableProviders() }
) {
    suspend fun resolve(track: OnlineTrack, quality: String?): Result<ResolvedMedia> =
        resolveTrack(track, quality).map { it.media }

    suspend fun resolveTrack(track: OnlineTrack, quality: String?): Result<ResolvedPlayback> {
        val provider = providerFor(track.key.pluginId)
        val original = if (provider == null) {
            Result.failure(IllegalStateException("插件未装载：${track.key.pluginId}"))
        } else {
            resolveMedia(provider, track, quality)
        }
        original.getOrNull()?.let { media ->
            return Result.success(ResolvedPlayback(track, media, usedFallback = false))
        }

        val originalError = original.exceptionOrNull()
            ?: IllegalStateException("当前来源无法播放")
        val alternatives = fallbackProviders()
            .distinctBy { it.pluginId }
            .filterNot { it.pluginId == track.key.pluginId }
        val replacement = supervisorScope {
            alternatives
                .map { provider ->
                    async { resolveAlternative(provider, track, quality) }
                }
                .awaitAll()
                .filterNotNull()
                .firstOrNull()
        }
        replacement?.let { return Result.success(it) }
        return Result.failure(originalError)
    }

    private suspend fun resolveAlternative(
        provider: MusicProvider,
        requested: OnlineTrack,
        quality: String?
    ): ResolvedPlayback? {
        val candidates = try {
            provider.search(requested.title, page = 1, type = "music").items
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        candidates
            .asSequence()
            .filter { it.key.pluginId == provider.pluginId }
            .filter { strictPlaybackMatch(requested, it) }
            .sortedBy { durationDifference(requested, it) }
            .forEach { candidate ->
                resolveMedia(provider, candidate, quality).getOrNull()?.let { media ->
                    return ResolvedPlayback(candidate, media, usedFallback = true)
                }
            }
        return null
    }

    private suspend fun resolveMedia(
        provider: MusicProvider,
        track: OnlineTrack,
        quality: String?
    ): Result<ResolvedMedia> {
        // 音质降级链（参照 MusicFree qualityOrder）：所选音质失败时依次尝试更低音质，
        // 避免单曲在服务端没有该音质权限/资源时整首不可播。
        val qualities = qualityFallbackOrder(quality)
        var lastFailure: Throwable? = null
        for (candidate in qualities) {
            val attempt = try {
                val media = provider.resolveMedia(track, candidate)
                if (media.expiresAtEpochMs != null && now() > media.expiresAtEpochMs) {
                    Result.failure(IllegalStateException("播放地址已过期"))
                } else {
                    validateMedia(media)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            attempt.getOrNull()?.let { return Result.success(it) }
            lastFailure = attempt.exceptionOrNull()
        }
        return Result.failure(
            lastFailure ?: IllegalStateException("当前音源无法解析播放地址")
        )
    }

    /** 从所选音质向下降级，直到最低可用档；未知标签保持原值并兜底最低档。 */
    private fun qualityFallbackOrder(quality: String?): List<String?> = when (quality) {
        "hires" -> listOf("hires", "flac", "320k", "128k")
        "flac24bit" -> listOf("flac24bit", "flac", "320k", "128k")
        "flac" -> listOf("flac", "320k", "128k")
        "320k" -> listOf("320k", "128k")
        "standard", "high", "128k" -> listOf(quality, "128k").distinct()
        else -> listOf(quality, "128k").distinct()
    }

    private fun validateMedia(media: ResolvedMedia): Result<ResolvedMedia> {
        val safeMedia = when (ssrfGuard.validate(media.url)) {
            SsrfDecision.Allow -> media
            is SsrfDecision.Deny -> {
                val httpsUrl = httpsPlaybackVariant(media.url)
                if (httpsUrl == null || ssrfGuard.validate(httpsUrl) is SsrfDecision.Deny) {
                    return Result.failure(IllegalStateException("播放地址被安全策略拒绝"))
                }
                media.copy(url = httpsUrl)
            }
        }
        return Result.success(safeMedia)
    }

    suspend fun lyric(track: OnlineTrack): Result<LyricOutcome> {
        val provider = providerFor(track.key.pluginId)
            ?: return Result.failure(IllegalStateException("插件未装载：${track.key.pluginId}"))
        return Result.success(provider.lyric(track))
    }

    fun availableProvider(pluginId: String): MusicProvider? = runtime.providerFor(pluginId)

    private suspend fun providerFor(pluginId: String): MusicProvider? =
        runtime.providerFor(pluginId)
            ?: restoreProvider(pluginId)
            ?: installProvider(pluginId)

    private fun strictPlaybackMatch(requested: OnlineTrack, candidate: OnlineTrack): Boolean {
        if (normalized(requested.title) != normalized(candidate.title)) return false
        val requestedArtists = requested.artists.map { normalized(it.name) }.filter { it.isNotBlank() }.toSet()
        val candidateArtists = candidate.artists.map { normalized(it.name) }.filter { it.isNotBlank() }.toSet()
        if (requestedArtists.isEmpty() || candidateArtists.isEmpty()) return false
        if (requestedArtists.intersect(candidateArtists).isEmpty()) return false
        val requestedDuration = requested.durationMs?.takeIf { it > 0L }
        val candidateDuration = candidate.durationMs?.takeIf { it > 0L }
        return requestedDuration == null || candidateDuration == null ||
            abs(requestedDuration - candidateDuration) <= MAX_DURATION_DIFFERENCE_MS
    }

    private fun durationDifference(first: OnlineTrack, second: OnlineTrack): Long {
        val firstDuration = first.durationMs?.takeIf { it > 0L } ?: return Long.MAX_VALUE
        val secondDuration = second.durationMs?.takeIf { it > 0L } ?: return Long.MAX_VALUE
        return abs(firstDuration - secondDuration)
    }

    private fun normalized(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private companion object {
        const val MAX_DURATION_DIFFERENCE_MS = 15_000L
    }
}
