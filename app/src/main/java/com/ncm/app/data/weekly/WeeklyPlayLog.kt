package com.ncm.app.data.weekly

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import java.time.Instant
import java.time.ZoneId

@Entity(
    tableName = "weekly_play_event",
    indices = [
        Index(value = ["userId", "sessionStartedAt"]),
        Index(value = ["userId", "playbackSessionId"], unique = true)
    ]
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val songId: Long,
    val playbackSessionId: String,
    val sessionStartedAt: Long
)

data class WeeklySongStat(
    val songId: Long,
    val playCount: Int,
    val lastPlayedAt: Long
)

@Dao
interface WeeklyPlayLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: PlayEventEntity): Long

    @Query("DELETE FROM weekly_play_event WHERE userId = :userId AND sessionStartedAt < :cutoff")
    suspend fun deleteOlderThan(userId: Long, cutoff: Long): Int

    @Query("DELETE FROM weekly_play_event WHERE id IN (SELECT id FROM weekly_play_event WHERE userId = :userId ORDER BY sessionStartedAt ASC LIMIT :limit)")
    suspend fun deleteOldest(userId: Long, limit: Int): Int

    @Query("SELECT COUNT(*) FROM weekly_play_event WHERE userId = :userId")
    suspend fun countByUser(userId: Long): Int

    @Query(
        "SELECT songId, COUNT(*) AS playCount, MAX(sessionStartedAt) AS lastPlayedAt " +
            "FROM weekly_play_event " +
            "WHERE userId = :userId AND sessionStartedAt >= :start AND sessionStartedAt < :end " +
            "GROUP BY songId"
    )
    suspend fun queryWeeklyStats(userId: Long, start: Long, end: Long): List<WeeklySongStat>

    @Query("DELETE FROM weekly_play_event WHERE userId = :userId")
    suspend fun deleteAllByUser(userId: Long): Int

    @Query("DELETE FROM weekly_play_event WHERE sessionStartedAt < :cutoff")
    suspend fun deleteAllUsersOlderThan(cutoff: Long): Int
}

@Database(entities = [PlayEventEntity::class], version = 1, exportSchema = false)
abstract class WeeklyDatabase : RoomDatabase() {
    abstract fun weeklyPlayLogDao(): WeeklyPlayLogDao

    companion object {
        @Volatile
        private var instance: WeeklyDatabase? = null

        fun get(context: Context): WeeklyDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WeeklyDatabase::class.java,
                    "weekly_play.db"
                ).build().also { instance = it }
            }
        }
    }
}

interface WeeklyPlayLogPort {
    suspend fun read(userId: Long, startMs: Long, endMs: Long): List<WeeklySongStat>
    suspend fun pruneExpired(userId: Long, now: Long)
    suspend fun pruneAllUsersExpired(now: Long)
    suspend fun deleteAllByUser(userId: Long): Long
}

class WeeklyPlayLog(
    private val database: WeeklyDatabase,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : WeeklyPlayLogPort {

    private val dao = database.weeklyPlayLogDao()

    /** 记录一次有效播放；插入去重、按用户 2000 条封顶、顺带清理 14 个本地自然日前旧数据。 */
    suspend fun record(event: PlayEventEntity) {
        database.withTransaction {
            val inserted = dao.insert(event)
            if (inserted >= 0) {
                val count = dao.countByUser(event.userId)
                if (count > MAX_EVENTS_PER_USER) {
                    dao.deleteOldest(event.userId, count - MAX_EVENTS_PER_USER)
                }
            }
            dao.deleteOlderThan(event.userId, cutoffFor(nowMs()))
        }
    }

    override suspend fun read(userId: Long, startMs: Long, endMs: Long): List<WeeklySongStat> =
        dao.queryWeeklyStats(userId, startMs, endMs)

    override suspend fun pruneExpired(userId: Long, now: Long) {
        dao.deleteOlderThan(userId, cutoffFor(now))
    }

    override suspend fun pruneAllUsersExpired(now: Long) {
        dao.deleteAllUsersOlderThan(cutoffFor(now))
    }

    override suspend fun deleteAllByUser(userId: Long): Long = dao.deleteAllByUser(userId).toLong()

    /** 14 个本地自然日前的 00:00（含当天其余时段）。 */
    private fun cutoffFor(now: Long): Long {
        val zone = zoneId
        return Instant.ofEpochMilli(now)
            .atZone(zone)
            .toLocalDate()
            .minusDays(CUTOFF_NATURAL_DAYS)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    companion object {
        const val MAX_EVENTS_PER_USER = 2000
        const val CUTOFF_NATURAL_DAYS = 14L
    }
}
