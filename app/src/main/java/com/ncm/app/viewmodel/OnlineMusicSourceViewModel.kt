package com.ncm.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ncm.app.data.store.MusicSourceSettings
import com.ncm.app.data.store.OnlineSourcePrefs
import com.ncm.app.plugin.auth.LinglanAuthState
import com.ncm.app.plugin.credential.LinglanCredentialStore
import com.ncm.app.plugin.manifest.AuthValidationResult
import com.ncm.app.plugin.manifest.DEFAULT_SOURCE_ALLOW_RULES
import com.ncm.app.plugin.manifest.LinglanAuthClient
import com.ncm.app.plugin.manifest.ManifestItem
import com.ncm.app.plugin.manifest.allowedManifestItems
import com.ncm.app.plugin.model.PluginCategory
import com.ncm.app.plugin.model.PluginReleaseStatus
import com.ncm.app.plugin.runtime.PluginRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class OnlineSourceUiState(
    val authState: LinglanAuthState = LinglanAuthState.DISCONNECTED,
    val selectedPluginId: String? = null,
    val manifestItems: List<ManifestItem> = emptyList(),
    val isDownloading: Boolean = false,
    val validUntilEpochMs: Long? = null,   // spec §10 已连接显示到期时间
    val maskedSecret: String? = null,      // spec §8.2 只显示掩码，不提供复制入口
    val error: String? = null
)

/**
 * 设置页「在线音乐来源」状态机（spec §10）。
 * selectSource 走真实装载管线：下载 → 签名门禁 → QuickJS 两步装载 → 原子激活（阶段 3/6）。
 * 联调前置（§17）未满足时得到明确错误，绝不静默切换来源。
 */
class OnlineMusicSourceViewModel(
    private val manifestProvider: suspend () -> List<ManifestItem>,
    private val runtime: PluginRuntime,
    private val authClient: LinglanAuthClient? = null,
    private val credentialStore: LinglanCredentialStore? = null,
    private val settings: MusicSourceSettings? = null,
    private val registry: com.ncm.app.plugin.registry.PluginRegistry? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnlineSourceUiState())
    val uiState: StateFlow<OnlineSourceUiState> = _uiState

    init {
        // 启动恢复：重启后恢复选中来源与授权状态（P2T8）
        settings?.read()?.let { saved ->
            if (saved.selectedPluginId != null) {
                _uiState.value = _uiState.value.copy(
                    authState = LinglanAuthState.entries.firstOrNull { it.name == saved.authState }
                        ?: LinglanAuthState.DISCONNECTED,
                    selectedPluginId = saved.selectedPluginId,
                    maskedSecret = credentialStore?.masked()
                )
            }
        }
    }

    fun connect(secret: String) {
        if (_uiState.value.authState == LinglanAuthState.VALIDATING) return  // 阻止重复提交（GC #13）
        _uiState.value = _uiState.value.copy(authState = LinglanAuthState.VALIDATING, error = null)
        viewModelScope.launch {
            val normalized = secret.trim()
            if (normalized.length < 8) {
                _uiState.value = _uiState.value.copy(
                    authState = LinglanAuthState.ERROR,
                    error = "密钥无效或已过期"
                )
                return@launch
            }
            val result = authClient?.validate(normalized)
                ?: AuthValidationResult(LinglanAuthState.ACTIVE, null, null) // 阶段 2 占位验证，联调后移除
            val connected = result.state == LinglanAuthState.ACTIVE ||
                result.state == LinglanAuthState.STALE_OFFLINE
            if (connected) {
                credentialStore?.save(normalized)
                settings?.write(
                    OnlineSourcePrefs(
                        authState = result.state.name,
                        lastVerifiedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }
            _uiState.value = _uiState.value.copy(
                authState = result.state,
                validUntilEpochMs = result.validUntilEpochMs,
                maskedSecret = if (connected) credentialStore?.masked() else null,
                error = result.message
            )
            if (connected) refreshManifest()
        }
    }

    fun cancelConnect() {  // 验证中允许取消（spec §10）
        if (_uiState.value.authState != LinglanAuthState.VALIDATING) return
        _uiState.value = _uiState.value.copy(authState = LinglanAuthState.DISCONNECTED, error = null)
    }

    fun refreshManifest() {
        viewModelScope.launch {
            val items = runCatchingManifest()
            _uiState.value = _uiState.value.copy(
                manifestItems = allowedManifestItems(items, DEFAULT_SOURCE_ALLOW_RULES),
                error = null
            )
        }
    }

    fun selectSource(pluginId: String) {
        if (_uiState.value.selectedPluginId == pluginId) return
        _uiState.value = _uiState.value.copy(isDownloading = true, error = null)
        val previous = _uiState.value.selectedPluginId
        viewModelScope.launch {
            val registry = registry
            val item = _uiState.value.manifestItems.firstOrNull { it.id == pluginId }
            if (registry == null || item == null) {
                // 阶段 2 占位路径（无注册表时仅记录选择）；联调后移除
                _uiState.value = _uiState.value.copy(
                    selectedPluginId = pluginId,
                    isDownloading = false
                )
                settings?.let { store ->
                    store.write(store.read().copy(selectedPluginId = pluginId))
                }
                return@launch
            }
            // 真实装载管线（GC #10/#11）：下载 → 签名门禁 → QuickJS 两步装载 → 原子激活
            registry.install(item).onSuccess { provider ->
                _uiState.value = _uiState.value.copy(
                    selectedPluginId = pluginId,
                    isDownloading = false,
                    error = null
                )
                settings?.let { store ->
                    store.write(store.read().copy(selectedPluginId = pluginId))
                }
            }.onFailure { e ->
                // 失败恢复上一个当前来源（GC #13），明确提示不静默切换
                _uiState.value = _uiState.value.copy(
                    selectedPluginId = previous,
                    isDownloading = false,
                    error = "来源「${item.name}」不可用：${e.message}"
                )
            }
        }
    }

    fun disconnect() {
        runtime.destroy()
        credentialStore?.clear()
        settings?.clear()
        _uiState.value = OnlineSourceUiState(authState = LinglanAuthState.DISCONNECTED)
    }

    private suspend fun runCatchingManifest(): List<ManifestItem> = try {
        manifestProvider()
    } catch (_: Exception) {
        emptyList()
    }
}

/** 阶段 2 假清单（无真实脚本/密钥）；阶段 3 用 LinglanManifestClient 替换。 */
suspend fun sampleManifest(): List<ManifestItem> = listOf(
    ManifestItem("linglan.kw", "酷我音乐", "1.0.0", "https://provider.example/kw/v1.js", PluginCategory.MUSIC, 1, null, PluginReleaseStatus.ACTIVE, null),
    ManifestItem("linglan.tx", "QQ音乐", "1.0.0", "https://provider.example/tx/v1.js", PluginCategory.MUSIC, 1, null, PluginReleaseStatus.ACTIVE, null),
    ManifestItem("linglan.wy", "网易云音乐", "1.0.0", "https://provider.example/wy/v1.js", PluginCategory.MUSIC, 1, null, PluginReleaseStatus.ACTIVE, null)
)
