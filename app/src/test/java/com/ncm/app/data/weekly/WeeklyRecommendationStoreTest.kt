package com.ncm.app.data.weekly

import android.content.Context
import com.ncm.app.domain.weekly.CachedSong
import com.ncm.app.domain.weekly.WeeklyRecResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklyRecommendationStoreTest {

    private val sourceWeek = LocalDate.of(2026, 7, 20)
    private val displayWeek = LocalDate.of(2026, 7, 27)
    private val songs = listOf(CachedSong(1, "A", listOf("X"), null))

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var store: WeeklyRecommendationStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("weekly_store_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = WeeklyRecommendationStore(prefs)
    }

    private fun key(userId: Long) = "${WeeklyRecommendationStore.KEY_PREFIX}$userId"

    @Test
    fun putSuccess_thenGet_returnsSameResult() {
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 2, generatedAt = 123L)
        val result = store.get(1L, displayWeek, sourceWeek)
        assertNotNull(result)
        result as WeeklyRecResult.Success
        assertEquals(songs, result.songs)
        assertEquals(2, result.seedCount)
        assertEquals(displayWeek, result.displayWeekStart)
    }

    @Test
    fun putInsufficientData_thenGet_returnsInsufficientData() {
        store.putInsufficientData(1L, sourceWeek, displayWeek, validPlayCount = 3, distinctSongCount = 1, generatedAt = 123L)
        val result = store.get(1L, displayWeek, sourceWeek)
        result as WeeklyRecResult.InsufficientData
        assertEquals(3, result.validPlayCount)
        assertEquals(1, result.distinctSongCount)
    }

    @Test
    fun get_missingKey_returnsNull() {
        assertNull(store.get(1L, displayWeek, sourceWeek))
    }

    @Test
    fun get_weekMismatch_treatsAsMissAndRemoves() {
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 123L)
        val otherWeek = LocalDate.of(2026, 8, 3)
        assertNull(store.get(1L, otherWeek, sourceWeek.minusWeeks(1)))
        assertNull(prefs.getString(key(1L), null))
    }

    @Test
    fun get_schemaMismatch_treatsAsMissAndRemoves() {
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 123L)
        // 篡改成 schema 2，模拟未来版本
        val raw = prefs.getString(key(1L), null)!!
        val tampered = raw.replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        prefs.edit().putString(key(1L), tampered).commit()
        assertNull(store.get(1L, displayWeek, sourceWeek))
        assertNull(prefs.getString(key(1L), null))
    }

    @Test
    fun get_corruptJson_treatsAsMissAndRemoves() {
        prefs.edit().putString(key(1L), "{not-json").commit()
        assertNull(store.get(1L, displayWeek, sourceWeek))
        assertNull(prefs.getString(key(1L), null))
    }

    @Test
    fun get_toleratesUnknownKeys() {
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 123L)
        val raw = prefs.getString(key(1L), null)!!
        val extended = raw.replace("{\"schemaVersion\"", "{\"futureField\":true,\"schemaVersion\"")
        prefs.edit().putString(key(1L), extended).commit()
        assertNotNull(store.get(1L, displayWeek, sourceWeek))
    }

    @Test
    fun sameKey_overwritesWithNewestWeek() {
        store.putSuccess(1L, sourceWeek, displayWeek, listOf(CachedSong(1, "A", listOf("X"), null)), seedCount = 1, generatedAt = 111L)
        val nextWeek = displayWeek.plusWeeks(1)
        store.putSuccess(1L, nextWeek.minusWeeks(1), nextWeek, listOf(CachedSong(2, "B", listOf("Y"), null)), seedCount = 1, generatedAt = 222L)
        val result = store.get(1L, nextWeek, nextWeek.minusWeeks(1))
        result as WeeklyRecResult.Success
        assertEquals(listOf(CachedSong(2, "B", listOf("Y"), null)), result.songs)
    }

    @Test
    fun removeInvalidIfPresent_removesCorruptButKeepsValid() {
        prefs.edit().putString(key(1L), "{bad").commit()
        assertTrue(store.removeInvalidIfPresent(1L))
        assertNull(prefs.getString(key(1L), null))

        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 123L)
        assertTrue(!store.removeInvalidIfPresent(1L))
        assertNotNull(prefs.getString(key(1L), null))
    }

    @Test
    fun removeForUserDurable_removesAndCommits() = runBlocking {
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 123L)
        assertTrue(store.removeForUserDurable(1L))
        assertNull(prefs.getString(key(1L), null))
    }
}
