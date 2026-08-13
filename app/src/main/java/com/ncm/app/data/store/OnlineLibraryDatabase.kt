package com.ncm.app.data.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [OnlineSongEntity::class], version = 1, exportSchema = false)
abstract class OnlineLibraryDatabase : RoomDatabase() {
    abstract fun onlineSongDao(): OnlineSongDao

    companion object {
        const val NAME = "online_library.db"

        @Volatile
        private var instance: OnlineLibraryDatabase? = null

        fun get(context: Context): OnlineLibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                OnlineLibraryDatabase::class.java,
                NAME
            ).build().also { instance = it }
        }
    }
}
