package com.ncm.app.domain.weekly

import com.ncm.app.data.model.Playlist
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

const val WEEKLY_PLAYLIST_ID = -1L

sealed class WeeklyRecUiState {
    object Loading : WeeklyRecUiState()
    data class Success(
        val songs: List<CachedSong>,
        val seedCount: Int,
        val displayWeekLabel: String
    ) : WeeklyRecUiState()
    data class InsufficientData(val validPlayCount: Int, val distinctSongCount: Int) : WeeklyRecUiState()
    data class Error(val message: String) : WeeklyRecUiState()
}

object WeeklyRecUiMapper {
    fun toUiState(result: WeeklyRecResult, zoneId: ZoneId = ZoneId.systemDefault()): WeeklyRecUiState = when (result) {
        is WeeklyRecResult.Success -> WeeklyRecUiState.Success(
            songs = result.songs,
            seedCount = result.seedCount,
            displayWeekLabel = displayWeekLabel(result.displayWeekStart)
        )
        is WeeklyRecResult.InsufficientData -> WeeklyRecUiState.InsufficientData(
            validPlayCount = result.validPlayCount,
            distinctSongCount = result.distinctSongCount
        )
        is WeeklyRecResult.Failure -> WeeklyRecUiState.Error(result.message ?: "获取每周推荐失败，请稍后重试")
    }

    /** 显示周起始日（周一）的 ISO 周号。 */
    fun displayWeekLabel(displayWeekStart: LocalDate): String =
        "第 ${displayWeekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} 周"

    fun successSubtitle(seedCount: Int, songCount: Int): String =
        "根据上周 $seedCount 首常听歌曲生成 · $songCount 首"
}

/** 每周推荐以标准歌单行的形式展示（id 为哨兵值，永不与真实歌单冲突）。 */
fun weeklyRow(state: WeeklyRecUiState): Playlist = when (state) {
    is WeeklyRecUiState.Success -> Playlist(
        id = WEEKLY_PLAYLIST_ID,
        name = "每周推荐",
        cover = state.songs.firstOrNull()?.cover,
        trackCount = state.songs.size
    )
    else -> Playlist(
        id = WEEKLY_PLAYLIST_ID,
        name = "每周推荐",
        trackCount = 0
    )
}
