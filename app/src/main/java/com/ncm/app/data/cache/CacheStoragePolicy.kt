package com.ncm.app.data.cache

import android.content.Context
import java.io.File

internal const val LINGLAN_AUDIO_CACHE_MAX_BYTES = 1024L * 1024L * 1024L
internal const val COVER_IMAGE_CACHE_MAX_BYTES = 1024L * 1024L * 1024L
internal const val LINGLAN_AUDIO_CACHE_MAX_MIB =
    LINGLAN_AUDIO_CACHE_MAX_BYTES / (1024L * 1024L)
internal const val LINGLAN_AUDIO_CACHE_DATABASE_NAME = "exoplayer_internal.db"

private const val LINGLAN_AUDIO_CACHE_DIRECTORY = "linglan_audio_cache"
private const val LINGLAN_AUDIO_CACHE_INDEX_DIRECTORY = "linglan_audio_cache_index"
private const val COVER_IMAGE_CACHE_DIRECTORY = "image_cache"

internal fun linglanAudioCacheDirectory(context: Context): File {
    return File(context.cacheDir, LINGLAN_AUDIO_CACHE_DIRECTORY)
}

internal fun legacyLinglanAudioCacheDirectory(context: Context): File {
    return File(context.noBackupFilesDir, LINGLAN_AUDIO_CACHE_DIRECTORY)
}

internal fun linglanAudioCacheDatabaseFile(context: Context): File {
    return File(
        File(context.cacheDir, LINGLAN_AUDIO_CACHE_INDEX_DIRECTORY),
        LINGLAN_AUDIO_CACHE_DATABASE_NAME
    )
}

internal fun coverImageCacheDirectory(context: Context): File {
    return File(context.cacheDir, COVER_IMAGE_CACHE_DIRECTORY)
}
