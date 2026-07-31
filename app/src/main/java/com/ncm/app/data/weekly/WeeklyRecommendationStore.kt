package com.ncm.app.data.weekly

import android.content.SharedPreferences
import com.ncm.app.domain.weekly.CachedSong
import com.ncm.app.domain.weekly.WeeklyRecCache
import com.ncm.app.domain.weekly.WeeklyRecCacheResult
import com.ncm.app.domain.weekly.WeeklyRecResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

interface WeeklyRecCachePort {
    fun get(userId: Long, displayWeekStart: LocalDate, sourceWeekStart: LocalDate): WeeklyRecResult?
    fun putSuccess(
        userId: Long,
        sourceWeekStart: LocalDate,
        displayWeekStart: LocalDate,
        songs: List<CachedSong>,
        seedCount: Int,
        generatedAt: Long
    )
    fun putInsufficientData(
        userId: Long,
        sourceWeekStart: LocalDate,
        displayWeekStart: LocalDate,
        validPlayCount: Int,
        distinctSongCount: Int,
        generatedAt: Long
    )
    fun removeInvalidIfPresent(userId: Long): Boolean
    suspend fun removeForUserDurable(userId: Long): Boolean
}

/**
 * 每周推荐缓存。单键 `weekly_rec:{userId}`；周内命中即零请求。
 * 解析/校验失败时删除缓存（apply()）；退出登录用 removeForUserDurable（commit() 带一次重试）。
 */
class WeeklyRecommendationStore(
    private val prefs: SharedPreferences
) : WeeklyRecCachePort {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "resultType"
    }

    override fun get(
        userId: Long,
        displayWeekStart: LocalDate,
        sourceWeekStart: LocalDate
    ): WeeklyRecResult? {
        val key = keyFor(userId)
        val raw = prefs.getString(key, null) ?: return null
        return try {
            val cache = json.decodeFromString<WeeklyRecCache>(raw)
            if (cache.schemaVersion != SCHEMA_VERSION ||
                cache.displayWeekStart != displayWeekStart.toString() ||
                cache.sourceWeekStart != sourceWeekStart.toString()
            ) {
                prefs.edit().remove(key).apply()
                null
            } else {
                when (val result = cache.result) {
                    is WeeklyRecCacheResult.Success -> WeeklyRecResult.Success(
                        songs = result.songs,
                        seedCount = result.seedCount,
                        displayWeekStart = displayWeekStart
                    )
                    is WeeklyRecCacheResult.InsufficientData -> WeeklyRecResult.InsufficientData(
                        validPlayCount = result.validPlayCount,
                        distinctSongCount = result.distinctSongCount
                    )
                }
            }
        } catch (e: Exception) {
            // JSON 解码失败 → 视为缓存损坏，删除。纯同步 prefs 操作，无协程取消语义。
            prefs.edit().remove(key).apply()
            null
        }
    }

    override fun putSuccess(
        userId: Long,
        sourceWeekStart: LocalDate,
        displayWeekStart: LocalDate,
        songs: List<CachedSong>,
        seedCount: Int,
        generatedAt: Long
    ) {
        val cache = WeeklyRecCache(
            schemaVersion = SCHEMA_VERSION,
            sourceWeekStart = sourceWeekStart.toString(),
            displayWeekStart = displayWeekStart.toString(),
            result = WeeklyRecCacheResult.Success(songs = songs, seedCount = seedCount),
            generatedAt = generatedAt
        )
        prefs.edit().putString(keyFor(userId), json.encodeToString(cache)).apply()
    }

    override fun putInsufficientData(
        userId: Long,
        sourceWeekStart: LocalDate,
        displayWeekStart: LocalDate,
        validPlayCount: Int,
        distinctSongCount: Int,
        generatedAt: Long
    ) {
        val cache = WeeklyRecCache(
            schemaVersion = SCHEMA_VERSION,
            sourceWeekStart = sourceWeekStart.toString(),
            displayWeekStart = displayWeekStart.toString(),
            result = WeeklyRecCacheResult.InsufficientData(
                validPlayCount = validPlayCount,
                distinctSongCount = distinctSongCount
            ),
            generatedAt = generatedAt
        )
        prefs.edit().putString(keyFor(userId), json.encodeToString(cache)).apply()
    }

    override fun removeInvalidIfPresent(userId: Long): Boolean {
        val key = keyFor(userId)
        val raw = prefs.getString(key, null) ?: return false
        val invalid = try {
            val cache = json.decodeFromString<WeeklyRecCache>(raw)
            cache.schemaVersion != SCHEMA_VERSION
        } catch (e: Exception) {
            true
        }
        if (invalid) prefs.edit().remove(key).apply()
        return invalid
    }

    override suspend fun removeForUserDurable(userId: Long): Boolean = withContext(Dispatchers.IO) {
        var attempts = 0
        var success = false
        while (attempts < 2 && !success) {
            success = prefs.edit().remove(keyFor(userId)).commit()
            attempts++
        }
        success
    }

    private fun keyFor(userId: Long): String = "$KEY_PREFIX$userId"

    companion object {
        const val SCHEMA_VERSION = 1
        const val KEY_PREFIX = "weekly_rec:"
        const val PREF_NAME = "ncm_weekly_rec"
    }
}
