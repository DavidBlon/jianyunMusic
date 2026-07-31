package com.ncm.app.data.update

import com.google.gson.Gson
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val releaseNotes: String,
    val forceUpdate: Boolean
)

object AppUpdateChecker {
    private const val UPDATE_URL = "https://music.deltabound.top/update.json"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun check(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(UPDATE_URL)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("Cache-Control", "no-cache")
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = response.body?.string() ?: return@use null
                parse(json).takeIf {
                    isUpdateAvailable(it.versionCode, currentVersionCode)
                }
            }
        }.getOrNull()
    }

    fun parse(json: String): UpdateInfo {
        val config = gson.fromJson(json, ServerUpdateConfig::class.java)
            ?: throw IllegalArgumentException("Empty update configuration")
        return UpdateInfo(
            versionCode = config.versionCode
                ?: throw IllegalArgumentException("Missing versionCode"),
            versionName = config.versionName?.trim().orEmpty()
                .ifBlank { throw IllegalArgumentException("Missing versionName") },
            downloadUrl = config.downloadUrl?.trim().orEmpty(),
            sha256 = config.sha256?.trim().orEmpty(),
            releaseNotes = config.releaseNotes.orEmpty(),
            forceUpdate = config.forceUpdate ?: false
        )
    }

    fun isUpdateAvailable(serverVersionCode: Int, currentVersionCode: Int): Boolean =
        serverVersionCode > currentVersionCode

    fun isHttpsDownloadUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null
    }.getOrDefault(false)

    fun sha256Matches(input: InputStream, expectedSha256: String): Boolean {
        input.use { stream ->
            if (expectedSha256.isBlank()) return true
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            return actual.equals(expectedSha256.trim(), ignoreCase = true)
        }
    }

    private data class ServerUpdateConfig(
        val versionCode: Int?,
        val versionName: String?,
        val downloadUrl: String?,
        val sha256: String?,
        val releaseNotes: String?,
        val forceUpdate: Boolean?
    )
}
