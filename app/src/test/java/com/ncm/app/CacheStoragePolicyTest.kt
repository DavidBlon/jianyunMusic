package com.ncm.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.ncm.app.data.cache.COVER_IMAGE_CACHE_MAX_BYTES
import com.ncm.app.data.cache.LINGLAN_AUDIO_CACHE_MAX_BYTES
import com.ncm.app.data.cache.LinglanAudioCache
import com.ncm.app.data.cache.coverImageCacheDirectory
import com.ncm.app.data.cache.legacyLinglanAudioCacheDirectory
import com.ncm.app.data.cache.linglanAudioCacheDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CacheStoragePolicyTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `audio and cover caches each have a one GiB limit`() {
        val oneGiB = 1024L * 1024L * 1024L

        assertEquals(oneGiB, LINGLAN_AUDIO_CACHE_MAX_BYTES)
        assertEquals(oneGiB, COVER_IMAGE_CACHE_MAX_BYTES)
    }

    @Test
    fun `audio and cover caches are separate Android cache directories`() {
        val audioDirectory = linglanAudioCacheDirectory(context)
        val coverDirectory = coverImageCacheDirectory(context)

        assertEquals(context.cacheDir, audioDirectory.parentFile)
        assertEquals(context.cacheDir, coverDirectory.parentFile)
        assertNotEquals(audioDirectory, coverDirectory)
    }

    @Test
    fun `obsolete audio cache never remains in user data when cache target exists`() {
        val source = legacyLinglanAudioCacheDirectory(context)
        val target = linglanAudioCacheDirectory(context)
        source.deleteRecursively()
        target.deleteRecursively()
        source.mkdirs()
        target.mkdirs()
        File(source, "old-audio.cache").writeBytes(byteArrayOf(1, 2, 3))

        val audioCache = LinglanAudioCache(context)
        try {
            assertFalse(source.exists())
            assertTrue(File(target, "old-audio.cache").exists())
        } finally {
            audioCache.release()
            source.deleteRecursively()
            target.deleteRecursively()
            context.deleteDatabase("exoplayer_internal.db")
        }
    }

    @Test
    fun `media index database is stored under Android cache`() {
        val audioDirectory = linglanAudioCacheDirectory(context)
        val cacheDatabase = File(context.cacheDir, "linglan_audio_cache_index/exoplayer_internal.db")
        audioDirectory.deleteRecursively()
        cacheDatabase.parentFile?.deleteRecursively()
        context.deleteDatabase("exoplayer_internal.db")

        val audioCache = LinglanAudioCache(context)
        try {
            val deadlineNanos = System.nanoTime() + 2_000_000_000L
            while (!cacheDatabase.exists() && System.nanoTime() < deadlineNanos) {
                Thread.sleep(10)
            }
            assertTrue(cacheDatabase.exists())
            assertFalse(context.getDatabasePath("exoplayer_internal.db").exists())
        } finally {
            audioCache.release()
            audioDirectory.deleteRecursively()
            cacheDatabase.parentFile?.deleteRecursively()
            context.deleteDatabase("exoplayer_internal.db")
        }
    }

    @Test
    fun `existing media index is moved out of user data`() {
        val audioDirectory = linglanAudioCacheDirectory(context)
        val sourceDatabase = context.getDatabasePath("exoplayer_internal.db")
        val cacheDatabase = File(context.cacheDir, "linglan_audio_cache_index/exoplayer_internal.db")
        audioDirectory.deleteRecursively()
        cacheDatabase.parentFile?.deleteRecursively()
        context.deleteDatabase("exoplayer_internal.db")
        sourceDatabase.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(sourceDatabase, null).close()

        val audioCache = LinglanAudioCache(context)
        try {
            val deadlineNanos = System.nanoTime() + 2_000_000_000L
            while (!cacheDatabase.exists() && System.nanoTime() < deadlineNanos) {
                Thread.sleep(10)
            }
            assertTrue(cacheDatabase.exists())
            assertFalse(sourceDatabase.exists())
        } finally {
            audioCache.release()
            audioDirectory.deleteRecursively()
            cacheDatabase.parentFile?.deleteRecursively()
            context.deleteDatabase("exoplayer_internal.db")
        }
    }
}
