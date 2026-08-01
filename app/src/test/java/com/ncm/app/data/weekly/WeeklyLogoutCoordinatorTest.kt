package com.ncm.app.data.weekly

import com.ncm.app.domain.weekly.CachedSong
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklyLogoutCoordinatorTest {

    private class FakeLogPort(
        private val calls: MutableList<String>,
        var failDelete: Boolean = false
    ) : WeeklyPlayLogPort {
        override suspend fun read(userId: Long, startMs: Long, endMs: Long): List<WeeklySongStat> = emptyList()
        override suspend fun pruneExpired(userId: Long, now: Long) {}
        override suspend fun pruneAllUsersExpired(now: Long) {}
        override suspend fun deleteAllByUser(userId: Long): Long {
            calls += "clear-room"
            if (failDelete) throw IOException("room unavailable")
            return 2
        }
    }

    private class FakeCachePort(
        private val calls: MutableList<String>
    ) : WeeklyRecCachePort {
        var durableRemoveCalls = 0
        override fun get(userId: Long, displayWeekStart: LocalDate, sourceWeekStart: LocalDate) = null
        override fun putSuccess(userId: Long, sourceWeekStart: LocalDate, displayWeekStart: LocalDate, songs: List<CachedSong>, seedCount: Int, generatedAt: Long) {}
        override fun putInsufficientData(userId: Long, sourceWeekStart: LocalDate, displayWeekStart: LocalDate, validPlayCount: Int, distinctSongCount: Int, generatedAt: Long) {}
        override fun removeInvalidIfPresent(userId: Long): Boolean = false
        override suspend fun removeForUserDurable(userId: Long): Boolean {
            calls += "clear-cache"
            durableRemoveCalls++
            return true
        }
    }

    private val calls = mutableListOf<String>()
    private lateinit var logPort: FakeLogPort
    private lateinit var cachePort: FakeCachePort
    private lateinit var cleaner: WeeklyCacheCleaner

    @Before
    fun setUp() {
        logPort = FakeLogPort(calls)
        cachePort = FakeCachePort(calls)
        cleaner = WeeklyCacheCleaner(logPort, cachePort)
    }

    @Test
    fun execute_runsStrictOrderInvalidateThenCancelThenClear() = runBlocking {
        val coordinator = WeeklyLogoutCoordinator(
            invalidateSession = { calls += "invalidate" },
            cancelInFlight = { calls += "cancel"; delay(10) },
            cleaner = cleaner
        )
        val result = coordinator.execute(userId = 7L, now = 1_750_000_000_000L)
        assertEquals(listOf("invalidate", "cancel", "clear-room", "clear-cache"), calls)
        assertTrue(result.success)
        assertEquals(1, cachePort.durableRemoveCalls)
    }

    @Test
    fun execute_returnsFailureResultWhenRoomDeleteThrows() = runBlocking {
        logPort.failDelete = true
        val coordinator = WeeklyLogoutCoordinator(
            invalidateSession = { calls += "invalidate" },
            cancelInFlight = { calls += "cancel" },
            cleaner = cleaner
        )
        val result = coordinator.execute(userId = 7L, now = 1_750_000_000_000L)
        assertNotNull(result)
        assertTrue(!result.success)
        assertEquals(listOf("invalidate", "cancel", "clear-room", "clear-cache"), calls)
    }
}
