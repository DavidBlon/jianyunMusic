package com.ncm.app.data.weekly

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklyPlayLogTest {

    private val zone = ZoneId.of("UTC")
    private val fixedNow = 1_750_000_000_000L
    private val dayMs = 86_400_000L

    private lateinit var db: WeeklyDatabase
    private lateinit var log: WeeklyPlayLog

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, WeeklyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        log = WeeklyPlayLog(db, zoneId = zone, nowMs = { fixedNow })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun event(
        userId: Long = 1L,
        songId: Long,
        sessionStart: Long,
        sessionId: String
    ) = PlayEventEntity(
        userId = userId,
        songId = songId,
        playbackSessionId = sessionId,
        sessionStartedAt = sessionStart
    )

    @Test
    fun record_dedupsByPlaybackSessionId() = runBlocking {
        val e = event(songId = 10, sessionStart = fixedNow - dayMs, sessionId = "10:abc")
        log.record(e)
        log.record(e)  // same (userId, playbackSessionId) → INSERT OR IGNORE
        assertEquals(1, db.weeklyPlayLogDao().countByUser(1L))
    }

    @Test
    fun record_appliesPerUserEventCap() = runBlocking {
        repeat(WeeklyPlayLog.MAX_EVENTS_PER_USER + 1) { i ->
            log.record(
                event(
                    songId = 1,
                    sessionStart = fixedNow - i * 1_000L,
                    sessionId = "s$i"
                )
            )
        }
        assertEquals(WeeklyPlayLog.MAX_EVENTS_PER_USER.toLong(), db.weeklyPlayLogDao().countByUser(1L).toLong())
        val all = log.read(1L, 0L, Long.MAX_VALUE)
        assertEquals(1, all.size)                      // all same song → grouped
        assertEquals(WeeklyPlayLog.MAX_EVENTS_PER_USER, all[0].playCount)
    }

    @Test
    fun record_prunesEventsOlderThanFourteenNaturalDays() = runBlocking {
        val old = event(songId = 1, sessionStart = fixedNow - 15 * dayMs, sessionId = "old")
        log.record(old)
        val fresh = event(songId = 2, sessionStart = fixedNow - dayMs, sessionId = "fresh")
        log.record(fresh)   // record always prunes on insert
        assertEquals(1, db.weeklyPlayLogDao().countByUser(1L))
    }

    @Test
    fun read_groupsStatsWithinWindow() = runBlocking {
        val start = fixedNow - 7 * dayMs
        val end = fixedNow
        log.record(event(songId = 10, sessionStart = start + 1_000, sessionId = "a1"))
        log.record(event(songId = 10, sessionStart = start + 2_000, sessionId = "a2"))
        log.record(event(songId = 10, sessionStart = start + 3_000, sessionId = "a3"))
        log.record(event(songId = 20, sessionStart = start + 4_000, sessionId = "b1"))
        val stats = log.read(1L, start, end)
        assertEquals(2, stats.size)
        val a = stats.first { it.songId == 10L }
        val b = stats.first { it.songId == 20L }
        assertEquals(3, a.playCount)
        assertEquals(start + 3_000, a.lastPlayedAt)
        assertEquals(1, b.playCount)
    }

    @Test
    fun read_excludesEventsOutsideWindow() = runBlocking {
        val start = fixedNow - 7 * dayMs
        val end = fixedNow - 3 * dayMs
        log.record(event(songId = 10, sessionStart = start + 1_000, sessionId = "inside"))
        log.record(event(songId = 20, sessionStart = fixedNow - 10 * dayMs, sessionId = "before"))
        log.record(event(songId = 30, sessionStart = fixedNow, sessionId = "after"))
        val stats = log.read(1L, start, end)
        assertEquals(listOf(10L), stats.map { it.songId })
    }

    @Test
    fun pruneExpired_removesOnlyOldEvents() = runBlocking {
        log.record(event(songId = 10, sessionStart = fixedNow - 15 * dayMs, sessionId = "old"))
        log.record(event(songId = 20, sessionStart = fixedNow - dayMs, sessionId = "new"))
        log.pruneExpired(1L, fixedNow)
        val remaining = log.read(1L, 0L, Long.MAX_VALUE)
        assertEquals(listOf(20L), remaining.map { it.songId })
    }

    @Test
    fun deleteAllByUser_returnsDeletedCountAndLeavesOtherUsers() = runBlocking {
        log.record(event(userId = 1, songId = 10, sessionStart = fixedNow - dayMs, sessionId = "u1"))
        log.record(event(userId = 2, songId = 20, sessionStart = fixedNow - dayMs, sessionId = "u2"))
        val deleted = log.deleteAllByUser(1L)
        assertEquals(1L, deleted)
        assertEquals(0L, db.weeklyPlayLogDao().countByUser(1L).toLong())
        assertEquals(1L, db.weeklyPlayLogDao().countByUser(2L).toLong())
    }

    @Test
    fun pruneAllUsersExpired_removesAcrossAllUsers() = runBlocking {
        log.record(event(userId = 1, songId = 10, sessionStart = fixedNow - 15 * dayMs, sessionId = "a"))
        log.record(event(userId = 2, songId = 20, sessionStart = fixedNow - dayMs, sessionId = "b"))
        log.pruneAllUsersExpired(fixedNow)
        assertEquals(0L, db.weeklyPlayLogDao().countByUser(1L).toLong())
        assertEquals(1L, db.weeklyPlayLogDao().countByUser(2L).toLong())
    }
}
