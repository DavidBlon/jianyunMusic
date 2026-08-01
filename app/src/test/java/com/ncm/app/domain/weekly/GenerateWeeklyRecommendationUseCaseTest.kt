package com.ncm.app.domain.weekly

import com.ncm.app.data.model.ArtistBrief
import com.ncm.app.data.model.Song
import com.ncm.app.data.weekly.WeeklyPlayLogPort
import com.ncm.app.data.weekly.WeeklyRecCachePort
import com.ncm.app.data.weekly.WeeklySongStat
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CyclicBarrier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 并发压力测试轮数：修复后每轮确定恰一个成功；修复前在竞争窗口触发时可见两个成功。 */
private const val STRESS_ITERATIONS = 200

class GenerateWeeklyRecommendationUseCaseTest {

    private class FakeSource : WeeklyRecommendationSource {
        val similarResults = mutableMapOf<Long, List<SimilarSong>>()
        val similarFailures = mutableSetOf<Long>()
        val detailResults = mutableMapOf<Long, Song>()
        var similarCallCount = 0
        var gate: CompletableDeferred<Unit>? = null

        /** 当前正阻塞在网络闸门（等待 gate 放行）的调用数；并发单飞测试用来感知两代任务都已到达闸门。 */
        private var atGateCount = 0
        val bothAtGate = CompletableDeferred<Unit>()

        private suspend fun awaitGate() {
            atGateCount++
            if (atGateCount >= 2) bothAtGate.complete(Unit)
            try {
                gate?.await()
            } finally {
                atGateCount--
            }
        }

        override suspend fun getSimilarSongs(songId: Long): List<SimilarSong> {
            awaitGate()
            similarCallCount++
            if (songId in similarFailures) throw IOException("similar failed for $songId")
            return similarResults[songId].orEmpty()
        }

        override suspend fun getSongDetails(ids: List<Long>): List<Song> {
            awaitGate()
            return ids.flatMap { id -> detailResults[id]?.let { listOf(it) }.orEmpty() }
        }
    }

    private class FakeLog : WeeklyPlayLogPort {
        var stats = emptyList<WeeklySongStat>()
        var readCount = 0
        override suspend fun read(userId: Long, startMs: Long, endMs: Long): List<WeeklySongStat> {
            readCount++
            return stats
        }
        override suspend fun pruneExpired(userId: Long, now: Long) {}
        override suspend fun pruneAllUsersExpired(now: Long) {}
        override suspend fun deleteAllByUser(userId: Long): Long = 1
    }

    private class FakeStore : WeeklyRecCachePort {
        data class PutSuccess(
            val userId: Long,
            val sourceWeekStart: LocalDate,
            val displayWeekStart: LocalDate,
            val songs: List<CachedSong>,
            val seedCount: Int
        )

        var getResult: WeeklyRecResult? = null
        var lastPutSuccess: PutSuccess? = null
        val putSuccessKeys = mutableListOf<String>()
        var putInsufficient: Pair<Int, Int>? = null

        override fun get(userId: Long, displayWeekStart: LocalDate, sourceWeekStart: LocalDate): WeeklyRecResult? = getResult
        override fun putSuccess(
            userId: Long,
            sourceWeekStart: LocalDate,
            displayWeekStart: LocalDate,
            songs: List<CachedSong>,
            seedCount: Int,
            generatedAt: Long
        ) {
            lastPutSuccess = PutSuccess(userId, sourceWeekStart, displayWeekStart, songs, seedCount)
            putSuccessKeys += "$userId:$displayWeekStart"
        }
        override fun putInsufficientData(
            userId: Long,
            sourceWeekStart: LocalDate,
            displayWeekStart: LocalDate,
            validPlayCount: Int,
            distinctSongCount: Int,
            generatedAt: Long
        ) {
            putInsufficient = validPlayCount to distinctSongCount
        }
        override fun removeInvalidIfPresent(userId: Long): Boolean = false
        override suspend fun removeForUserDurable(userId: Long): Boolean = true
    }

    private val week = LocalDate.of(2026, 7, 27)
    private val previousWeek = LocalDate.of(2026, 7, 20)
    private val zone = ZoneId.of("UTC")
    private val fixedNow = 1_750_000_000_000L
    private val lastPlayed = 1_749_000_000_000L

