package com.ncm.app.data.repository

internal object RepositoryPolicy {

    fun mergeCookiePairs(existing: String, incoming: String): String {
        val pairs = linkedMapOf<String, String>()

        fun append(cookie: String) {
            cookie.split(";")
                .map { it.trim() }
                .filter { it.contains("=") }
                .forEach { pair ->
                    val name = pair.substringBefore("=")
                    pairs[name] = pair
                }
        }

        append(existing)
        append(incoming)
        return pairs.values.joinToString("; ")
    }

    fun backupQualityFor(bitrate: Int): String {
        return if (bitrate <= 128000) "128k" else "320k"
    }

    fun playbackUnavailableMessage(
        hasPlayableUrl: Boolean,
        serverMessage: String?,
        hasFreeTrial: Boolean,
        code: Int,
        fee: Int,
        loggedIn: Boolean
    ): String? {
        if (hasPlayableUrl) return null

        return when {
            hasFreeTrial -> "这首歌当前只能试听，暂不支持播放完整版。"
            fee == 1 -> "这首歌需要网易云 VIP，当前账号无法播放完整版。"
            fee == 4 -> "这首歌需要单独购买后才能播放。"
            code == 404 -> "这首歌暂无可用版权或播放地址。"
            !serverMessage.isNullOrBlank() -> serverMessage
            !loggedIn -> "当前未登录，部分会员或版权歌曲无法播放。"
            else -> "这首歌当前不可播放，可能需要会员、购买或暂无版权。"
        }
    }
}
