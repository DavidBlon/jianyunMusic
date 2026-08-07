package com.ncm.app.data.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 在线歌曲 CRUD 与迁移工具查询（spec §12 / 阶段 5）。 */
@Dao
interface OnlineSongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OnlineSongEntity)

    @Query("SELECT * FROM online_songs WHERE pluginId = :pluginId AND remoteId = :remoteId")
    suspend fun findByCompositeKey(pluginId: String, remoteId: String): OnlineSongEntity?

    @Query("SELECT * FROM online_songs")
    suspend fun all(): List<OnlineSongEntity>

    @Query("DELETE FROM online_songs WHERE pluginId = :pluginId AND remoteId = :remoteId")
    suspend fun deleteByKey(pluginId: String, remoteId: String)

    @Query("SELECT COUNT(*) FROM online_songs")
    suspend fun countAll(): Int
}
