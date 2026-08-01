package com.ncm.app.domain.weekly

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyRecUiMapperTest {

    @Test
    fun successMapsToUiState() {
        val result = WeeklyRecResult.Success(
            songs = listOf(CachedSong(songId = 1, name = "A", artists = listOf("X"), cover = null)),
            seedCount = 8,
            displayWeekStart = LocalDate.of(2026, 7, 27)
        )
        val ui = WeeklyRecUiMapper.toUiState(result)
        assertTrue(ui is WeeklyRecUiState.Success)
        ui as WeeklyRecUiState.Success
        assertEquals(1, ui.songs.size)
    }

    @Test
    fun insufficientDataMapsToUiState() {
        val ui = WeeklyRecUiMapper.toUiState(
            WeeklyRecResult.InsufficientData(validPlayCount = 3, distinctSongCount = 1)
        )
        assertTrue(ui is WeeklyRecUiState.InsufficientData)
        ui as WeeklyRecUiState.InsufficientData
        assertEquals(3, ui.validPlayCount)
        assertEquals(1, ui.distinctSongCount)
    }

    @Test
    fun failureMapsToErrorWithFallbackMessage() {
        val ui = WeeklyRecUiMapper.toUiState(WeeklyRecResult.Failure(null))
        assertTrue(ui is WeeklyRecUiState.Error)
        assertEquals("获取每周推荐失败，请稍后重试", (ui as WeeklyRecUiState.Error).message)

        val withMessage = WeeklyRecUiMapper.toUiState(WeeklyRecResult.Failure("自定义"))
        assertEquals("自定义", (withMessage as WeeklyRecUiState.Error).message)
    }

    @Test
    fun weeklyRowSuccessUsesFirstSongCoverAndCount() {
        val state = WeeklyRecUiState.Success(
            songs = listOf(
                CachedSong(songId = 1, name = "A", artists = listOf("X"), cover = "http://cover/1"),
                CachedSong(songId = 2, name = "B", artists = listOf("Y"), cover = null)
            )
        )
        val row = weeklyRow(state)
        assertEquals(WEEKLY_PLAYLIST_ID, row.id)
        assertEquals("每周推荐", row.name)
        assertEquals("http://cover/1", row.cover)
        assertEquals(2, row.trackCount)
    }

    @Test
    fun weeklyRowNonSuccessShowsZeroCountAndNoCover() {
        val rows = listOf(
            weeklyRow(WeeklyRecUiState.Loading),
            weeklyRow(WeeklyRecUiState.InsufficientData(validPlayCount = 1, distinctSongCount = 1)),
            weeklyRow(WeeklyRecUiState.Error("boom"))
        )
        for (row in rows) {
            assertEquals(WEEKLY_PLAYLIST_ID, row.id)
            assertEquals("每周推荐", row.name)
            assertEquals(0, row.trackCount)
        }
    }
}