    private lateinit var source: FakeSource
    private lateinit var log: FakeLog
    private lateinit var store: FakeStore

    @Before
    fun setUp() {
        source = FakeSource()
        log = FakeLog()
        store = FakeStore()
    }

    private fun buildUseCase(
        scope: CoroutineScope,
        userId: () -> Long = { 1L },
        sessionGen: () -> Int = { 0 }
    ) = GenerateWeeklyRecommendationUseCase(
        source = source,
        weeklyPlayLog = log,
        store = store,
        currentUserId = userId,
        currentSessionGeneration = sessionGen,
        scope = scope,
        zoneIdProvider = { zone },
        nowMs = { fixedNow }
    )

    private fun song(id: Long, artistId: Long, name: String = "Song$id"): Song =
        Song(id = id, name = name, artists = listOf(ArtistBrief(artistId, "Artist$artistId")))

    /** 两个种子（满足最小去重歌曲数）+ 两个候选，候选带艺人（Plan A）。 */
    private fun happyPathFixture() {
        log.stats = listOf(
            WeeklySongStat(songId = 1, playCount = 5, lastPlayedAt = lastPlayed),
            WeeklySongStat(songId = 2, playCount = 4, lastPlayedAt = lastPlayed)
        )
        source.detailResults[1] = song(1, 10)
        source.detailResults[2] = song(2, 11)
        source.similarResults[1] = listOf(
            SimilarSong(100, "Cand1", listOf(ArtistBrief(20, "ArtistB"))),
            SimilarSong(200, "Cand2", listOf(ArtistBrief(20, "ArtistB")))
        )
        source.similarResults[2] = emptyList()
        source.detailResults[100] = song(100, 20, name = "Cand1")
        source.detailResults[200] = song(200, 20, name = "Cand2")
    }

    // ---- happy path & sufficiency ----

