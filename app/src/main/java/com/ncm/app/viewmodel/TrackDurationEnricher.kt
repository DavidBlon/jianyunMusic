package com.ncm.app.viewmodel

import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.provider.MusicProvider
import kotlinx.coroutines.CancellationException

/** Fetches provider details only for rows whose list response omitted duration or artwork. */
internal suspend fun enrichMissingTrackDurations(
    tracks: List<OnlineTrack>,
    providerFor: (String) -> MusicProvider?
): List<OnlineTrack> = tracks.map { track ->
    val hasDuration = track.durationMs?.takeIf { it > 0L } != null
    val hasArtwork = !track.artworkUrl.isNullOrBlank() || !track.album?.artworkUrl.isNullOrBlank()
    if (hasDuration && hasArtwork) return@map track
    val provider = providerFor(track.key.pluginId)
        ?.takeIf(MusicProvider::supportsTrackInfo)
        ?: return@map track
    val details = try {
        provider.trackInfo(track)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    details ?: return@map track
    val durationMs = details.durationMs?.takeIf { it > 0L } ?: track.durationMs
    val artworkUrl = details.artworkUrl?.takeIf(String::isNotBlank)
        ?: details.album?.artworkUrl?.takeIf(String::isNotBlank)
        ?: track.artworkUrl
    val album = track.album?.let { existing ->
        if (existing.artworkUrl.isNullOrBlank() && !artworkUrl.isNullOrBlank()) {
            existing.copy(artworkUrl = artworkUrl)
        } else {
            existing
        }
    } ?: details.album
    track.copy(durationMs = durationMs, artworkUrl = artworkUrl, album = album)
}
