package com.ncm.app.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class DownloadedSongMigrationTest {

    @Test
    fun incompleteOrMissingFileIsNotAValidDownload() {
        val incomplete = DownloadedSongEntity(
            localId = "f1", sourceTrackKey = "kw#1", uri = "file:///tmp/a.part",
            title = "A", complete = false
        )
        assertEquals(false, incomplete.isPlayable())
    }

    @Test
    fun completeExistingFileIsPlayableLocally() {
        val file = Files.createTempFile("downloaded-song", ".mp3").toFile()
        file.writeText("audio")
        val complete = DownloadedSongEntity(
            localId = "f2", sourceTrackKey = "kw#2", uri = file.toURI().toString(),
            title = "B", complete = true
        )
        assertEquals(true, complete.isPlayable())
    }

    @Test
    fun completeFlagWithoutExistingFileIsNotPlayable() {
        val complete = DownloadedSongEntity(
            localId = "f3", sourceTrackKey = "kw#3", uri = "file:///nonexistent-dir/x.mp3",
            title = "C", complete = true
        )
        assertEquals(false, complete.isPlayable())
    }

    @Test
    fun linglanCacheKeyMapsToLocalRecord() {
        val record = DownloadedSongEntity.fromLinglanCacheKey("linglan-audio:123:128000", "file:///cache/123.mp3")
        assertEquals("legacy-netease#123", record?.sourceTrackKey)
        assertEquals("cache-123", record?.localId)
        assertNull(DownloadedSongEntity.fromLinglanCacheKey("other:1", "file:///x"))
    }
}
