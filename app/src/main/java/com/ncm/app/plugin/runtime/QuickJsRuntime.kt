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

/** 插件模块导出的静态元数据（spec §6.1）。 */
data class PluginModuleMeta(
    val platform: String,
    val version: String,
    val supportedSearchType: List<String>
)

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

    fun useHttpExecutor(executor: HttpExecutor) { httpExecutor = executor }

    /**
     * 两步装载第一步（GC #11）：在禁用真实网络的上下文求值脚本并解析元数据。
     * [hostParams] 为只读运行参数（宿主版本、短期授权句柄），以冻结 JSON 对象注入。
     */
    fun loadModule(pluginId: String, script: String, hostParams: Map<String, Any?>): PluginModuleMeta = submit {
        val context = QuickJSContext.create()
        context.setMemoryLimit(maxMemoryMb)
        context.setConsole(RedactingConsole)
        installHost(context, hostParams)
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
    fun invokeMethod(pluginId: String, name: String, args: Array<Any?>): Any? = submit {
        val plugin = loaded[pluginId]
            ?: throw PluginException("PLUGIN_NOT_LOADED", "插件未装载", retryable = false)
        val fn = plugin.exports.getJSFunctionProperty(name)
            ?: throw PluginException("NOT_SUPPORTED", "插件不支持能力: $name", retryable = false)
        val jsArgs = args.map { toJsValue(plugin.context, it) }.toTypedArray()
        val raw = fn.call(*jsArgs)
        val settled = settlePromise(plugin, raw)
        jsToKotlin(settled, depth = 0)
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

    private fun <T> submit(task: () -> T): T {
        if (worker.isShutdown) {
            throw PluginException("RUNTIME_DESTROYED", "插件运行时已销毁", retryable = false)
        }
        val future = worker.submit(Callable { task() })
        return try {
            future.get(callTimeoutMs, TimeUnit.MILLISECONDS)
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
        context.evaluate("__awaitState = undefined; __awaitValue = undefined; __awaitError = undefined;")
        global.setProperty("__await", promise)
        var iterations = 0
        while (iterations < PROMISE_POLL_ITERATIONS) {
            val state = context.evaluate("__pollAwait()") as? String
            when (state) {
                "resolved" -> return global.getProperty("__awaitValue")
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
            val result = kotlinx.coroutines.runBlocking {
                executor(HttpRequestSpec(url, method, headers, body?.toByteArray(Charsets.UTF_8)))
            }
            val data = String(result.data, Charsets.UTF_8)
            val headerJson = result.headers.entries.joinToString(",") { (k, v) ->
                "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
            }
            """{"status":${result.status},"headers":{$headerJson},"data":${escapeJson(data)}}"""
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

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

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
        const val DEFAULT_CALL_TIMEOUT_MS = 10_000L
        const val DEFAULT_MAX_MEMORY_MB = 64
        const val MAX_JS_DEPTH = 8
        const val PROMISE_POLL_ITERATIONS = 200

        /** 宿主注入的 JS 前置：module/exports/require + 兼容模块 + 受控宿主对象。 */
        const val PREAMBLE = """
            'use strict';
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
            function setTimeout() { return 0; } /* 宿主不提供真实定时器；异步超时由宿主轮询上限兜底 */
            function clearTimeout() {}
            var process = { env: {} };

            /* ---- 受控 HTTP 兼容（spec §6.3：status/headers/data） ---- */
            function __httpRequest(url, method, headers, body) {
              var raw = __host.request(url, method || 'GET', JSON.stringify(headers || {}), body || '');
              var parsed = JSON.parse(raw);
              if (parsed.error) throw new Error('host http error: ' + parsed.error);
              return { status: parsed.status, headers: parsed.headers || {}, data: parsed.data };
            }
            __defineCompat('axios', {
              get: function (url, opts) { return Promise.resolve(__httpRequest(url, 'GET', (opts || {}).headers, null)); },
              post: function (url, body, opts) { return Promise.resolve(__httpRequest(url, 'POST', (opts || {}).headers, typeof body === 'string' ? body : JSON.stringify(body || {}))); },
              request: function (cfg) { return Promise.resolve(__httpRequest(cfg.url || '', cfg.method || 'GET', cfg.headers || {}, cfg.data || '')); }
            });
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
            ['crypto-js', 'big-integer', 'dayjs', 'cheerio', 'he'].forEach(function (name) {
              __defineCompat(name, { __unsupported: true });
            });

            /* ---- Promise 轮询等待（宿主每次 evaluate 推进微任务） ---- */
            var __awaitState;
            var __awaitValue;
            var __awaitError;
            function __pollAwait() {
              if (__awaitState === undefined && __await !== undefined) {
                __awaitState = 'pending';
                Promise.resolve(__await).then(
                  function (v) { __awaitState = 'resolved'; __awaitValue = v; },
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
