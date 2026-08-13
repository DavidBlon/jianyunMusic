package com.ncm.app.plugin.registry

import com.ncm.app.plugin.manifest.ManifestItem
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.runtime.PluginRuntime
import com.ncm.app.plugin.runtime.PluginScriptCache
import com.ncm.app.plugin.security.ManifestSignatureVerifier
import com.ncm.app.plugin.security.VerifyDecision
import com.ncm.app.plugin.security.isRevoked
import kotlinx.coroutines.CancellationException

/** 插件注册表：下载 → 校验 → 候选缓存 → 两步装载 → 原子激活（GC #10/#11）。 */
class PluginRegistry(
    private val runtimeFactory: (pluginId: String, script: String, hostParams: Map<String, Any?>) -> PluginRuntime,
    private val downloader: suspend (String) -> ByteArray,
    private val verifier: ManifestSignatureVerifier,
    private val cache: PluginScriptCache,
    private val hostParams: Map<String, Any?> = emptyMap(),
    private val requireSignedManifest: Boolean = true
) {
    private val runtimes = mutableMapOf<String, PluginRuntime>()

    /**
     * Restores the last verified script without touching the network. This is used during
     * process start so a temporary manifest outage does not make an already selected source
     * disappear. The cached script still goes through the runtime contract probe.
     */
    @Synchronized
    fun restore(pluginId: String): Result<MusicProvider> {
        currentProvider(pluginId)?.let { return Result.success(it) }
        val cached = cache.loadActive(pluginId)
            ?: return Result.failure(IllegalStateException("没有可恢复的在线来源缓存"))
        val runtime = try {
            runtimeFactory(pluginId, cached.script, hostParams)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val provider = try {
            runtime.providerFor(pluginId)
        } catch (e: Exception) {
            runtime.destroy()
            return Result.failure(e)
        } ?: run {
            runtime.destroy()
            return Result.failure(IllegalStateException("缓存来源未提供音乐能力"))
        }
        runtimes.put(pluginId, runtime)?.destroy()
        return Result.success(provider)
    }

    suspend fun install(item: ManifestItem): Result<MusicProvider> {
        if (isRevoked(item.status)) return Result.failure(IllegalStateException("插件已被撤销"))
        val bytes = try {
            downloader(item.url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (bytes.size > MAX_SCRIPT_BYTES) return Result.failure(IllegalStateException("script too large"))
        val script = String(bytes, Charsets.UTF_8)

        // 生产签名硬门槛（GC #10）：缺签名或签名无效 → 拒绝。
        // 过渡模式（requireSignedManifest=false）：聆澜清单尚无签名（已探测确认），
        // 降级为「HTTPS 来源 + 大小上限 + 可选 SHA-256」；联调提供签名后恢复门禁。
        val signature = item.signature
        if (requireSignedManifest) {
            if (signature == null) return Result.failure(IllegalStateException("manifest missing signature"))
            val decision = verifier.verify(item, script, signature, item.signatureTimestamp ?: 0L)
            if (decision is VerifyDecision.Invalid) return Result.failure(IllegalStateException(decision.reason))
        } else {
            // 仅当清单提供 sha256 时校验脚本摘要（防传输篡改）；缺失则退化为 HTTPS 信任
            item.sha256?.let { expected ->
                if (!ManifestSignatureVerifier.sha256Hex(script).equals(expected, ignoreCase = true)) {
                    return Result.failure(IllegalStateException("script hash mismatch"))
                }
            }
        }

        // 候选缓存 → 新上下文两步装载（GC #11，P3T8 的 load 内部做两段检查）→ 原子激活
        cache.stageCandidate(item.id, item.version, script)
        val runtime = try {
            runtimeFactory(item.id, script, hostParams)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val provider = try {
            runtime.providerFor(item.id)
        } catch (e: CancellationException) {
            runtime.destroy()
            throw e
        } catch (e: Exception) {
            runtime.destroy()
            return Result.failure(e)
        } ?: run {
            runtime.destroy()
            return Result.failure(IllegalStateException("Plugin did not expose a provider"))
        }
        // Only replace the active runtime after the candidate has passed every probe.
        // Destroy the old context after the swap so repeated refreshes do not leak QuickJS.
        val previousRuntime = synchronized(this) { runtimes.put(item.id, runtime) }
        previousRuntime?.destroy()
        cache.activateCandidate(item.id, item.version)
        return Result.success(provider)
    }

    @Synchronized
    fun currentProvider(pluginId: String): MusicProvider? = runtimes[pluginId]?.providerFor(pluginId)

    @Synchronized
    fun availableProviders(): List<MusicProvider> {
        cache.activePluginIds()
            .filterNot(runtimes::containsKey)
            .forEach(::restore)
        return runtimes.mapNotNull { (pluginId, runtime) -> runtime.providerFor(pluginId) }
    }

    @Synchronized
    fun remove(pluginId: String) {
        runtimes.remove(pluginId)?.destroy()
        cache.deleteAll(pluginId)
    }

    @Synchronized
    fun destroy() {
        runtimes.values.forEach { it.destroy() }
        runtimes.clear()
    }

    private companion object {
        const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024
    }
}
