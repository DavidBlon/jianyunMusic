package com.ncm.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ncm.app.data.repository.MusicSourceKeyValidationResult
import com.ncm.app.data.store.MusicSourceSettings
import com.ncm.app.plugin.auth.LinglanAuthState
import com.ncm.app.plugin.credential.LinglanCredentialStore
import com.ncm.app.plugin.credential.isValidMusicSourceKey
import com.ncm.app.plugin.manifest.AuthValidationResult
import com.ncm.app.plugin.manifest.DEFAULT_SOURCE_ALLOW_RULES
import com.ncm.app.plugin.manifest.LinglanAuthClient
import com.ncm.app.plugin.manifest.LinglanManifestClient
import com.ncm.app.plugin.manifest.ManifestItem
import com.ncm.app.plugin.manifest.allowedManifestItems
import com.ncm.app.plugin.registry.PluginRegistry
import com.ncm.app.plugin.runtime.PluginRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OnlineSourceUiState(
    val authState: LinglanAuthState = LinglanAuthState.DISCONNECTED,
    val selectedPluginId: String? = null,
    val manifestItems: List<ManifestItem> = emptyList(),
    val isRefreshing: Boolean = false,
    val isDownloading: Boolean = false,
    val validUntilEpochMs: Long? = null,
    val maskedSecret: String? = null,
    val error: String? = null
)

/**
 * 设置页在线来源状态机。授权密钥只交给宿主网络层，插件脚本不可读取；
 * 已验证的脚本可从本地缓存恢复，清单短暂不可用时不会丢失当前来源。
 */
