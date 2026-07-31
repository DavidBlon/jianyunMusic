package com.ncm.app.domain.weekly

import com.ncm.app.data.model.ArtistBrief
import com.ncm.app.data.model.Song
import com.ncm.app.data.weekly.WeeklySongStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyRecommendationAlgorithmTest {

    private val start = 1_700_000_000_000L
    private val end = 1_750_000_000_000L

    private fun song(id: Long, artistId: Long, artistName: String = "Artist$artistId"): Song =
        Song(id = id, name = "Song$id", artists = listOf(ArtistBrief(artistId, artistName)))

    private fun songNoArtist(id: Long): Song = Song(id = id, name = "Song$id", artists = null)

    private fun stat(songId: Long, playCount: Int, lastPlayedAt: Long = end): WeeklySongStat =
        WeeklySongStat(songId, playCount, lastPlayedAt)

    private fun edge(
        seed: Long,
        candidate: Long,
        rank: Int,
        artistId: Long = 20,
        seedScore: Double = 1.0
    ): CandidateEdge = CandidateEdge(seed, candidate, seedScore, rank, artistId)

    // ---- preliminaryScore ----

    @Test
    fun preliminaryScore_playCountDominates() {
        val high = WeeklyRecommendationAlgorithm.preliminaryScore(10, end, start, end)
        val low = WeeklyRecommendationAlgorithm.preliminaryScore(1, end, start, end)
        assertTrue(high > low)
    }

    @Test
    fun preliminaryScore_recencyBoostsLaterListens() {
        val recent = WeeklyRecommendationAlgorithm.preliminaryScore(1, end, start, end)
        val early = WeeklyRecommendationAlgorithm.preliminaryScore(1, start, start, end)
        assertTrue(recent > early)
        assertEquals(1.0, recent, 1e-9)
    }

    @Test
    fun preliminaryScore_usesDoublesWithoutIntegerTruncation() {
        val one = WeeklyRecommendationAlgorithm.preliminaryScore(1, start, start, end)
        assertTrue(one > 0.6 && one < 0.8)     // 0.7*log2(2) = 0.7
        val two = WeeklyRecommendationAlgorithm.preliminaryScore(2, start, start, end)
        assertTrue(two > 1.0 && two < 1.2)     // 0.7*log2(3) ≈ 1.1097
    }

    @Test
    fun preliminaryScore_clampsRecencyToUnitRange() {
        val before = WeeklyRecommendationAlgorithm.preliminaryScore(0, start - 1_000, start, end)
        val after = WeeklyRecommendationAlgorithm.preliminaryScore(0, end + 1_000, start, end)
        assertEquals(0.0, before, 1e-9)
        assertEquals(0.3, after, 1e-9)
    }

    // ---- selectSeeds ----

    @Test
    fun selectSeeds_picksTopSongsByScoreUpToLimit() {
        val weekSongs = (1..10).map { stat(it.toLong(), playCount = 11 - it) }
        val hydrated = (1L..10L).associateWith { song(it, artistId = it) }
        val seeds = WeeklyRecommendationAlgorithm.selectSeeds(weekSongs, hydrated, start, end)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), seeds.map { it.songId })
    }

    @Test
    fun selectSeeds_limitsTwoSeedsPerPrimaryArtist() {
        val weekSongs = listOf(stat(1, 10), stat(2, 9), stat(3, 8), stat(4, 7))
        val hydrated = mapOf(
            1L to song(1, 100), 2L to song(2, 100), 3L to song(3, 100), 4L to song(4, 200)
        )
        val seeds = WeeklyRecommendationAlgorithm.selectSeeds(weekSongs, hydrated, start, end)
        assertEquals(listOf(1L, 2L, 4L), seeds.map { it.songId })   // 3 skipped: artist 100 at limit
    }

    @Test
    fun selectSeeds_noArtistSongsUseOwnBucketAndDoNotCollide() {
        val weekSongs = listOf(stat(1, 10), stat(2, 9), stat(3, 8))
        val hydrated = mapOf(1L to song(1, 100))   // only song 1 has artist info
        val seeds = WeeklyRecommendationAlgorithm.selectSeeds(weekSongs, hydrated, start, end)
        assertEquals(listOf(1L, 2L, 3L), seeds.map { it.songId })
    }

    // ---- rankCandidates ----

    @Test
    fun rankCandidates_filtersInvalidEdges() {
        val edges = listOf(
            edge(1, 100, rank = 0),                  // simRank out of 1..10
            edge(1, 101, rank = 11),
            edge(1, 0, rank = 1),                    // invalid candidate id
            edge(1, 1, rank = 1),                    // candidate == seed
            edge(1, 102, rank = 1, artistId = 0),    // no artist
            edge(1, 103, rank = 1, artistId = 20)    // valid
        )
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        assertEquals(listOf(103L), result)
    }

    @Test
    fun rankCandidates_dedupsDuplicateEdgesBeforeScoring() {
        val edges = listOf(
            edge(1, 100, rank = 2),
            edge(1, 100, rank = 2),                  // duplicate pair must collapse to one
            edge(1, 200, rank = 1)
        )
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        // With dedup: 100 co1 simScore 9; 200 co1 simScore 10 → [200, 100]
        assertEquals(listOf(200L, 100L), result)
    }

    @Test
    fun rankCandidates_excludesListenedSongs() {
        val edges = listOf(
            edge(1, 100, rank = 1),
            edge(1, 200, rank = 1)
        )
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, setOf(100L))
        assertEquals(listOf(200L), result)
    }

    @Test
    fun rankCandidates_weightsSeedsByRelativeScore() {
        val edges = listOf(
            edge(1, 100, rank = 1, seedScore = 1.0),   // weight 1.0 → simScore 10
            edge(2, 200, rank = 1, seedScore = 0.5)    // weight 0.5 → simScore 5
        )
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        assertEquals(listOf(100L, 200L), result)
    }

    @Test
    fun rankCandidates_zeroSeedScoresFallsBackToCoOccurrence() {
        val edges = listOf(
            edge(1, 100, rank = 1, seedScore = 0.0),
            edge(1, 200, rank = 1, seedScore = 0.0),
            edge(2, 200, rank = 1, seedScore = 0.0)    // 200 co2 → coScore 3
        )
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        assertEquals(listOf(200L, 100L), result)
    }

    @Test
    fun rankCandidates_coScoreTableIsThreeTimesCoOccurrenceMinusOne() {
        // coOccurrence 2 → +3. 100 reached from 2 seeds (co2), 200 from 1 seed (co1).
        // Equal simScore per seed → 100 wins via coScore.
        val edges = listOf(
            edge(1, 100, rank = 1),
            edge(2, 100, rank = 1),
            edge(1, 200, rank = 1)
        )
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        assertEquals(listOf(100L, 200L), result)
    }

    @Test
    fun rankCandidates_tieBreaksBySongIdAscending() {
        val edges = listOf(
            edge(1, 200, rank = 1, seedScore = 1.0),
            edge(1, 100, rank = 1, seedScore = 1.0)
        )
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        assertEquals(listOf(100L, 200L), result)
    }

    @Test
    fun rankCandidates_secondPassRelaxesArtistLimitWhenShortOfTarget() {
        val edges = mutableListOf<CandidateEdge>()
        for (artist in 1L..6L) {
            for (rank in 1..7) {
                edges += edge(1, artist * 100 + rank, rank = rank, artistId = artist)
            }
        }
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        // First pass (limit 3): 6*3 = 18 < 30 → second pass (limit 5): 6*5 = 30.
        assertEquals(30, result.size)
        val perArtist = result.groupingBy { it / 100 }.eachCount()
        assertEquals(6, perArtist.size)
        assertEquals(5, perArtist.values.maxOrNull())
    }

    @Test
    fun rankCandidates_returnsAllWhenPoolSmallerThanTarget() {
        val edges = (1..5).map { edge(1, it.toLong() + 100, rank = it, artistId = 1) }
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        assertEquals(5, result.size)
    }
}
