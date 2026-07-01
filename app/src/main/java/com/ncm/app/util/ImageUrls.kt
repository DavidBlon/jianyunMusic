package com.ncm.app.util

fun sizedImageUrl(url: String?, size: Int): String? {
    val clean = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (clean.startsWith("data:", ignoreCase = true)) return clean
    return "${clean.substringBefore("?")}?param=${size}y${size}"
}
