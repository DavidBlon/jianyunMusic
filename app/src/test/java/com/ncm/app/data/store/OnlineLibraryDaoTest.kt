package com.ncm.app.data.store

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OnlineLibraryDaoTest {

    private fun db(): OnlineLibraryDatabase =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), OnlineLibraryDatabase::class.java).build()

    @Test
    fun upsertAndQueryByCompositeKey() = runBlocking {
        val dao = db().onlineSongDao()
        val a = OnlineSongEntity("linglan.kw", "123", "A", "[]", null, 1000, null, "{}", "1.0.0", 1)
        dao.upsert(a)
        val loaded = dao.findByCompositeKey("linglan.kw", "123")
        assertEquals("A", loaded?.title)
    }

    @Test
    fun sameRemoteIdDifferentPluginCoexist() = runBlocking {
        val dao = db().onlineSongDao()
        dao.upsert(OnlineSongEntity("linglan.kw", "123", "A", "[]", null, 1000, null, "{}", "1.0.0", 1))
        dao.upsert(OnlineSongEntity("linglan.tx", "123", "B", "[]", null, 1000, null, "{}", "1.0.0", 1))
        assertEquals(2, dao.countAll())
    }

    @Test
    fun deleteByCompositeKeyRemovesOnlyThatKey() = runBlocking {
        val dao = db().onlineSongDao()
        dao.upsert(OnlineSongEntity("linglan.kw", "123", "A", "[]", null, 1000, null, "{}", "1.0.0", 1))
        dao.upsert(OnlineSongEntity("linglan.kw", "456", "B", "[]", null, 1000, null, "{}", "1.0.0", 1))
        dao.deleteByKey("linglan.kw", "123")
        assertNull(dao.findByCompositeKey("linglan.kw", "123"))
        assertEquals("B", dao.findByCompositeKey("linglan.kw", "456")?.title)
    }
}
