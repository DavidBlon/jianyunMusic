package com.ncm.app.data.store

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [OnlineSongEntity::class], version = 1, exportSchema = false)
abstract class OnlineLibraryDatabase : RoomDatabase() {
    abstract fun onlineSongDao(): OnlineSongDao

    companion object {
        const val NAME = "online_library.db"
    }
}
