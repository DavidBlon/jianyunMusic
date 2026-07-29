package com.ncm.app.data.repository

import com.google.gson.JsonParser
import com.ncm.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed interface MusicSourceKeyValidationResult {
    data object Valid : MusicSourceKeyValidationResult
    data class Invalid(val message: String) : MusicSourceKeyValidationResult
    data class Unavailable(val message: String) : MusicSourceKeyValidationResult
}

/**
 * Validates a card key through the provider's script-check endpoint. This
 * verifies authorization without resolving a song or consuming a playback
 * request.
 */
class MusicSourceKeyValidator {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun validate(rawKey: String): MusicSourceKeyValidationResult {
        val key = rawKey.trim()
        if (key.length < MIN_KEY_LENGTH) {
            return MusicSourceKeyValidationResult.Invalid("卡密内容不完整，请重新复制后粘贴")
        }

        val apiRoot = BuildConfig.PAID_MUSIC_API_URL
            .trim()
            .trimEnd('/')
            .removeSuffix("/music")
        if (apiRoot.isBlank()) {
            return MusicSourceKeyValidationResult.Unavailable("音源服务地址未配置，请联系开发者")
        }

        val validationEndpoint = "$apiRoot/script".toHttpUrlOrNull()
            ?: return MusicSourceKeyValidationResult.Unavailable("音源服务地址不可用")
        val url = runCatching {
            validationEndpoint.newBuilder()
                .addQueryParameter("checkUpdate", CLIENT_CHECK_ID)
                .addQueryParameter("key", key)
                .build()
        }.getOrNull() ?: run {
            return MusicSourceKeyValidationResult.Unavailable("音源服务地址不可用")
        }
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", "JianYunMusic/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

        return try {
            execute(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            MusicSourceKeyValidationResult.Unavailable("暂时无法连接卡密验证服务，请检查网络后重试")
        }
    }

    private suspend fun execute(request: Request): MusicSourceKeyValidationResult =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) {
                            continuation.resume(
                                MusicSourceKeyValidationResult.Unavailable(
                                    "暂时无法连接卡密验证服务，请检查网络后重试"
                                )
                            )
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!continuation.isActive) return
                            val result = interpretResponse(
                                httpCode = response.code,
                                body = response.body?.string().orEmpty()
                            )
                            continuation.resume(result)
                        }
                    }
                }
            )
        }

    private fun interpretResponse(
        httpCode: Int,
        body: String
    ): MusicSourceKeyValidationResult {
        val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        val code = runCatching { json?.get("code")?.asInt }.getOrNull()
        val serverMessage = runCatching {
            json?.get("message")?.asString?.trim()
        }.getOrNull().orEmpty()

        return when (code) {
            200 -> MusicSourceKeyValidationResult.Valid
            401, 403 -> MusicSourceKeyValidationResult.Invalid(
                serverMessage.ifBlank { "卡密无效或已过期，请检查后重试" }
            )
            429 -> MusicSourceKeyValidationResult.Unavailable("验证请求过于频繁，请稍后再试")
            else -> when (httpCode) {
                401, 403 -> MusicSourceKeyValidationResult.Invalid(
                    serverMessage.ifBlank { "卡密无效或已过期，请检查后重试" }
                )
                429 -> MusicSourceKeyValidationResult.Unavailable("验证请求过于频繁，请稍后再试")
                else -> MusicSourceKeyValidationResult.Unavailable(
                    serverMessage.ifBlank { "卡密验证服务暂时不可用，请稍后重试" }
                )
            }
        }
    }

    private companion object {
        const val MIN_KEY_LENGTH = 8
        const val CLIENT_CHECK_ID = "jiany-music-android"
    }
}