    @Test
    fun success_generatesAndWritesCache() = runTest {
        happyPathFixture()
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.Success)
        val success = result as WeeklyRecResult.Success
        assertEquals(listOf(100L, 200L), success.songs.map { it.songId })
        assertEquals(2, success.seedCount)
        assertEquals(week, success.displayWeekStart)
        assertEquals(listOf("1:$week"), store.putSuccessKeys)
        assertEquals(previousWeek, store.lastPutSuccess!!.sourceWeekStart)
        assertEquals(listOf("Cand1", "Cand2"), store.lastPutSuccess!!.songs.map { it.name })
    }

    @Test
    fun cacheHit_returnsWithoutNetworkOrLogReads() = runTest {
        store.getResult = WeeklyRecResult.Success(
            songs = listOf(CachedSong(1, "Cached", listOf("X"), null)),
            seedCount = 1,
            displayWeekStart = week
        )
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.Success)
        assertEquals(0, source.similarCallCount)
        assertEquals(0, log.readCount)
        assertNull(store.lastPutSuccess)
    }

    @Test
    fun insufficientDataCached_returnsWithoutNetwork() = runTest {
        store.getResult = WeeklyRecResult.InsufficientData(validPlayCount = 3, distinctSongCount = 1)
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.InsufficientData)
        assertEquals(0, source.similarCallCount)
        assertEquals(0, log.readCount)
    }

    @Test
    fun emptyStats_returnsAndCachesInsufficientData() = runTest {
        log.stats = emptyList()
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.InsufficientData)
        assertEquals(0 to 0, store.putInsufficient)
    }

    @Test
    fun tooFewDistinctSongs_returnsAndCachesInsufficientData() = runTest {
        log.stats = listOf(WeeklySongStat(songId = 1, playCount = 20, lastPlayedAt = lastPlayed))
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.InsufficientData)
        assertEquals(20 to 1, store.putInsufficient)
    }

    // ---- failure paths ----

    @Test
    fun allSimilarRequestsFail_returnsFailureWithoutCacheWrite() = runTest {
        happyPathFixture()
        source.similarFailures += 1L
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.Failure)
        assertNull(store.lastPutSuccess)
    }

    @Test
    fun partialSeedFailure_continuesWithRemainingSeeds() = runTest {
        log.stats = listOf(
            WeeklySongStat(songId = 1, playCount = 5, lastPlayedAt = lastPlayed),
            WeeklySongStat(songId = 2, playCount = 4, lastPlayedAt = lastPlayed)
        )
        source.detailResults[1] = song(1, 10)
        source.detailResults[2] = song(2, 11)
        source.similarFailures += 2L                      // seed 2 fails
        source.similarResults[1] = listOf(SimilarSong(100, "Cand1", listOf(ArtistBrief(20, "ArtistB"))))
        source.detailResults[100] = song(100, 20, name = "Cand1")

        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.Success)
        assertEquals(listOf(100L), (result as WeeklyRecResult.Success).songs.map { it.songId })
    }

    @Test
    fun noCandidateEdges_returnsFailure() = runTest {
        happyPathFixture()
        source.similarResults[1] = emptyList()
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.Failure)
        assertNull(store.lastPutSuccess)
    }

    @Test
    fun listenedSongsAreExcludedFromResult() = runTest {
        happyPathFixture()
        log.stats = listOf(
            WeeklySongStat(songId = 1, playCount = 5, lastPlayedAt = lastPlayed),
            WeeklySongStat(songId = 200, playCount = 1, lastPlayedAt = lastPlayed)
        )
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.Success)
        assertEquals(listOf(100L), (result as WeeklyRecResult.Success).songs.map { it.songId })
    }

    // ---- write-back guard ----

    @Test
    fun accountSwitchDuringGeneration_doesNotWriteCache() = runTest {
        happyPathFixture()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        var sessionGen = 0
        val useCase = buildUseCase(scope = this, sessionGen = { sessionGen })
        val caller = async { useCase.execute(GenerationKey(1L, week)) }
        runCurrent()
        sessionGen = 1                                   // 模拟退出/重登
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(store.lastPutSuccess)
        assertTrue(caller.await() is WeeklyRecResult.Failure)
    }

    // ---- single-flight ----

    @Test
    fun sameKeyConcurrentCalls_shareOneGeneration() = runTest {
        happyPathFixture()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val useCase = buildUseCase(scope = this)
        val a = async { useCase.execute(GenerationKey(1L, week)) }
        val b = async { useCase.execute(GenerationKey(1L, week)) }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        val ra = a.await()
        val rb = b.await()
        assertNotNull(ra)
        assertNotNull(rb)
        assertEquals(2, source.similarCallCount)          // 只跑了一次生成（两个种子）
        assertEquals(listOf("1:$week"), store.putSuccessKeys)
    }

    @Test
    fun differentKey_cancelsPreviousGeneration() = runTest {
        happyPathFixture()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val week2 = week.plusWeeks(1)
        val useCase = buildUseCase(scope = this)
        val old = async { useCase.execute(GenerationKey(1L, week)) }
        runCurrent()
        val new = async { useCase.execute(GenerationKey(1L, week2)) }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        val oldCancelled = try {
            old.await()
            false
        } catch (e: CancellationException) {
            true
        }
        assertTrue(oldCancelled)
        assertTrue(new.await() is WeeklyRecResult.Success)
        assertEquals(listOf("1:$week2"), store.putSuccessKeys)
    }

    /**
     * GC #8 缺陷修复：不同 key 并发到达时，第二把锁内也必须取消旧任务。
     *
     * 竞争窗口（两个调用都在第一把锁读到 inFlight == null，再交错进入第二把锁）极窄，且两把锁之间
     * 没有可注入的挂起点，单线程测试调度器上无法确定性复现（首把锁快路径总是先触发，测不到该分支）。
     * 因此用有界并发压力测试：用 CyclicBarrier(2) 让两个协程在各自的热线程上同时放行进入 execute()，
     * 使它们的锁段在同一时刻竞争同一把 Mutex（公平 Mutex 会让后到的协程排在先到协程的第一把锁之后、
     * 第二把锁之前），从而可靠触发该竞争窗口。网络闸门把两代任务都挡在首个网络调用前，等「任一调用
     * 已被取消」或「两代任务都已到达闸门」二者之一发生后放行。
     * 修复后：无论交错顺序，后到的调用一定会在建新任务前取消旧任务，故恰有一个调用成功、一个被取消
     * （oldSucceeded xor newSucceeded 恒真）。修复前：竞争窗口命中时旧任务不会被取消，两个生成都
     * 成功 → xor 为 false，测试失败（已实测修复前在迭代 171 触发，oldSucceeded=newSucceeded=true）。
     * 该窗口命中依赖真实多线程调度；修复后则是确定性 1。
     */
    @Test
    fun differentKeys_contending_cancelsOldGenerationInSecondLock() = runBlocking(Dispatchers.Default) {
        val key1 = GenerationKey(1L, week)
        val key2 = GenerationKey(1L, week.plusWeeks(1))
        repeat(STRESS_ITERATIONS) { iteration ->
            source = FakeSource()
            log = FakeLog()
            store = FakeStore()
            happyPathFixture()
            val gate = CompletableDeferred<Unit>()
            source.gate = gate
            val useCase = buildUseCase(scope = this)

            val oldDone = CompletableDeferred<Unit>()
            val newDone = CompletableDeferred<Unit>()
            var oldSucceeded = false
            var newSucceeded = false

            // 线程级屏障：两个协程先各自就绪，再同时到达 CyclicBarrier 后一起放行，
            // 让它们的锁段在同一时刻竞争同一把 Mutex，从而可靠制造「首把锁都读到
            // inFlight==null、第二把锁交错」的竞争窗口。
            val barrier = CyclicBarrier(2)
            val oldReady = CompletableDeferred<Unit>()
            val newReady = CompletableDeferred<Unit>()

            val old = async {
                oldReady.complete(Unit)
                barrier.await()
                try {
                    useCase.execute(key1)
                    oldSucceeded = true
                } catch (e: CancellationException) {
                    // 被不同 key 取消：预期
                } finally {
                    oldDone.complete(Unit)
                }
            }
            val new = async {
                newReady.complete(Unit)
                barrier.await()
                try {
                    useCase.execute(key2)
                    newSucceeded = true
                } catch (e: CancellationException) {
                    // 被不同 key 取消：预期
                } finally {
                    newDone.complete(Unit)
                }
            }
            oldReady.await()
            newReady.await()

            // 等「任一调用已被取消」或「两代任务都已到达网络闸门」；withTimeout 只是死锁安全网。
            withTimeout(10_000) {
                select<Unit> {
                    oldDone.onAwait { }
                    newDone.onAwait { }
                    source.bothAtGate.onAwait { }
                }
            }
            gate.complete(Unit)
            old.await()
            new.await()

            assertTrue(
                "迭代 $iteration：并发不同 key 的生成必须单飞（恰一个成功、一个被取消），" +
                    "实际 oldSucceeded=$oldSucceeded newSucceeded=$newSucceeded",
                oldSucceeded xor newSucceeded
            )
        }
    }

    @Test
    fun callerCancellation_doesNotCancelBackgroundTask() = runTest {
        happyPathFixture()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val useCase = buildUseCase(scope = this)
        val caller = async { useCase.execute(GenerationKey(1L, week)) }
        runCurrent()
        caller.cancel()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNotNull(store.lastPutSuccess)              // 后台任务继续完成并写缓存
    }

    // ---- week bounds ----

    @Test
    fun weekBounds_returnsStartAndEndAtLocalMidnight() {
        val (start, end) = weekBounds(previousWeek, week, ZoneId.of("UTC"))
        assertEquals(previousWeek.atStartOfDay(zone).toInstant().toEpochMilli(), start)
        assertEquals(week.atStartOfDay(zone).toInstant().toEpochMilli(), end)
    }

    @Test
    fun weekBounds_usesLocalNaturalDaysAcrossDst() {
        val dstZone = ZoneId.of("America/New_York")
        val sourceStart = LocalDate.of(2026, 3, 2)   // 周一
        val displayStart = LocalDate.of(2026, 3, 9)  // 周一（跨 2026-03-08 春季拨快）
        val (start, end) = weekBounds(sourceStart, displayStart, dstZone)
        assertEquals(sourceStart.atStartOfDay(dstZone).toInstant().toEpochMilli(), start)
        assertEquals(displayStart.atStartOfDay(dstZone).toInstant().toEpochMilli(), end)
        assertEquals((6 * 24L + 23) * 3_600_000L, end - start)  // 拨快损失 1 小时墙钟
    }
}
