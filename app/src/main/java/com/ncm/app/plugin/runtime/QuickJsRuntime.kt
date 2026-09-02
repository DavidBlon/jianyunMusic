package com.ncm.app.plugin.runtime

import android.util.Log
import com.ncm.app.plugin.provider.PluginException
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 插件模块导出的静态元数据（spec §6.1）。 */
data class PluginModuleMeta(
    val platform: String,
    val version: String,
    val supportedSearchType: List<String>
)

/** Decodes the primitive-string envelope used to cross the Android QuickJS JNI boundary. */
internal fun decodeSettledJsonEnvelope(json: String): Any? {
    val envelope = com.google.gson.JsonParser.parseString(json)
    require(envelope.isJsonObject && envelope.asJsonObject.has("value")) {
        "invalid async result envelope"
    }
    return envelope.asJsonObject.get("value").toKotlinJsonValue(depth = 0)
}

/** Some music APIs return HTTP 200 while a nested service request was rejected. */
internal fun nestedServiceRejectionCode(json: String): Int? = runCatching {
    val root = com.google.gson.JsonParser.parseString(json).asJsonObject
    val code = root.getAsJsonObject("req_1")?.get("code")?.asInt ?: return@runCatching null
    code.takeIf { it != 0 }
}.getOrNull()

internal fun qqLegacySearchFallbackRequest(
    url: String,
    body: String?,
    headers: Map<String, String>
): HttpRequestSpec? = runCatching {
    if (url.substringBefore('?') != QQ_MUSICU_SEARCH_URL || body.isNullOrBlank()) return@runCatching null
    val request = com.google.gson.JsonParser.parseString(body).asJsonObject
        .getAsJsonObject("req_1") ?: return@runCatching null
    if (request.get("method")?.asString != "DoSearchForQQMusicDesktop" ||
        request.get("module")?.asString != "music.search.SearchCgiService"
    ) return@runCatching null
    val params = request.getAsJsonObject("param") ?: return@runCatching null
    if (params.get("search_type")?.asInt != 0) return@runCatching null
    val query = params.get("query")?.asString?.trim().orEmpty()
    if (query.isBlank()) return@runCatching null
    val page = params.get("page_num")?.asInt?.coerceAtLeast(1) ?: 1
    val count = params.get("num_per_page")?.asInt?.coerceIn(1, MAX_RESULTS_PER_PAGE) ?: 20
    val encodedQuery = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
    val fallbackHeaders = headers
        .filterKeys { !it.equals("Cookie", ignoreCase = true) }
        .plus("Cookie" to "uin=0")
    HttpRequestSpec(
        url = "$QQ_LEGACY_SEARCH_URL?p=$page&n=$count&w=$encodedQuery&format=json",
        method = "GET",
        headers = fallbackHeaders
    )
}.getOrNull()

internal fun qqLegacyResponseAsMusicu(json: String): String? = runCatching {
    val legacy = com.google.gson.JsonParser.parseString(json).asJsonObject
    if (legacy.get("code")?.asInt != 0) return@runCatching null
    val song = legacy.getAsJsonObject("data")?.getAsJsonObject("song") ?: return@runCatching null
    val list = song.getAsJsonArray("list") ?: return@runCatching null
    val total = song.get("totalnum")?.asLong ?: list.size().toLong()

    val meta = com.google.gson.JsonObject().apply { addProperty("sum", total) }
    val songBody = com.google.gson.JsonObject().apply { add("list", list.deepCopy()) }
    val bodyObject = com.google.gson.JsonObject().apply { add("song", songBody) }
    val data = com.google.gson.JsonObject().apply {
        add("meta", meta)
        add("body", bodyObject)
    }
    val req = com.google.gson.JsonObject().apply {
        addProperty("code", 0)
        add("data", data)
    }
    com.google.gson.JsonObject().apply {
        addProperty("code", 0)
        add("req_1", req)
    }.toString()
}.getOrNull()

