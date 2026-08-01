package com.ncm.app.domain.weekly

import com.ncm.app.data.model.Song
import com.ncm.app.data.weekly.WeeklyPlayLogPort
import com.ncm.app.data.weekly.WeeklyRecCachePort
import com.ncm.app.data.weekly.WeeklySongStat
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

data class GenerationKey(
    val userId: Long,
    val displayWeekStart: LocalDate
)

interface WeeklyGenerationController {
    suspend fun cancelGenerationForUser(userId: Long)
}

/** 上一自然周 [sourceWeekStart] 00:00 → 本周 [displayWeekStart] 00:00（本地时区）。 */
fun weekBounds(
    sourceWeekStart: LocalDate,
    displayWeekStart: LocalDate,
    zoneId: ZoneId
): Pair<Long, Long> {
    val start = sourceWeekStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val end = displayWeekStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
    return start to end
}

class GenerateWeeklyRecommendationUseCase(
    private val source: WeeklyRecommendationSource,
    private val weeklyPlayLog: WeeklyPlayLogPort,
    private val store: WeeklyRecCachePort,
    private val currentUserId: () -> Long,
    private val currentSessionGeneration: () -> Int,
    private val scope: CoroutineScope,
    private val zoneIdProvider: () -> ZoneId,
    private val nowMs: () -> Long
) : WeeklyGenerationController {

    private data class InFlightTask(
        val key: GenerationKey,
        val job: kotlinx.coroutines.Deferred<WeeklyRecResult>
    )

    private val generationMutex = Mutex()
    private var inFlight: InFlightTask? = null
    private val semaphore = Semaphore(SIMILAR_CONCURRENCY)

    /**
     * Single-flight：同一 (userId, displayWeekStart) 同时只跑一个任务。
     * Mutex 只保护任务查/建；不持锁挂起（cancel 而非 cancelAndJoin）。
     * 清理经 scope.launch 清理，避免 invokeOnCompletion 在持锁等待时死锁。
     */
    suspend fun execute(key: GenerationKey): WeeklyRecResult {
        val runningTask = generationMutex.withLock { inFlight }
        if (runningTask != null && runningTask.key == key) {
            return runningTask.job.await()
        }
        if (runningTask != null) {
            runningTask.job.cancel()
        }

        val task = generationMutex.withLock {
            val current = inFlight
            if (current != null && current.key == key) {
                return@withLock current.job
            }
            if (current != null) {
                // 第二把锁内发现不同 key 的在途任务（首把锁快路径未覆盖的竞争窗口）：
                // 直接 cancel（非挂起，无死锁风险），保证同一账号同一显示周同时只跑一个生成。
                current.job.cancel()
            }
            val newJob = scope.async { generate(key) }
            val newTask = InFlightTask(key, newJob)
            inFlight = newTask
            newJob.invokeOnCompletion {
                scope.launch {
                    generationMutex.withLock {
                        if (inFlight?.job === newJob) inFlight = null
                    }
                }
            }
            newJob
        }
        return task.await()
    }

    override suspend fun cancelGenerationForUser(userId: Long) {
        val task = generationMutex.withLock { inFlight }
        if (task != null && task.key.userId == userId) {
            task.job.cancelAndJoin()
        }
    }

    private suspend fun generate(key: GenerationKey): WeeklyRecResult {
        val userId = key.userId
        val displayWeekStart = key.displayWeekStart
        val generationZone = zoneIdProvider()
        val sessionGenerationSnapshot = currentSessionGeneration()
        val sourceWeekStart = displayWeekStart.minusWeeks(1)

        // 缓存命中（含数据不足缓存）→ 零请求返回。
        store.get(userId, displayWeekStart, sourceWeekStart)?.let { return it }

        val (sourceStartMs, sourceEndMs) = weekBounds(sourceWeekStart, displayWeekStart, generationZone)
        val weeklyStats = weeklyPlayLog.read(userId, sourceStartMs, sourceEndMs)

        val distinctSongCount = weeklyStats.size
        val validPlayCount = weeklyStats.sumOf { it.playCount }
        if (distinctSongCount < MIN_DISTINCT_SONGS) {
            store.putInsufficientData(
                userId = userId,
                sourceWeekStart = sourceWeekStart,
                displayWeekStart = displayWeekStart,
                validPlayCount = validPlayCount,
                distinctSongCount = distinctSongCount,
                generatedAt = nowMs()
            )
            return WeeklyRecResult.InsufficientData(validPlayCount, distinctSongCount)
        }

        val hydrated = hydrateWeeklySongs(weeklyStats, sourceStartMs, sourceEndMs)
        val seeds = WeeklyRecommendationAlgorithm.selectSeeds(weeklyStats, hydrated, sourceStartMs, sourceEndMs)
        if (seeds.isEmpty()) {
            return WeeklyRecResult.Failure("缺少可用于生成推荐的歌曲数据")
        }

        val edges = fetchCandidateEdges(seeds)
        if (edges.isEmpty()) {
            return WeeklyRecResult.Failure("获取相似歌曲失败")
        }

        val listenedSongIds = weeklyStats.map { it.songId }.toSet()
        val rankedIds = WeeklyRecommendationAlgorithm.rankCandidates(edges, listenedSongIds)
        if (rankedIds.isEmpty()) {
            return WeeklyRecResult.Failure("未生成足够相似歌曲")
        }

        val details = fetchSongDetails(rankedIds)
        val byId = details.associateBy { it.id }
        val orderedSongs = rankedIds.mapNotNull { byId[it] }
        if (orderedSongs.isEmpty()) {
            return WeeklyRecResult.Failure("歌曲信息获取失败")
        }

        // 写缓存前校验会话仍一致：防止旧任务在退出/重登后把结果写进新会话。
        if (!isGenerationStillCurrent(userId, sessionGenerationSnapshot)) {
            return WeeklyRecResult.Failure(null)
        }

        val cachedSongs = orderedSongs.map { it.toCachedSong() }
        store.putSuccess(
            userId = userId,
            sourceWeekStart = sourceWeekStart,
            displayWeekStart = displayWeekStart,
            songs = cachedSongs,
            seedCount = seeds.size,
            generatedAt = nowMs()
        )
        return WeeklyRecResult.Success(cachedSongs, seeds.size, displayWeekStart)
    }

    private suspend fun isGenerationStillCurrent(userId: Long, sessionGenerationSnapshot: Int): Boolean {
        coroutineContext.ensureActive()
        return currentUserId() == userId && currentSessionGeneration() == sessionGenerationSnapshot
    }

    /** 先算初步分，≤100 全水合，>100 只水合前 20（供选种艺人信息）。批次 ≤50。 */
    private suspend fun hydrateWeeklySongs(
        weeklyStats: List<WeeklySongStat>,
        sourceStartMs: Long,
        sourceEndMs: Long
    ): Map<Long, Song> {
        val scored = weeklyStats
            .map { stat ->
                stat to WeeklyRecommendationAlgorithm.preliminaryScore(
                    stat.playCount, stat.lastPlayedAt, sourceStartMs, sourceEndMs
                )
            }
            .sortedByDescending { it.second }
        val toHydrate = if (scored.size <= HYDRATE_ALL_LIMIT) scored else scored.take(HYDRATE_TOP_LIMIT)
        val ids = toHydrate.map { it.first.songId }
        return fetchSongsBatched(ids).associateBy { it.id }
    }

    private suspend fun fetchSongsBatched(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        return supervisorScope {
            ids.chunked(BATCH_SIZE).map { batch ->
                async {
                    try {
                        withTimeout(SIMILAR_TIMEOUT_MS) { source.getSongDetails(batch) }
                    } catch (e: TimeoutCancellationException) {
                        emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }.flatten()
    }

    /** 每个种子拉相似歌曲（并发 ≤4，单种子 8 秒超时只跳该种子）；Plan A 缺艺人 → Plan B 水合补全。 */
    private suspend fun fetchCandidateEdges(seeds: List<Seed>): List<CandidateEdge> {
        if (seeds.isEmpty()) return emptyList()
        val perSeed = supervisorScope {
            seeds.map { seed ->
                async {
                    semaphore.withPermit {
                        try {
                            val similar = withTimeout(SIMILAR_TIMEOUT_MS) { source.getSimilarSongs(seed.songId) }
                            seed to similar.take(WeeklyRecommendationAlgorithm.SIM_LIMIT)
                        } catch (e: TimeoutCancellationException) {
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll()
        }.filterNotNull()

        val missingArtist = perSeed.any { (_, similar) -> similar.any { it.artists.isEmpty() } }
        val artistById: Map<Long, Long> = if (missingArtist) hydrateCandidateArtists(perSeed) else emptyMap()

        return perSeed.flatMap { (seed, similar) ->
            similar.mapIndexedNotNull { index, song ->
                val primaryArtistId = song.artists.firstOrNull()?.id ?: artistById[song.id] ?: 0L
                CandidateEdge(
                    seedSongId = seed.songId,
                    candidateSongId = song.id,
                    seedScore = seed.score,
                    simRank = index + 1,
                    primaryArtistId = primaryArtistId
                )
            }
        }
    }

    /** Plan B：候选缺艺人时，最多水合 80 首以补全 primaryArtistId。 */
    private suspend fun hydrateCandidateArtists(perSeed: List<Pair<Seed, List<SimilarSong>>>): Map<Long, Long> {
        val ids = perSeed
            .flatMap { it.second.map { song -> song.id } }
            .distinct()
            .take(CANDIDATE_HYDRATION_LIMIT)
        if (ids.isEmpty()) return emptyMap()
        val songs = fetchSongsBatched(ids)
        return songs.associate { it.id to (it.artists?.firstOrNull()?.id ?: 0L) }
    }

    /** 最终候选按 id 逐个取详情（并发 ≤4），单曲失败跳过。 */
    private suspend fun fetchSongDetails(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        return supervisorScope {
            ids.map { id ->
                async {
                    semaphore.withPermit {
                        try {
                            withTimeout(SIMILAR_TIMEOUT_MS) { source.getSongDetails(listOf(id)) }
                                .firstOrNull()
                        } catch (e: TimeoutCancellationException) {
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll()
        }.filterNotNull()
    }

    companion object {
        const val MIN_DISTINCT_SONGS = 2
        const val HYDRATE_ALL_LIMIT = 100
        const val HYDRATE_TOP_LIMIT = 20
        const val BATCH_SIZE = 50
        const val CANDIDATE_HYDRATION_LIMIT = 80
        const val SIMILAR_TIMEOUT_MS = 8_000L
        const val SIMILAR_CONCURRENCY = 4
    }
}
