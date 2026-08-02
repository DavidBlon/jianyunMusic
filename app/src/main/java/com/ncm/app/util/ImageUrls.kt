package com.ncm.app.util

private const val ALBUM_ARTWORK_SOURCE_SIZE = 700
private const val ALBUM_ARTWORK_THUMBNAIL_SIZE = 160

fun sizedImageUrl(url: String?, size: Int): String? {
    val clean = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (clean.startsWith("data:", ignoreCase = true)) return clean
    return "${clean.substringBefore("?")}?param=${size}y${size}"
}

/** One shared source URL lets Coil reuse the encoded artwork across list and player decode sizes. */
fun albumArtworkUrl(url: String?): String? = sizedImageUrl(url, ALBUM_ARTWORK_SOURCE_SIZE)

fun albumArtworkThumbnailUrl(url: String?): String? =
    sizedImageUrl(url, ALBUM_ARTWORK_THUMBNAIL_SIZE)

/** Stable key used by the player to show the already decoded list thumbnail immediately. */
fun albumArtworkThumbnailCacheKey(url: String?): String? {
    val clean = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val identity = if (clean.startsWith("data:", ignoreCase = true)) {
        "data:${clean.hashCode()}"
    } else {
        clean.substringBefore("?")
    }
    return "album-artwork-thumbnail:$identity"
}
