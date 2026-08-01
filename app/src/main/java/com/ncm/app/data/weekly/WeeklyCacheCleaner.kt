package com.ncm.app.data.weekly

import android.util.Log
import kotlinx.coroutines.CancellationException

data class ClearWeeklyDataResult(
    val roomCleared: Boolean,
    val recommendationCacheCleared: Boolean
) {
    val success: Boolean get() = roomCleared && recommendationCacheCleared
}

/**
 * 每周推荐数据清理。页面打开/App 启动时清理过期与损坏数据；退出登录时清空当前账号数据。
 * 注意：cleanupOnPageOpen 只清理、不读取播放记录（"命中缓存零请求" 的前提）。
 */
class WeeklyCacheCleaner(
    private val weeklyPlayLog: WeeklyPlayLogPort,
    private val store: WeeklyRecCachePort,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun cleanupOnPageOpen(userId: Long, now: Long = nowMs()) {
        weeklyPlayLog.pruneExpired(userId, now)
        store.removeInvalidIfPresent(userId)
    }

    suspend fun cleanupOnAppStart(userId: Long, now: Long = nowMs()) {
        weeklyPlayLog.pruneAllUsersExpired(now)
        if (userId > 0) {
            store.removeInvalidIfPresent(userId)
        }
    }

    suspend fun clearWeeklyUserData(userId: Long, now: Long = nowMs()): ClearWeeklyDataResult {
        val roomCleared = try {
            weeklyPlayLog.deleteAllByUser(userId) >= 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "clearWeeklyUserData: room delete failed", e)
            false
        }
        val cacheCleared = try {
            store.removeForUserDurable(userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "clearWeeklyUserData: cache delete failed", e)
            false
        }
        return ClearWeeklyDataResult(
            roomCleared = roomCleared,
            recommendationCacheCleared = cacheCleared
        )
    }

    private companion object {
        const val TAG = "WeeklyCacheCleaner"
    }
}

/**
 * 退出登录协调器：保证严格顺序 ① invalidate → ② cancelInFlight → ③ clearWeeklyUserData。
 * 通过 lambda 注入，便于单测记录调用顺序。
 */
class WeeklyLogoutCoordinator(
    private val invalidateSession: () -> Unit,
    private val cancelInFlight: suspend () -> Unit,
    private val cleaner: WeeklyCacheCleaner
) {
    suspend fun execute(userId: Long, now: Long = System.currentTimeMillis()): ClearWeeklyDataResult {
        invalidateSession()
        cancelInFlight()
        return cleaner.clearWeeklyUserData(userId, now)
    }
}
