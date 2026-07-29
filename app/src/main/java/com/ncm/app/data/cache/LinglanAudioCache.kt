package com.ncm.app.data.cache

import android.content.Context
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.ncm.app.data.model.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LinglanCacheStats(
    val songCount: Int = 0,
    val sizeBytes: Long = 0
)

data class CachedLinglanAudio(
    val url: String,
    val source: String,
    val bitrate: Int,
    val cacheKey: String
)

/**
 * The only persistent playback cache in the app.
 *
 * NetEase and Kugou media items never receive one of these cache keys, so AppPlayer routes them
 * straight to the network. Linglan entries live in noBackupFilesDir: they survive restarts but are
 * not copied into Android cloud backups.
 */
class LinglanAudioCache(context: Context) {
    private companion object {
        private const val CACHE_DIRECTORY = "linglan_audio_cache"
        private const val LEGACY_UNIVERSAL_CACHE_DIRECTORY = "media"
        private const val ORIGIN_URL_METADATA = "com.ncm.app.linglan.ORIGIN_URL"
        private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
        private const val CACHE_ONLY_HOST = "linglan-cache.invalid"
        private const val BACKGROUND_FILL_DELAY_MS = 2_500L
    }

    private val lock = Any()
    private val fillScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fillJobs = mutableMapOf<String, Job>()
    private val fillWriters = mutableMapOf<String, CacheWriter>()

    val cache: SimpleCache

    init {
        // Older builds cached every provider in cacheDir/media. Remove it before AppPlayer starts
        // so NetEase and Kugou never remain readable from the legacy universal cache.
        runCatching {
            File(context.cacheDir, LEGACY_UNIVERSAL_CACHE_DIRECTORY)
                .takeIf(File::exists)
                ?.deleteRecursively()
        }
        cache = SimpleCache(
            File(context.noBackupFilesDir, CACHE_DIRECTORY),
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            StandaloneDatabaseProvider(context)
        )
    }

    fun cacheKey(songId: Long, bitrate: Int): String {
        return LinglanCachePolicy.cacheKey(songId, bitrate)
    }

    fun rememberSource(songId: Long, bitrate: Int, url: String) {
        if (songId <= 0 || url.isBlank()) return
        runCatching {
            synchronized(lock) {
                cache.applyContentMetadataMutations(
                    cacheKey(songId, bitrate),
                    ContentMetadataMutations().set(ORIGIN_URL_METADATA, url)
                )
            }
        }
    }

    fun findReusable(songId: Long, bitrate: Int): CachedLinglanAudio? {
        val normalizedBitrate = LinglanCachePolicy.normalizeBitrate(bitrate)
        val key = cacheKey(songId, normalizedBitrate)
        return runCatching {
            synchronized(lock) {
                if (key !in cache.keys) return@synchronized null
                val metadata = cache.getContentMetadata(key)
                val contentLength = ContentMetadata.getContentLength(metadata)
                val isComplete = contentLength != C.LENGTH_UNSET.toLong() &&
                    contentLength > 0 &&
                    cache.isCached(key, 0, contentLength)
                if (isComplete) {
                    return@synchronized CachedLinglanAudio(
                        url = "https://$CACHE_ONLY_HOST/$songId/$normalizedBitrate.mp3",
                        source = PlaybackSource.LINGLAN_CACHE,
                        bitrate = normalizedBitrate,
                        cacheKey = key
                    )
                }

                val originUrl = metadata.get(ORIGIN_URL_METADATA, "")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@synchronized null
                CachedLinglanAudio(
                    url = originUrl,
                    source = PlaybackSource.LINGLAN,
                    bitrate = normalizedBitrate,
                    cacheKey = key
                )
            }
        }.getOrNull()
    }

