package com.ncm.app.data.weekly

import android.content.Context
import com.ncm.app.domain.weekly.CachedSong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklyCacheCleanerTest {

    private class FakeLogPort : WeeklyPlayLogPort {
        var pruneExpiredCalls = 0
        var pruneAllCalls = 0
        var readCount = 0
        var deletedUsers = mutableListOf<Long>()
        var failDelete = false
        override suspend fun read(userId: Long, startMs: Long, endMs: Long): List<WeeklySongStat> {
            readCount++
            return emptyList()
        }
        override suspend fun pruneExpired(userId: Long, now: Long) { pruneExpiredCalls++ }
        override suspend fun pruneAllUsersExpired(now: Long) { pruneAllCalls++ }
        override suspend fun deleteAllByUser(userId: Long): Long {
            deletedUsers += userId
            if (failDelete) throw IOException("room unavailable")
            return 3
        }
    }

    private val sourceWeek = LocalDate.of(2026, 7, 20)
    private val displayWeek = LocalDate.of(2026, 7, 27)
    private val songs = listOf(CachedSong(1, "A", listOf("X"), null))
    private val fixedNow = 1_750_000_000_000L

    private lateinit var logPort: FakeLogPort
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var store: WeeklyRecommendationStore
    private lateinit var cleaner: WeeklyCacheCleaner

    @Before
    fun setUp() {
        logPort = FakeLogPort()
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("weekly_cleaner_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = WeeklyRecommendationStore(prefs)
        cleaner = WeeklyCacheCleaner(logPort, store)
    }

    @Test
    fun cleanupOnPageOpen_prunesAndRemovesCorruptCache() = runBlocking {
        prefs.edit().putString("weekly_rec:1", "{corrupt").commit()
        cleaner.cleanupOnPageOpen(1L, now = fixedNow)
        assertEquals(1, logPort.pruneExpiredCalls)
        assertEquals(0, logPort.readCount)                       // 打开页面不得读播放记录
        assertNull(prefs.getString("weekly_rec:1", null))
    }

    @Test
    fun cleanupOnPageOpen_keepsValidCache() = runBlocking {
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 1L)
        cleaner.cleanupOnPageOpen(1L, now = fixedNow)
        assertNotNull(prefs.getString("weekly_rec:1", null))
    }

    @Test
    fun cleanupOnAppStart_prunesAllAndRemovesInvalidForUser() = runBlocking {
        prefs.edit().putString("weekly_rec:5", "{bad").commit()
        cleaner.cleanupOnAppStart(5L, now = fixedNow)
        assertEquals(1, logPort.pruneAllCalls)
        assertNull(prefs.getString("weekly_rec:5", null))
    }

    @Test
    fun clearWeeklyUserData_successClearsRoomAndCache() = runBlocking {
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 1L)
        val result = cleaner.clearWeeklyUserData(1L)
        assertTrue(result.success)
        assertEquals(listOf(1L), logPort.deletedUsers)
        assertNull(prefs.getString("weekly_rec:1", null))
    }

    @Test
    fun clearWeeklyUserData_roomFailureReportedButCacheStillCleared() = runBlocking {
        logPort.failDelete = true
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 1L)
        val result = cleaner.clearWeeklyUserData(1L)
        assertFalse(result.success)
        assertFalse(result.roomCleared)
        assertTrue(result.recommendationCacheCleared)
        assertNull(prefs.getString("weekly_rec:1", null))
    }

    @Test
    fun clearWeeklyUserData_neverReadsPlayLog() = runBlocking {
        cleaner.clearWeeklyUserData(1L)
        assertEquals(0, logPort.readCount)
    }
}
