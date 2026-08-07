package com.ncm.app.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineLibraryMigrationTest {

    @Test
    fun compositeKeyUniquenessPreventsCrossPluginCollision() {
        val a = OnlineSongEntity(pluginId = "linglan.kw", remoteId = "123", title = "A")
        val b = OnlineSongEntity(pluginId = "linglan.tx", remoteId = "123", title = "B")
        // 同 remoteId 不同 plugin 必须共存；schema 用 (pluginId, remoteId) 联合主键。
        assertEquals(a.asCompositeKey(), "linglan.kw#123")
        assertEquals(b.asCompositeKey(), "linglan.tx#123")
        assertTrue(a.asCompositeKey() != b.asCompositeKey())
    }
}