class OnlineMusicSourceViewModel(
    private val manifestProvider: suspend () -> List<ManifestItem>,
    private val runtime: PluginRuntime,
    private val authClient: LinglanAuthClient? = null,
    private val credentialStore: LinglanCredentialStore? = null,
    private val settings: MusicSourceSettings? = null,
    private val registry: PluginRegistry? = null,
    private val manifestClient: LinglanManifestClient? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val legacyCredentialProvider: (() -> String?)? = null,
    private val clearMigratedLegacyCredential: (() -> Unit)? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnlineSourceUiState())
    val uiState: StateFlow<OnlineSourceUiState> = _uiState
    private var connectJob: Job? = null
    private var manifestJob: Job? = null
    private var installJob: Job? = null

    init {
        val saved = settings?.read()
        val storedSecret = credentialStore?.read()
        if (!storedSecret.isNullOrBlank() && isValidMusicSourceKey(storedSecret)) {
            val restoredAuthState = LinglanAuthState.entries
                .firstOrNull { it.name == saved?.authState }
                ?.takeIf { it == LinglanAuthState.ACTIVE || it == LinglanAuthState.STALE_OFFLINE }
                ?: LinglanAuthState.ACTIVE
            _uiState.value = _uiState.value.copy(
                authState = restoredAuthState,
                maskedSecret = credentialStore.masked()
            )
            restoreCachedSource(saved?.selectedPluginId)
        } else {
            if (!storedSecret.isNullOrBlank()) {
                credentialStore?.clear()
                settings?.clear()
            }
            // 一次性迁移旧版本卡密；成功保存到 Keystore 后再清理旧值。
            val legacySecret = legacyCredentialProvider?.invoke()?.trim()
            if (!legacySecret.isNullOrBlank() && isValidMusicSourceKey(legacySecret)) {
                    connectInternal(legacySecret, clearMigratedLegacyCredential)
            } else if (!legacySecret.isNullOrBlank()) {
                clearMigratedLegacyCredential?.invoke()
            }
        }
    }

    fun connect(secret: String) = connectInternal(secret)

    private fun connectInternal(secret: String, onConnected: (() -> Unit)? = null) {
        if (_uiState.value.authState == LinglanAuthState.VALIDATING) return
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            validateAndConnectInternal(secret, onConnected)
        }
    }

    /** Validates pasted input, saves it securely, and returns a retry-friendly UI result. */
    suspend fun validateAndConnect(secret: String): MusicSourceKeyValidationResult =
        validateAndConnectInternal(secret)

    private suspend fun validateAndConnectInternal(
        secret: String,
        onConnected: (() -> Unit)? = null
    ): MusicSourceKeyValidationResult {
        _uiState.value = _uiState.value.copy(authState = LinglanAuthState.VALIDATING, error = null)
        val normalized = secret.trim()
        if (!isValidMusicSourceKey(normalized)) {
            val message = "\u5bc6\u94a5\u683c\u5f0f\u4e0d\u6b63\u786e\uff0c\u8bf7\u91cd\u65b0\u7c98\u8d34\u8d2d\u4e70\u540e\u83b7\u5f97\u7684\u5bc6\u94a5"
            _uiState.value = _uiState.value.copy(authState = LinglanAuthState.ERROR, error = message)
            return MusicSourceKeyValidationResult.Invalid(message)
        }

        val result = authClient?.validate(normalized)
            ?: AuthValidationResult(LinglanAuthState.ACTIVE, null, null)
        val connected = result.state == LinglanAuthState.ACTIVE ||
            result.state == LinglanAuthState.STALE_OFFLINE
        if (!connected) {
            val message = result.message ?: "\u5bc6\u94a5\u9519\u8bef\u6216\u5df2\u8fc7\u671f\uff0c\u8bf7\u91cd\u65b0\u8f93\u5165"
            _uiState.value = _uiState.value.copy(
                authState = result.state,
                validUntilEpochMs = result.validUntilEpochMs,
                maskedSecret = null,
                error = message
            )
            return if (result.state == LinglanAuthState.ERROR) {
                MusicSourceKeyValidationResult.Unavailable(message)
            } else {
                MusicSourceKeyValidationResult.Invalid(message)
            }
        }

        val stored = credentialStore?.save(normalized) ?: true
        if (!stored) {
            val message = "\u65e0\u6cd5\u5b89\u5168\u4fdd\u5b58\u5bc6\u94a5\uff0c\u8bf7\u68c0\u67e5\u8bbe\u5907\u5b89\u5168\u8bbe\u7f6e"
            _uiState.value = _uiState.value.copy(authState = LinglanAuthState.ERROR, error = message)
            return MusicSourceKeyValidationResult.Unavailable(message)
        }

        val previous = settings?.read()
        settings?.write(
            (previous ?: com.ncm.app.data.store.OnlineSourcePrefs()).copy(
                authState = result.state.name,
                lastVerifiedAtEpochMs = System.currentTimeMillis()
            )
        )
        onConnected?.invoke()
        _uiState.value = _uiState.value.copy(
            authState = result.state,
            validUntilEpochMs = result.validUntilEpochMs,
            maskedSecret = credentialStore?.masked() ?: "\u2022\u2022\u2022\u2022${normalized.takeLast(4)}",
            error = null
        )
        refreshManifest(previous?.selectedPluginId)
        return MusicSourceKeyValidationResult.Valid
    }

    fun cancelConnect() {
        if (_uiState.value.authState != LinglanAuthState.VALIDATING) return
        connectJob?.cancel()
        connectJob = null
        _uiState.value = _uiState.value.copy(authState = LinglanAuthState.DISCONNECTED, error = null)
    }

    fun refreshManifest() = refreshManifest(restorePluginId = null)

    private fun refreshManifest(restorePluginId: String?) {
        manifestJob?.cancel()
        manifestJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
            val items = loadManifest()
            val allowed = allowedManifestItems(items, DEFAULT_SOURCE_ALLOW_RULES)
            val selectedId = restorePluginId ?: _uiState.value.selectedPluginId
            val cachedSourceReady = selectedId
                ?.let { runtime.providerFor(it) != null }
                ?: false
            if (allowed.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    authState = if (cachedSourceReady) LinglanAuthState.STALE_OFFLINE else _uiState.value.authState,
                    selectedPluginId = selectedId.takeIf { cachedSourceReady },
                    manifestItems = emptyList(),
                    isRefreshing = false,
                    error = if (cachedSourceReady) {
                        "来源列表暂时不可用，正在使用设备中的已缓存来源"
                    } else {
                        "未获取到可用在线来源，请检查密钥、授权状态或网络后重试"
                    }
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                manifestItems = allowed,
                isRefreshing = false,
                error = null
            )

            restorePluginId?.let { pluginId ->
                if (allowed.none { it.id == pluginId }) {
                    _uiState.value = _uiState.value.copy(
                        selectedPluginId = pluginId.takeIf { cachedSourceReady },
                        error = if (cachedSourceReady) {
                            "当前缓存来源暂未出现在最新列表中，可继续使用或重新选择"
                        } else {
                            "已保存的在线来源已不可用，请重新选择"
                        }
                    )
                } else {
                    selectSource(pluginId)
                }
            }
        }
    }

    fun selectSource(pluginId: String) {
        if (_uiState.value.selectedPluginId == pluginId) return
        _uiState.value = _uiState.value.copy(isDownloading = true, error = null)
        val previous = _uiState.value.selectedPluginId
        installJob = viewModelScope.launch {
            val item = _uiState.value.manifestItems.firstOrNull { it.id == pluginId }
            if (item == null) {
                _uiState.value = _uiState.value.copy(
                    isDownloading = false,
                    error = "\u672a\u627e\u5230\u6240\u9009\u5728\u7ebf\u6765\u6e90\uff0c\u8bf7\u5237\u65b0\u540e\u91cd\u8bd5"
                )
                return@launch
            }

            val registry = registry
            if (registry == null) {
                // JVM UI 测试可不组装真实 PluginRegistry，只验证单选状态。
                _uiState.value = _uiState.value.copy(
                    selectedPluginId = pluginId,
                    isDownloading = false
                )
                settings?.let { store ->
                    store.write(store.read().copy(selectedPluginId = pluginId))
                }
                return@launch
            }

            val installResult = try {
                withContext(ioDispatcher) { registry.install(item) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            installResult.onSuccess {
                // Keep already loaded providers available: favorites and playback history are
                // source-owned, so an older track must still resolve after the active source changes.
                _uiState.value = _uiState.value.copy(
                    selectedPluginId = pluginId,
                    isDownloading = false,
                    error = null
                )
                settings?.let { store ->
                    store.write(store.read().copy(selectedPluginId = pluginId))
                }
            }.onFailure { error ->
                if (previous == null) {
                    settings?.let { store ->
                        store.write(store.read().copy(selectedPluginId = null))
                    }
                }
                _uiState.value = _uiState.value.copy(
                    selectedPluginId = previous,
                    isDownloading = false,
                    error = "\u5f53\u524d\u6765\u6e90\u4e0d\u53ef\u7528\uff1a${item.name}\uff08${error.message ?: "\u672a\u77e5\u9519\u8bef"}\uff09"
                )
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        manifestJob?.cancel()
        installJob?.cancel()
        val selectedPluginId = _uiState.value.selectedPluginId ?: settings?.read()?.selectedPluginId
        credentialStore?.clear()
        settings?.clear()
        if (registry != null) {
            selectedPluginId?.let(registry::remove)
            registry.destroy()
        } else {
            runtime.destroy()
        }
        _uiState.value = OnlineSourceUiState(authState = LinglanAuthState.DISCONNECTED)
    }

    private fun restoreCachedSource(pluginId: String?) {
        if (pluginId == null || registry == null) {
            refreshManifest(pluginId)
            return
        }
        manifestJob?.cancel()
        manifestJob = viewModelScope.launch {
            val restored = try {
                withContext(ioDispatcher) { registry.restore(pluginId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (restored.isSuccess) {
                _uiState.value = _uiState.value.copy(selectedPluginId = pluginId, error = null)
            }
            manifestJob = null
            refreshManifest(pluginId)
        }
    }

    private suspend fun loadManifest(): List<ManifestItem> {
        val secret = credentialStore?.read()
        val client = manifestClient
        return if (!secret.isNullOrBlank() && client != null) {
            try {
                client.fetch(secret)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            runCatchingManifest()
        }
    }

    private suspend fun runCatchingManifest(): List<ManifestItem> = try {
        manifestProvider()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
}
