package com.ncm.app.domain.weekly

import com.ncm.app.data.model.ArtistBrief
import com.ncm.app.data.model.Song
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyRecommendationUiPolicyTest {

    private val currentWeek = GenerationKey(1L, LocalDate.of(2026, 8, 3))
    private val nextWeek = GenerationKey(1L, LocalDate.of(2026, 8, 10))

    @Test
    fun onlyReusesASettledResultForTheSameUserAndWeek() {
        val success = WeeklyRecUiState.Success(
            songs = listOf(CachedSong(1L, "A", listOf("Artist"), null))
        )

        assertTrue(canReuseWeeklyRecommendation(success, currentWeek, currentWeek))
        assertFalse(canReuseWeeklyRecommendation(success, currentWeek, nextWeek))
        assertFalse(
            canReuseWeeklyRecommendation(
                success,
                currentWeek,
                GenerationKey(2L, currentWeek.displayWeekStart)
            )
        )
        assertTrue(
            canReuseWeeklyRecommendation(
                WeeklyRecUiState.InsufficientData(validPlayCount = 1, distinctSongCount = 1),
                currentWeek,
                currentWeek
            )
        )
        assertFalse(canReuseWeeklyRecommendation(WeeklyRecUiState.Error("failed"), currentWeek, currentWeek))
    }

    @Test
    fun restoresTheAlgorithmRankingAfterSongDetailsReturnOutOfOrder() {
        val songs = listOf(song(30L), song(10L), song(20L))

        assertEquals(
            listOf(10L, 20L, 30L),
            restoreWeeklyRecommendationOrder(listOf(10L, 20L, 30L), songs).map { it.id }
        )
    }

    private fun song(id: Long): Song = Song(
        id = id,
        name = "Song$id",
        artists = listOf(ArtistBrief(1L, "Artist"))
    )
}
