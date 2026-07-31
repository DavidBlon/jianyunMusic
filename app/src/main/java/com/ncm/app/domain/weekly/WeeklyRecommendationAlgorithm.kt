package com.ncm.app.domain.weekly

import com.ncm.app.data.model.Song
import com.ncm.app.data.weekly.WeeklySongStat
import kotlin.math.ln

/** 选中的种子歌曲。score 为 preliminaryScore，primaryArtistId 用于艺人配额。 */
data class Seed(
    val songId: Long,
    val score: Double,
    val primaryArtistId: Long
)

/** 一条"种子→候选"相似边。 */
data class CandidateEdge(
    val seedSongId: Long,
    val candidateSongId: Long,
    val seedScore: Double,
    val simRank: Int,
    val primaryArtistId: Long
)

private data class ScoredCandidate(
    val candidateSongId: Long,
    val simScore: Double,
    val coOccurrence: Int,
    val sumSimRank: Int,
    val primaryArtistId: Long
)

object WeeklyRecommendationAlgorithm {

    const val SEED_LIMIT = 8
    const val SEED_ARTIST_LIMIT = 2
    const val SIM_LIMIT = 10
    const val TARGET_COUNT = 30
    const val ARTIST_LIMIT_FIRST_PASS = 3
    const val ARTIST_LIMIT_SECOND_PASS = 5
    const val SCORE_PLAY_WEIGHT = 0.7
    const val SCORE_RECENCY_WEIGHT = 0.3

    private const val CO_OCCURRENCE_WEIGHT = 3.0

    /** 0.7*log2(1+playCount) + 0.3*recency，全部 Double 参与，防止整型截断。 */
    fun preliminaryScore(playCount: Int, lastPlayedAt: Long, startMs: Long, endMs: Long): Double {
        val playTerm = SCORE_PLAY_WEIGHT * ln(1.0 + playCount) / ln(2.0)
        val durationMs = (endMs - startMs).toDouble()
        val recency = if (durationMs <= 0.0) {
            0.0
        } else {
            ((lastPlayedAt - startMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
        }
        return playTerm + SCORE_RECENCY_WEIGHT * recency
    }

    /** 贪心按分数降序选最多 8 个种子；同 primaryArtistId ≤ 2；无艺人歌曲用 songId 当桶（不互斥）。 */
    fun selectSeeds(
        weekSongs: List<WeeklySongStat>,
        hydrated: Map<Long, Song>,
        startMs: Long,
        endMs: Long
    ): List<Seed> {
        val selected = mutableListOf<Seed>()
        val artistCount = mutableMapOf<Long, Int>()
        val sorted = weekSongs
            .map { stat ->
                stat to preliminaryScore(stat.playCount, stat.lastPlayedAt, startMs, endMs)
            }
            .sortedByDescending { it.second }
        for ((stat, score) in sorted) {
            if (selected.size >= SEED_LIMIT) break
            val artistId = hydrated[stat.songId]?.primaryArtistId() ?: 0L
            val bucket = if (artistId > 0) artistId else stat.songId
            if ((artistCount[bucket] ?: 0) >= SEED_ARTIST_LIMIT) continue
            selected += Seed(stat.songId, score, artistId)
            artistCount[bucket] = (artistCount[bucket] ?: 0) + 1
        }
        return selected
    }

    /**
     * 过滤非法边 → 按 (seed, candidate) 去重保留最小 simRank → 剔除已听歌曲 →
     * 累加 simScore（种子权重归一化）与 coScore = 3*(coOccurrence-1) →
     * 稳定排序 → 两趟艺人限制（3 → 不足 30 用 5 重选，不追加）。
     */
    fun rankCandidates(edges: List<CandidateEdge>, listenedSongIds: Set<Long>): List<Long> {
        val valid = edges.filter { edge ->
            edge.candidateSongId > 0 &&
                edge.candidateSongId != edge.seedSongId &&
                edge.simRank in 1..SIM_LIMIT &&
                edge.primaryArtistId > 0
        }
        val deduped = valid
            .groupBy { it.seedSongId to it.candidateSongId }
            .mapNotNull { (_, group) -> group.minByOrNull { it.simRank } }
            .filter { it.candidateSongId !in listenedSongIds }
        if (deduped.isEmpty()) return emptyList()

        val maxSeedScore = deduped.maxOfOrNull { it.seedScore } ?: 0.0
        val accumulators = LinkedHashMap<Long, CandidateAccumulator>()
        for (edge in deduped) {
            val seedWeight = if (maxSeedScore > 0.0) edge.seedScore / maxSeedScore else 0.0
            val contribution = seedWeight * (SIM_LIMIT - edge.simRank + 1)
            val acc = accumulators.getOrPut(edge.candidateSongId) {
                CandidateAccumulator(edge.candidateSongId, edge.primaryArtistId)
            }
            acc.simScore += contribution
            acc.coOccurrence += 1
            acc.sumSimRank += edge.simRank
        }
        val scored = accumulators.values.map { acc ->
            ScoredCandidate(
                candidateSongId = acc.candidateSongId,
                simScore = acc.simScore,
                coOccurrence = acc.coOccurrence,
                sumSimRank = acc.sumSimRank,
                primaryArtistId = acc.primaryArtistId
            )
        }
        val sorted = scored.sortedWith(
            compareByDescending<ScoredCandidate> { it.coOccurrence }
                .thenByDescending { it.simScore }
                .thenBy { it.sumSimRank }
                .thenBy { it.candidateSongId }
        )
        val firstPass = selectWithArtistLimit(sorted, ARTIST_LIMIT_FIRST_PASS, TARGET_COUNT)
        val result = if (firstPass.size < TARGET_COUNT) {
            selectWithArtistLimit(sorted, ARTIST_LIMIT_SECOND_PASS, TARGET_COUNT)
        } else {
            firstPass
        }
        return result.map { it.candidateSongId }
    }

    private fun selectWithArtistLimit(
        sorted: List<ScoredCandidate>,
        artistLimit: Int,
        target: Int
    ): List<ScoredCandidate> {
        val result = mutableListOf<ScoredCandidate>()
        val artistCount = mutableMapOf<Long, Int>()
        for (candidate in sorted) {
            if (result.size >= target) break
            val bucket = if (candidate.primaryArtistId > 0) candidate.primaryArtistId else candidate.candidateSongId
            if ((artistCount[bucket] ?: 0) >= artistLimit) continue
            result += candidate
            artistCount[bucket] = (artistCount[bucket] ?: 0) + 1
        }
        return result
    }

    private data class CandidateAccumulator(
        val candidateSongId: Long,
        val primaryArtistId: Long
    ) {
        var simScore: Double = 0.0
        var coOccurrence: Int = 0
        var sumSimRank: Int = 0
    }

    private fun Song.primaryArtistId(): Long = artists?.firstOrNull()?.id ?: 0L
}