internal fun qqPlaylistDetailFallbackRequest(
    url: String,
    headers: Map<String, String>
): HttpRequestSpec? = runCatching {
    val uri = java.net.URI(url)
    if (uri.host !in setOf("i.y.qq.com", "c.y.qq.com") ||
        uri.path != "/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg"
    ) return@runCatching null
    val params = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
        val parts = pair.split('=', limit = 2)
        if (parts.size != 2) null else parts[0] to java.net.URLDecoder.decode(parts[1], Charsets.UTF_8.name())
    }.toMap()
    val playlistId = params["disstid"]?.toLongOrNull() ?: return@runCatching null
    val body = com.google.gson.JsonObject().apply {
        add("comm", com.google.gson.JsonObject().apply {
            addProperty("ct", 24)
            addProperty("cv", 0)
            addProperty("uin", 0)
            addProperty("format", "json")
        })
        add("req", com.google.gson.JsonObject().apply {
            addProperty("module", "music.srfDissInfo.aiDissInfo")
            addProperty("method", "uniform_get_Dissinfo")
            add("param", com.google.gson.JsonObject().apply {
                addProperty("disstid", playlistId)
                addProperty("enc_host_uin", "")
                addProperty("tag", 1)
                addProperty("userinfo", 1)
                addProperty("song_begin", 0)
                addProperty("song_num", 20)
            })
        })
    }.toString()
    HttpRequestSpec(
        url = QQ_MUSICU_SEARCH_URL,
        method = "POST",
        headers = headers
            .filterKeys { !it.equals("Cookie", ignoreCase = true) }
            .plus("Content-Type" to "application/json")
            .plus("Cookie" to "uin=0"),
        body = body.toByteArray(Charsets.UTF_8)
    )
}.getOrNull()

internal fun qqPlaylistDetailResponseAsLegacy(json: String): String? = runCatching {
    val root = com.google.gson.JsonParser.parseString(json).asJsonObject
    val req = root.getAsJsonObject("req") ?: return@runCatching null
    if (req.get("code")?.asInt != 0) return@runCatching null
    val data = req.getAsJsonObject("data") ?: return@runCatching null
    if (data.get("code")?.asInt != 0) return@runCatching null
    val songList = data.getAsJsonArray("songlist") ?: return@runCatching null
    val playlist = com.google.gson.JsonObject().apply { add("songlist", songList.deepCopy()) }
    com.google.gson.JsonObject().apply {
        addProperty("code", 0)
        add("cdlist", com.google.gson.JsonArray().apply { add(playlist) })
    }.toString()
}.getOrNull()

private const val QQ_MUSICU_SEARCH_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
private const val QQ_LEGACY_SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"

private fun com.google.gson.JsonElement.toKotlinJsonValue(depth: Int): Any? {
    require(depth <= 16) { "async result exceeds maximum depth" }
    return when {
        isJsonNull -> null
        isJsonArray -> asJsonArray.map { it.toKotlinJsonValue(depth + 1) }
        isJsonObject -> asJsonObject.entrySet().associate { (key, value) ->
            key to value.toKotlinJsonValue(depth + 1)
        }
        asJsonPrimitive.isBoolean -> asBoolean
        asJsonPrimitive.isString -> asString
        asJsonPrimitive.isNumber -> asString.toLongOrNull() ?: asString.toDouble()
        else -> null
    }
}

/**
 * QuickJS 运行时封装：每个插件一个独立上下文（GC #7）。
 * 本类是本计划里唯一的 QuickJS 触碰点；其余任务只依赖本类的 Kotlin 接口。
 *
 * 设计要点：
 * - 所有 QuickJS 调用在单一 worker 线程执行（QuickJS 上下文非线程安全），
 *   调用方通过 [callTimeoutMs] 限时等待；超时后结果被丢弃并抛出可重试错误。
 * - JS 侧只暴露受控 `__host`（HTTP 桥/日志/只读参数），require 只解析兼容模块表。
 * - async 插件的 Promise 通过 JS 侧 holder + 逐次 evaluate 推进微任务队列完成。
 * - JVM 单测环境无 QuickJS 原生库（Windows 无预编译 dll），引擎执行测试在
 *   真机/模拟器 QA（Robolectric 无法加载 ELF .so）。
 */
