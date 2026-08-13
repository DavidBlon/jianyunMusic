package com.ncm.app.plugin.model

import kotlin.math.roundToLong

private const val MAX_TRACK_DURATION_SECONDS = 24L * 60L * 60L
private const val MAX_TRACK_DURATION_MS = MAX_TRACK_DURATION_SECONDS * 1_000L

private val BYTE_SIZE_PATTERN = Regex(
    pattern = "^([0-9]+(?:\\.[0-9]+)?)\\s*(b|kb|kib|mb|mib|gb|gib)?$",
    option = RegexOption.IGNORE_CASE
)
private val BITRATE_PATTERN = Regex(
    pattern = "^([0-9]+(?:\\.[0-9]+)?)\\s*(?:k|kbps)?$",
    option = RegexOption.IGNORE_CASE
)

/**
 * Converts the duration variants used by MusicFree-compatible providers into milliseconds.
 * Providers commonly return seconds in `duration`/`interval`, while older and test plugins
 * may already return milliseconds. Explicit millisecond fields always take precedence.
 */
internal fun normalizedTrackDurationMs(raw: Map<*, *>): Long? {
    sequenceOf("durationMs", "duration_ms", "durationMillis", "dt")
        .mapNotNull { key -> finiteNumber(raw[key]) }
        .mapNotNull(::explicitMilliseconds)
        .firstOrNull()
        ?.let { return it }

    sequenceOf("duration", "interval")
        .mapNotNull { key -> flexibleDurationMs(raw[key]) }
        .firstOrNull()
        ?.let { return it }

    return durationFromQualities(raw["qualities"])
}

private fun flexibleDurationMs(value: Any?): Long? {
    if (value is String && ':' in value) return clockDurationMs(value)
    val number = finiteNumber(value) ?: return null
    if (number <= 0.0) return null

    // MusicFree providers use seconds for values in this range. Larger legacy values are
    // treated as milliseconds (for example 200000 = 3:20).
    val milliseconds = if (number <= MAX_TRACK_DURATION_SECONDS) number * 1_000.0 else number
    return milliseconds.takeIf { it <= MAX_TRACK_DURATION_MS }?.roundToLong()
}

private fun explicitMilliseconds(number: Double): Long? = number
    .takeIf { it > 0.0 && it <= MAX_TRACK_DURATION_MS }
    ?.roundToLong()

private fun clockDurationMs(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.size !in 2..3) return null
    val numbers = parts.map { it.toDoubleOrNull() ?: return null }
    if (numbers.any { !it.isFinite() || it < 0.0 } || numbers.drop(1).any { it >= 60.0 }) {
        return null
    }
    val seconds = when (numbers.size) {
        2 -> numbers[0] * 60.0 + numbers[1]
        else -> numbers[0] * 3_600.0 + numbers[1] * 60.0 + numbers[2]
    }
    return (seconds * 1_000.0)
        .takeIf { it > 0.0 && it <= MAX_TRACK_DURATION_MS }
        ?.roundToLong()
}

private fun durationFromQualities(value: Any?): Long? {
    val qualities = value as? Map<*, *> ?: return null
    return qualities.entries.mapNotNull { (qualityName, qualityValue) ->
        val quality = qualityValue as? Map<*, *> ?: return@mapNotNull null
        val sizeBytes = byteSize(quality["size"]) ?: return@mapNotNull null
        val bitrate = bitrate(quality["bitrate"])
            ?: bitrate(qualityName)
            ?: return@mapNotNull null
        if (sizeBytes <= 0.0 || bitrate <= 0.0) return@mapNotNull null
        val durationMs = sizeBytes * 8_000.0 / bitrate
        if (!durationMs.isFinite() || durationMs !in 1_000.0..MAX_TRACK_DURATION_MS.toDouble()) {
            return@mapNotNull null
        }
        bitrate to durationMs.roundToLong()
    }.minByOrNull { (bitrate, _) -> bitrate }?.second
}

private fun finiteNumber(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> value.trim().toDoubleOrNull()
    else -> null
}?.takeIf(Double::isFinite)

private fun byteSize(value: Any?): Double? {
    if (value is Number) return value.toDouble().takeIf(Double::isFinite)
    val match = BYTE_SIZE_PATTERN.matchEntire((value as? String)?.trim().orEmpty()) ?: return null
    val amount = match.groupValues[1].toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2].lowercase()) {
        "", "b" -> 1.0
        "kb", "kib" -> 1_024.0
        "mb", "mib" -> 1_024.0 * 1_024.0
        "gb", "gib" -> 1_024.0 * 1_024.0 * 1_024.0
        else -> return null
    }
    return amount * multiplier
}

private fun bitrate(value: Any?): Double? {
    if (value is Number) return value.toDouble().takeIf { it.isFinite() && it > 0.0 }
    val match = BITRATE_PATTERN.matchEntire((value as? String)?.trim().orEmpty()) ?: return null
    val amount = match.groupValues[1].toDoubleOrNull() ?: return null
    val hasKiloSuffix = match.value.any { it.equals('k', ignoreCase = true) }
    return if (hasKiloSuffix) amount * 1_000.0 else amount
}
