package com.ncm.app.plugin.credential

private const val MIN_MUSIC_SOURCE_KEY_LENGTH = 8
private const val MAX_MUSIC_SOURCE_KEY_LENGTH = 256

/** A key must be safe both as a URL value and as an HTTP header value. */
internal fun isValidMusicSourceKey(raw: String): Boolean {
    val value = raw.trim()
    return value.length in MIN_MUSIC_SOURCE_KEY_LENGTH..MAX_MUSIC_SOURCE_KEY_LENGTH &&
        value.all { character -> character.code in 0x21..0x7e }
}
