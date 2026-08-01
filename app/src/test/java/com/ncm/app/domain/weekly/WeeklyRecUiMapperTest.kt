package com.ncm.app.domain.weekly

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyRecUiMapperTest {

    @Test
    fun successMapsToUiStateWithWeekLabel() {
        val result = WeeklyRecResult.Success(
            songs = listOf(CachedSong(1, "A", listOf("X"), null)),
            seedCount = 8,
            displayWeekStart = LocalDate.of(2026, 7, 27)
        )
        val ui = WeeklyRecUiMapper.toUiState(result, ZoneId.of("UTC"))
        assertTrue(ui is WeeklyRecUiState.Success)
        ui as WeeklyRecUiState.Success
        assertEquals("第 31 周", ui.displayWeekLabel)
        assertEquals(8, ui.seedCount)
        assertEquals(1, ui.songs.size)
    }

    @Test
    fun insufficientDataMapsToUiState() {
        val ui = WeeklyRecUiMapper.toUiState(
            WeeklyRecResult.InsufficientData(validPlayCount = 3, distinctSongCount = 1),
            ZoneId.of("UTC")
        )
        assertTrue(ui is WeeklyRecUiState.InsufficientData)
        ui as WeeklyRecUiState.InsufficientData
        assertEquals(3, ui.validPlayCount)
        assertEquals(1, ui.distinctSongCount)
    }

    @Test
    fun failureMapsToErrorWithFallbackMessage() {
        val ui = WeeklyRecUiMapper.toUiState(WeeklyRecResult.Failure(null), ZoneId.of("UTC"))
        assertTrue(ui is WeeklyRecUiState.Error)
        assertEquals("获取每周推荐失败，请稍后重试", (ui as WeeklyRecUiState.Error).message)

        val withMessage = WeeklyRecUiMapper.toUiState(WeeklyRecResult.Failure("自定义"), ZoneId.of("UTC"))
        assertEquals("自定义", (withMessage as WeeklyRecUiState.Error).message)
    }

    @Test
    fun weekLabelFormatUsesIsoWeek() {
        assertEquals("第 31 周", WeeklyRecUiMapper.displayWeekLabel(LocalDate.of(2026, 7, 27)))
        assertEquals("第 53 周", WeeklyRecUiMapper.displayWeekLabel(LocalDate.of(2026, 12, 28)))
    }

    @Test
    fun successSubtitleFormat() {
        assertEquals("根据上周 8 首常听歌曲生成 · 26 首", WeeklyRecUiMapper.successSubtitle(8, 26))
    }
}