    /**
     * Finishes caching the already obtained Linglan URL in the background. The short delay lets
     * playback acquire the cache write span first, so startup is never held behind a full download.
     */
    fun scheduleFullCache(
        url: String,
        cacheKey: String,
        dataSourceFactory: () -> CacheDataSource
    ) {
        if (url.isBlank() || !LinglanCachePolicy.isLinglanCacheKey(cacheKey)) return
        synchronized(lock) {
            val isComplete = runCatching { isFullyCached(cacheKey) }.getOrDefault(false)
            if (fillJobs[cacheKey]?.isActive == true || isComplete) return
            val job = fillScope.launch(start = CoroutineStart.LAZY) {
                val ownJob = currentCoroutineContext()[Job]
                var writer: CacheWriter? = null
                try {
                    delay(BACKGROUND_FILL_DELAY_MS)
                    val createdWriter = CacheWriter(
                        dataSourceFactory(),
                        DataSpec.Builder()
                            .setUri(url)
                            .setKey(cacheKey)
                            .build(),
                        null,
                        null
                    )
                    writer = createdWriter
                    synchronized(lock) {
                        if (fillJobs[cacheKey] !== ownJob) {
                            createdWriter.cancel()
                            return@launch
                        }
                        fillWriters[cacheKey] = createdWriter
                    }
                    createdWriter.cache()
                } catch (_: CancellationException) {
                    // Explicit cache clearing cancels pending fills.
                } catch (_: Exception) {
                    // Playback can still use the source URL and any bytes already cached.
                } finally {
                    synchronized(lock) {
                        if (fillWriters[cacheKey] === writer) {
                            fillWriters.remove(cacheKey)
                        }
                        if (fillJobs[cacheKey] === ownJob) {
                            fillJobs.remove(cacheKey)
                        }
                    }
                }
            }
            fillJobs[cacheKey] = job
            job.start()
        }
    }

    fun cancelPendingWrites() {
        val writers: List<CacheWriter>
        val jobs: List<Job>
        synchronized(lock) {
            writers = fillWriters.values.toList()
            jobs = fillJobs.values.toList()
            fillWriters.clear()
            fillJobs.clear()
        }
        writers.forEach(CacheWriter::cancel)
        jobs.forEach(Job::cancel)
    }

    suspend fun stats(): LinglanCacheStats = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val songCount = cache.keys
                .asSequence()
                .filter(LinglanCachePolicy::isLinglanCacheKey)
                .filter(::hasReusableEntry)
                .mapNotNull(LinglanCachePolicy::songIdFromKey)
                .distinct()
                .count()
            LinglanCacheStats(songCount = songCount, sizeBytes = cache.cacheSpace)
        }
    }

    suspend fun remove(songId: Long, bitrate: Int) = withContext(Dispatchers.IO) {
        cancelWriter(cacheKey(songId, bitrate))
        synchronized(lock) {
            removeEntry(cacheKey(songId, bitrate))
        }
    }

    suspend fun clear(): LinglanCacheStats = withContext(Dispatchers.IO) {
        cancelPendingWrites()
        synchronized(lock) {
            cache.keys
                .filter(LinglanCachePolicy::isLinglanCacheKey)
                .toList()
                .forEach(::removeEntry)
            LinglanCacheStats(songCount = 0, sizeBytes = cache.cacheSpace)
        }
    }

    private fun isFullyCached(cacheKey: String): Boolean {
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        return contentLength != C.LENGTH_UNSET.toLong() &&
            contentLength > 0 &&
            cache.isCached(cacheKey, 0, contentLength)
    }

    private fun hasReusableEntry(cacheKey: String): Boolean {
        val metadata = cache.getContentMetadata(cacheKey)
        val hasOriginUrl = metadata.get(ORIGIN_URL_METADATA, "")
            ?.isNotBlank() == true
        return hasOriginUrl || cache.getCachedSpans(cacheKey).isNotEmpty()
    }

    private fun removeEntry(cacheKey: String) {
        if (cacheKey !in cache.keys) return
        cache.applyContentMetadataMutations(
            cacheKey,
            ContentMetadataMutations()
                .remove(ORIGIN_URL_METADATA)
                .remove(ContentMetadata.KEY_CONTENT_LENGTH)
                .remove(ContentMetadata.KEY_REDIRECTED_URI)
        )
        cache.removeResource(cacheKey)
    }

    private fun cancelWriter(cacheKey: String) {
        val writer: CacheWriter?
        val job: Job?
        synchronized(lock) {
            writer = fillWriters.remove(cacheKey)
            job = fillJobs.remove(cacheKey)
        }
        writer?.cancel()
        job?.cancel()
    }
}
