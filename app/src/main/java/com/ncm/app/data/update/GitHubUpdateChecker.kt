package com.ncm.app.data.update

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String
)

object GitHubUpdateChecker {
    private const val REPOSITORY = "wangbo432453/jianyunMusic"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://gitee.com/api/v5/repos/$REPOSITORY/tags?per_page=20")
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                val tags = JsonParser.parseString(body.string()).asJsonArray
                tags.asSequence()
                    .mapNotNull { item -> item.asJsonObject.get("name")?.asString }
                    .map { it to normalizeVersion(it) }
                    .filter { (_, version) -> isNewer(version, currentVersion) }
                    .maxWithOrNull { left, right -> compareVersions(left.second, right.second) }
                    ?.let { (tag, version) ->
                        UpdateInfo(
                            versionName = version,
                            downloadUrl = "https://gitee.com/$REPOSITORY/raw/$tag/release/JianYunMusic-v$version.apk"
                        )
                    }
            }
        }.getOrNull()
    }

    private fun normalizeVersion(value: String): String = value.removePrefix("v")

    private fun isNewer(candidate: String, current: String): Boolean =
        compareVersions(candidate, normalizeVersion(current)) > 0

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = versionParts(left)
        val rightParts = versionParts(right)
        return leftParts.indices.firstNotNullOfOrNull { index ->
            (leftParts[index] - rightParts[index]).takeIf { it != 0 }
        } ?: 0
    }

    private fun versionParts(version: String): List<Int> =
        version.substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
            .let { parts -> (parts + List((3 - parts.size).coerceAtLeast(0)) { 0 }).take(3) }
}
