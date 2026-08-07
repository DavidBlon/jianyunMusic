package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.provider.MusicProvider

/** 插件运行环境。阶段 1 用内存实现占位，阶段 3 替换为 QuickJS 隔离实现。 */
interface PluginRuntime {
    fun providerFor(pluginId: String): MusicProvider?

    /** 装载插件脚本并返回其 MusicProvider（GC #11 两步检查由实现负责）。
     *  占位实现不支持；P3T8 的 QuickJsPluginRuntime 提供真实实现。 */
    fun load(pluginId: String, script: String, hostParams: Map<String, Any?>): MusicProvider =
        throw UnsupportedOperationException("占位运行时不支持装载脚本")

    fun destroy()
    fun isHealthy(): Boolean
}

/** 阶段 1 占位：仅用于单元测试与 UI 开发，不提供任何隔离。 */
class InMemoryPluginRuntime(
    private val providers: Map<String, MusicProvider>
) : PluginRuntime {
    @Volatile private var destroyed = false

    override fun providerFor(pluginId: String): MusicProvider? {
        if (destroyed) return null
        return providers[pluginId]
    }

    override fun destroy() { destroyed = true }

    override fun isHealthy(): Boolean = !destroyed
}
