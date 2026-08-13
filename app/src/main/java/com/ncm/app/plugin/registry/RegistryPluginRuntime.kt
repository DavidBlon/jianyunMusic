package com.ncm.app.plugin.registry

import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.runtime.PluginRuntime

/**
 * PluginRegistry 的 PluginRuntime 适配器（P6 接线）：搜索/解析服务只依赖
 * [PluginRuntime.providerFor]，装载入口仍在 [PluginRegistry.install]（设置页选择来源时调用）。
 */
class RegistryPluginRuntime(
    private val registry: PluginRegistry
) : PluginRuntime {

    override fun providerFor(pluginId: String): MusicProvider? = registry.currentProvider(pluginId)

    override fun availableProviders(): List<MusicProvider> = registry.availableProviders()

    override fun load(pluginId: String, script: String, hostParams: Map<String, Any?>): MusicProvider =
        throw UnsupportedOperationException("装载请走 PluginRegistry.install（含签名门禁与两步检查）")

    override fun destroy() {
        registry.destroy()
    }

    override fun isHealthy(): Boolean = true
}