class QuickJsRuntime(
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    private val maxMemoryMb: Int = DEFAULT_MAX_MEMORY_MB,
    httpExecutor: HttpExecutor? = null
) {
    @Volatile private var httpExecutor: HttpExecutor? = httpExecutor
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "quickjs-plugin-worker").apply { isDaemon = true }
    }
    /** 仅 worker 线程访问。 */
    private val loaded = mutableMapOf<String, LoadedPlugin>()

    private class LoadedPlugin(val context: QuickJSContext, val exports: JSObject)
    private data class SettledPromiseJson(val envelope: String)

    fun useHttpExecutor(executor: HttpExecutor) { httpExecutor = executor }

    /**
     * 两步装载第一步（GC #11）：在禁用真实网络的上下文求值脚本并解析元数据。
     * [hostParams] 为只读运行参数（宿主版本、短期授权句柄），以冻结 JSON 对象注入。
     */
    fun loadModule(pluginId: String, script: String, hostParams: Map<String, Any?>): PluginModuleMeta = submit {
        val context = QuickJSContext.create()
        // wrapper-android takes this value in bytes, while this runtime is configured in MiB.
        // Applying a raw value such as 64 creates a 64-byte heap and makes every plugin fail.
        installHost(context, hostParams)
        context.setMemoryLimit(maxMemoryMb * BYTES_PER_MIB)
        // The script preamble supplies the redacting console through __host.log.
        // Do not call wrapper setConsole(): it installs a global `console` binding that
        // conflicts with that preamble and can overflow the wrapper's native error path.
        context.evaluate(PREAMBLE + "\n" + script)
        val module = context.getGlobalObject().getJSObject("module")
            ?: throw PluginException("INVALID_META", "插件未导出 module.exports", retryable = false)
        val exports = module.getJSObject("exports")
            ?: throw PluginException("INVALID_META", "插件未导出 module.exports", retryable = false)
        val meta = PluginModuleMeta(
            platform = exports.getString("platform").orEmpty(),
            version = exports.getString("version").orEmpty(),
            supportedSearchType = jsToKotlin(exports.getProperty("supportedSearchType"), depth = 0)
                .let { (it as? List<*>)?.mapNotNull { v -> v as? String }.orEmpty() }
        )
        if (meta.platform.isBlank()) {
            context.destroy()
            throw PluginException("INVALID_META", "插件缺少 platform", retryable = false)
        }
        if (meta.version.isBlank()) {
            context.destroy()
            throw PluginException("INVALID_META", "插件缺少 version", retryable = false)
        }
        loaded[pluginId] = LoadedPlugin(context, exports)
        meta
    }

    /** 调用已装载插件导出的方法；返回值由宿主桥接为 Kotlin 类型（Map/List/基本类型）。 */
    /** Checks the preamble export helper without requiring plugins to export it. */
    fun hasExport(pluginId: String, name: String): Boolean = submit {
        val plugin = loaded[pluginId]
            ?: throw PluginException("PLUGIN_NOT_LOADED", "plugin is not loaded", retryable = false)
        val helper = plugin.context.getGlobalObject().getJSFunctionProperty("hasExport")
            ?: throw PluginException("PLUGIN_ERROR", "export-check helper unavailable", retryable = false)
        helper.call(name) as? Boolean ?: false
    }

    fun invokeMethod(
        pluginId: String,
        name: String,
        args: Array<Any?>,
        timeoutMs: Long = callTimeoutMs
    ): Any? = submit(timeoutMs) {
        val plugin = loaded[pluginId]
            ?: throw PluginException("PLUGIN_NOT_LOADED", "插件未装载", retryable = false)
        val fn = plugin.exports.getJSFunctionProperty(name)
            ?: throw PluginException("NOT_SUPPORTED", "插件不支持能力: $name", retryable = false)
        val jsArgs = args.map { toJsValue(plugin.context, it) }.toTypedArray()
        val raw = fn.call(*jsArgs)
        val settled = settlePromise(plugin, raw)
        if (settled is SettledPromiseJson) {
            decodeSettledJsonEnvelope(settled.envelope)
        } else {
            jsToKotlin(settled, depth = 0)
        }
    }

    fun destroy() {
        try {
            worker.submit {
                loaded.values.forEach { it.context.destroy() }
                loaded.clear()
            }.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // 忽略：worker 可能已被关闭或任务被取消
        }
        worker.shutdownNow()
    }

    // ---------- 内部 ----------

    private fun <T> submit(timeoutMs: Long = callTimeoutMs, task: () -> T): T {
        if (worker.isShutdown) {
            throw PluginException("RUNTIME_DESTROYED", "插件运行时已销毁", retryable = false)
        }
        val future = worker.submit(Callable { task() })
        return try {
            if (timeoutMs <= 0L) {
                future.get()
            } else {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            }
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw PluginException("TIMEOUT", "插件调用超时", retryable = true)
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause
            if (cause is PluginException) throw cause
            throw PluginException("PLUGIN_ERROR", cause?.message ?: "插件执行错误", retryable = true)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PluginException("INTERRUPTED", "插件调用被中断", retryable = true)
        }
    }

    /** async 插件方法返回 Promise：JS 侧 holder + 逐次 evaluate 推进微任务（宿主轮询上限兜底）。 */
    private fun settlePromise(plugin: LoadedPlugin, raw: Any?): Any? {
        val promise = raw as? JSObject ?: return raw
        if (promise.getProperty("then") == null) return raw
        val context = plugin.context
        val global = context.getGlobalObject()
        context.evaluate("__awaitState = undefined; __awaitValueJson = undefined; __awaitError = undefined;")
        global.setProperty("__await", promise)
        var iterations = 0
        while (iterations < PROMISE_POLL_ITERATIONS) {
            val state = context.evaluate("__pollAwait()") as? String
            when (state) {
                "resolved" -> return SettledPromiseJson(
                    (global.getProperty("__awaitValueJson") as? String)
                        ?: throw PluginException(
                            "PLUGIN_ERROR",
                            "插件异步结果无法序列化",
                            retryable = true
                        )
                )
                "rejected" -> throw PluginException(
                    "PLUGIN_ERROR",
                    (global.getProperty("__awaitError") as? String) ?: "插件异步调用失败",
                    retryable = true
                )
            }
            iterations++
        }
        throw PluginException("TIMEOUT", "插件异步调用未在期限内完成", retryable = true)
    }

    private fun installHost(context: QuickJSContext, hostParams: Map<String, Any?>) {
        val global = context.getGlobalObject()
        val host = context.createNewJSObject()
        host.setProperty("request", JSCallFunction { args ->
            handleHttpRequest(args)
        })
        host.setProperty("log", JSCallFunction { args ->
            RedactingConsole.log(RedactingConsole.redact(args.joinToString(" ")))
            null
        })
        host.setProperty("md5", JSCallFunction { args ->
            val input = (args.getOrNull(0) as? String).orEmpty()
            java.security.MessageDigest.getInstance("MD5")
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        })
        host.setProperty("decodeBase64Utf8", JSCallFunction { args ->
            val input = (args.getOrNull(0) as? String).orEmpty()
            runCatching {
                String(java.util.Base64.getDecoder().decode(input), Charsets.UTF_8)
            }.getOrDefault("")
        })
        host.setProperty("aesCbcPkcs7Base64", JSCallFunction { args ->
            val value = (args.getOrNull(0) as? String).orEmpty()
            val key = (args.getOrNull(1) as? String).orEmpty()
            val iv = (args.getOrNull(2) as? String).orEmpty()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
            )
            java.util.Base64.getEncoder().encodeToString(
                cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            )
        })
        host.setProperty("modPowHex", JSCallFunction { args ->
            val value = (args.getOrNull(0) as? String).orEmpty()
            val exponent = (args.getOrNull(1) as? String).orEmpty()
            val modulus = (args.getOrNull(2) as? String).orEmpty()
            java.math.BigInteger(value, 16)
                .modPow(java.math.BigInteger(exponent, 16), java.math.BigInteger(modulus, 16))
                .toString(16)
        })
        global.setProperty("__host", host)
        // 只读运行参数：JSON 注入，JS 侧解析为冻结对象（spec §7.1）
        global.setProperty("__hostParamsJson", simpleJson(hostParams))
    }

    /** 受控 HTTP 桥的同步入口（JS 单线程内阻塞执行；超时由宿主策略与桥共同保证）。 */
    private fun handleHttpRequest(args: Array<Any?>): String {
        val executor = httpExecutor
        if (executor == null) {
            return """{"error":"http bridge not installed"}"""
        }
        val url = args.getOrNull(0) as? String
        val method = (args.getOrNull(1) as? String) ?: "GET"
        val headersJson = (args.getOrNull(2) as? String)
        val body = (args.getOrNull(3) as? String)
        if (url.isNullOrBlank()) return """{"error":"missing url"}"""
        return try {
            val headers = headersJson?.let { parseJsonMap(it) }.orEmpty()
            // The JS bridge uses an empty string as the no-body sentinel. OkHttp rejects
            // any request body for GET/HEAD, even a zero-byte one, so normalise it here
            // at the JVM boundary rather than passing JS null through the native wrapper.
            val requestBody = if (method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)) {
                null
            } else {
                body?.toByteArray(Charsets.UTF_8)
            }
            val playlistFallback = qqPlaylistDetailFallbackRequest(url, headers)
            var result = kotlinx.coroutines.runBlocking {
                executor(playlistFallback ?: HttpRequestSpec(url, method, headers, requestBody))
            }
            var data = String(result.data, Charsets.UTF_8)
            if (playlistFallback != null) {
                val legacy = qqPlaylistDetailResponseAsLegacy(data)
                    ?: return """{"error":"source playlist detail request failed"}"""
                // The legacy plugin deliberately parses this endpoint as JSONP text.
                // Keeping the callback wrapper prevents the host bridge from eagerly
                // embedding valid JSON as an object before the plugin calls replace().
                data = "callback($legacy)"
            }
            if (nestedServiceRejectionCode(data) == 2001) {
                qqLegacySearchFallbackRequest(url, body, headers)?.let { fallbackRequest ->
                    val fallbackResult = kotlinx.coroutines.runBlocking { executor(fallbackRequest) }
                    val converted = qqLegacyResponseAsMusicu(
                        String(fallbackResult.data, Charsets.UTF_8)
                    )
                    if (converted != null) {
                        result = fallbackResult
                        data = converted
                    }
                }
            }
            nestedServiceRejectionCode(data)?.let { code ->
                return """{"error":"source service rejected request (code $code)"}"""
            }
            val headerJson = result.headers.entries.joinToString(",") { (k, v) ->
                "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
            }
            """{"status":${result.status},"headers":{$headerJson},"data":${jsonValueOrString(data)}}"""
        } catch (e: Exception) {
            """{"error":"${escapeJson(e.message ?: "http error")}"}"""
        }
    }

    /** Java 值 → JS 值：基本类型直传；Map/List 递归构建 JSObject/JSArray。 */
    private fun toJsValue(context: QuickJSContext, value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Int, is Long, is Double -> value
        is Map<*, *> -> {
            val obj = context.createNewJSObject()
            value.entries.forEach { (k, v) ->
                if (k is String) context.setProperty(obj, k, toJsValue(context, v))
            }
            obj
        }
        is List<*> -> {
            val arr = context.createNewJSArray()
            value.forEachIndexed { index, v -> arr.set(toJsValue(context, v), index) }
            arr
        }
        else -> value.toString()
    }

    /** JS 值 → Kotlin 值：基本类型直传；JSArray → List；JSObject → Map（有深度上限）。 */
    private fun jsToKotlin(value: Any?, depth: Int): Any? {
        if (depth > MAX_JS_DEPTH) return null
        return when (value) {
            null, is String, is Boolean, is Int, is Long, is Double -> value
            is JSArray -> (0 until value.length()).map { jsToKotlin(value.get(it), depth + 1) }
            is JSObject -> {
                val names = (value.context.getOwnPropertyNames(value) as? List<*>) ?: return null
                names.mapNotNull { name ->
                    val key = name as? String ?: return@mapNotNull null
                    key to jsToKotlin(value.getProperty(key), depth + 1)
                }.toMap()
            }
            else -> value.toString()
        }
    }

    private fun parseJsonMap(json: String): Map<String, String> = try {
        val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
        obj.entrySet().associate { (k, v) ->
            k to if (v.isJsonPrimitive && v.asJsonPrimitive.isString) v.asString else v.toString()
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun jsonValueOrString(value: String): String = try {
        com.google.gson.JsonParser.parseString(value).toString()
    } catch (_: Exception) {
        "\"${escapeJson(value)}\""
    }

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t")

    private fun simpleJson(value: Map<String, Any?>): String = try {
        com.google.gson.Gson().toJson(value)
    } catch (_: Exception) {
        "{}"
    }

    private object RedactingConsole : QuickJSContext.Console {
        override fun log(message: String) = logRedacted(message)
        override fun info(message: String) = logRedacted(message)
        override fun warn(message: String) = logRedacted(message)
        override fun error(message: String) = logRedacted(message)

        private fun logRedacted(message: String) {
            Log.d("QuickJsPlugin", redact(message))
        }

        /** 日志脱敏（GC #15）：不记录密钥/令牌/播放地址。 */
        fun redact(message: String): String = message
            .replace(Regex("(?i)(key|token|secret|password|cookie|authorization)=[^&\\s\"']+"), "$1=***")
            .replace(Regex("(?i)(X-API-Key)[:\\s]+[^\\s\"']+"), "$1: ***")
    }

    private companion object {
        const val DEFAULT_CALL_TIMEOUT_MS = 30_000L
        const val DEFAULT_MAX_MEMORY_MB = 64
        const val BYTES_PER_MIB = 1024 * 1024
        const val MAX_JS_DEPTH = 8
        const val PROMISE_POLL_ITERATIONS = 2_000

        /** 宿主注入的 JS 前置：module/exports/require + 兼容模块 + 受控宿主对象。 */
        const val PREAMBLE = """
            'use strict';
            /* The bundled Android QuickJS is ES2016-era, while current MusicFree scripts
               are compiled against newer built-ins. Fill only missing standard methods. */
            if (!Object.entries) Object.entries = function (obj) {
              var out = [];
              Object.keys(Object(obj)).forEach(function (key) { out.push([key, obj[key]]); });
              return out;
            };
            if (!Object.values) Object.values = function (obj) {
              return Object.keys(Object(obj)).map(function (key) { return obj[key]; });
            };
            if (!Object.fromEntries) Object.fromEntries = function (entries) {
              var out = {};
              entries.forEach(function (entry) { out[entry[0]] = entry[1]; });
              return out;
            };
            if (!Array.prototype.includes) Array.prototype.includes = function (value, start) {
              return this.indexOf(value, start || 0) >= 0;
            };
            if (!String.prototype.includes) String.prototype.includes = function (value, start) {
              return this.indexOf(value, start || 0) >= 0;
            };
            if (!String.prototype.startsWith) String.prototype.startsWith = function (value, start) {
              return this.indexOf(value, start || 0) === (start || 0);
            };
            if (!String.prototype.endsWith) String.prototype.endsWith = function (value, end) {
              var limit = end === undefined ? this.length : end;
              return this.substring(limit - value.length, limit) === value;
            };
            var module = { exports: {} };
            var exports = module.exports;
            var __modules = {};
            function __defineCompat(name, impl) { __modules[name] = impl; }
            function require(name) {
              if (Object.prototype.hasOwnProperty.call(__modules, name)) return __modules[name];
              throw new Error('module not found: ' + name);
            }
            var __host = globalThis.__host;
            var __hostParams = (function () {
              try { return JSON.parse(globalThis.__hostParamsJson || '{}'); } catch (e) { return {}; }
            })();
            var console = {
              log: function (m) { __host.log(String(m)); },
              info: function (m) { __host.log(String(m)); },
              warn: function (m) { __host.log(String(m)); },
              error: function (m) { __host.log(String(m)); }
            };
            function setTimeout(fn) {
              /* No timer thread crosses the native boundary. Run zero-delay callbacks now;
                 overall async timeouts are still enforced by the Kotlin worker. */
              if (typeof fn === 'function') fn();
              return 0;
            }
            function clearTimeout() {}
            var process = { env: {} };

            /* ---- 受控 HTTP 兼容（spec §6.3：status/headers/data） ---- */
            function __httpRequest(url, method, headers, body) {
              // Keep the native bridge argument a string. The Kotlin boundary converts the
              // empty-string no-body sentinel to null for GET/HEAD before reaching OkHttp.
              var raw = __host.request(url, method || 'GET', JSON.stringify(headers || {}), body || '');
              var parsed = JSON.parse(raw);
              if (parsed.error) throw new Error('host http error: ' + parsed.error);
              return { status: parsed.status, headers: parsed.headers || {}, data: parsed.data };
            }
            function __appendQuery(url, params) {
              if (!params) return url;
              var query = typeof params === 'string' ? params : __queryString(params);
              if (!query) return url;
              return url + (url.indexOf('?') >= 0 ? '&' : '?') + query;
            }
            function __queryString(obj) {
              var parts = [];
              for (var k in (obj || {})) {
                if (!Object.prototype.hasOwnProperty.call(obj, k) || obj[k] === undefined || obj[k] === null) continue;
                var value = obj[k];
                if (Array.isArray(value)) {
                  value.forEach(function (entry) { parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(entry)); });
                } else {
                  parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(value));
                }
              }
              return parts.join('&');
            }
            function __axios(config) {
              if (typeof config === 'string') config = { url: config };
              config = config || {};
              var body = config.data;
              if (body !== null && body !== undefined && typeof body !== 'string') body = JSON.stringify(body);
              return Promise.resolve(__httpRequest(
                __appendQuery(config.url || '', config.params),
                String(config.method || 'GET').toUpperCase(),
                config.headers || {},
                body || ''
              ));
            }
            __axios.get = function (url, opts) {
              opts = opts || {};
              return __axios({ url: url, method: 'GET', headers: opts.headers || {}, params: opts.params });
            };
            __axios.post = function (url, body, opts) {
              opts = opts || {};
              return __axios({ url: url, method: 'POST', headers: opts.headers || {}, data: body });
            };
            __axios.put = function (url, body, opts) {
              opts = opts || {};
              return __axios({ url: url, method: 'PUT', headers: opts.headers || {}, data: body });
            };
            __axios.delete = function (url, opts) {
              opts = opts || {};
              return __axios({ url: url, method: 'DELETE', headers: opts.headers || {}, params: opts.params, data: opts.data });
            };
            __axios.request = __axios;
            __axios.default = __axios;
            __defineCompat('axios', __axios);
            __defineCompat('qs', {
              stringify: function (obj) {
                var parts = [];
                for (var k in obj) { if (Object.prototype.hasOwnProperty.call(obj, k)) parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(obj[k])); }
                return parts.join('&');
              },
              parse: function (str) {
                var out = {};
                if (!str) return out;
                String(str).split('&').forEach(function (pair) {
                  var kv = pair.split('=');
                  out[decodeURIComponent(kv[0])] = decodeURIComponent(kv[1] || '');
                });
                return out;
              }
            });

            /* ---- Small compatibility shims used by real MusicFree providers ---- */
            function __decodeHtml(value) {
              var named = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };
              return String(value == null ? '' : value).replace(/&(#x[0-9a-f]+|#[0-9]+|[a-z]+);/gi, function (_, entity) {
                var lower = String(entity).toLowerCase();
                if (lower.charAt(0) === '#') {
                  var radix = lower.charAt(1) === 'x' ? 16 : 10;
                  var digits = radix === 16 ? lower.slice(2) : lower.slice(1);
                  var code = parseInt(digits, radix);
                  return isNaN(code) ? _ : String.fromCodePoint(code);
                }
                return Object.prototype.hasOwnProperty.call(named, lower) ? named[lower] : _;
              });
            }
            var __he = { decode: __decodeHtml };
            __he.default = __he;
            __defineCompat('he', __he);

            var __cheerio = {
              load: function (html) {
                var text = __decodeHtml(String(html == null ? '' : html)
                  .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, '')
                  .replace(/<style[^>]*>[\s\S]*?<\/style>/gi, '')
                  .replace(/<[^>]+>/g, ''));
                var root = function () { return { text: function () { return text; } }; };
                root.text = function () { return text; };
                return root;
              }
            };
            __cheerio.default = __cheerio;
            __defineCompat('cheerio', __cheerio);

            var __cryptoUtf8 = {
              parse: function (value) { return { __utf8: String(value == null ? '' : value) }; },
              stringify: function (value) { return value && value.__utf8 || ''; }
            };
            var __cryptoBase64 = {
              parse: function (value) {
                var decoded = __host.decodeBase64Utf8(String(value || ''));
                return {
                  __utf8: decoded,
                  toString: function (encoder) {
                    return encoder && typeof encoder.stringify === 'function'
                      ? encoder.stringify(this)
                      : this.__utf8;
                  }
                };
              }
            };
            var __crypto = {
              MD5: function (value) {
                var digest = __host.md5(String(value == null ? '' : value));
                return { toString: function () { return digest; } };
              },
              AES: {
                encrypt: function (value, key, options) {
                  var encrypted = __host.aesCbcPkcs7Base64(
                    value && value.__utf8 || String(value == null ? '' : value),
                    key && key.__utf8 || String(key == null ? '' : key),
                    options && options.iv && options.iv.__utf8 || ''
                  );
                  return { toString: function () { return encrypted; } };
                }
              },
              mode: { CBC: {} },
              pad: { Pkcs7: {} },
              enc: { Base64: __cryptoBase64, Utf8: __cryptoUtf8 }
            };
            __crypto.default = __crypto;
            __defineCompat('crypto-js', __crypto);

            function __bigInteger(value, radix) {
              var wrapped = {
                __value: String(value == null ? '0' : value),
                __radix: radix || 10,
                modPow: function (exponent, modulus) {
                  return __bigInteger(__host.modPowHex(
                    this.__value,
                    exponent.__value,
                    modulus.__value
                  ), 16);
                },
                toString: function () { return this.__value; }
              };
              return wrapped;
            }
            __bigInteger.default = __bigInteger;
            __defineCompat('big-integer', __bigInteger);

            function __twoDigits(value) { return value < 10 ? '0' + value : String(value); }
            function __dayjs(value) {
              var date = value instanceof Date ? value : new Date(value);
              return {
                format: function (pattern) {
                  if (pattern === 'YYYY-MM-DD') {
                    return date.getFullYear() + '-' + __twoDigits(date.getMonth() + 1) + '-' + __twoDigits(date.getDate());
                  }
                  return date.toISOString();
                }
              };
            }
            __dayjs.unix = function (seconds) { return __dayjs(Number(seconds) * 1000); };
            __dayjs.default = __dayjs;
            __defineCompat('dayjs', __dayjs);

            /* ---- Promise 轮询等待（宿主每次 evaluate 推进微任务） ---- */
            var __awaitState;
            var __awaitValueJson;
            var __awaitError;
            function __pollAwait() {
              if (__awaitState === undefined && __await !== undefined) {
                __awaitState = 'pending';
                Promise.resolve(__await).then(
                  function (v) {
                    try {
                      __awaitValueJson = JSON.stringify({ value: v });
                      __awaitState = 'resolved';
                    } catch (e) {
                      __awaitState = 'rejected';
                      __awaitError = 'async result is not serializable';
                    }
                  },
                  function (e) { __awaitState = 'rejected'; __awaitError = String((e && e.message) || e); }
                );
              }
              return __awaitState === undefined ? 'none' : __awaitState;
            }

            /* ---- 导出方法存在性探针（过渡模式第二步装载检查） ---- */
            function hasExport(name) {
              return module.exports[name] !== undefined;
            }
        """
    }
}
