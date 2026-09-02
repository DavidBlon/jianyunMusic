package com.ncm.app.viewmodel

import com.ncm.app.plugin.auth.LinglanAuthState
import com.ncm.app.data.repository.MusicSourceKeyValidationResult
import com.ncm.app.plugin.manifest.ManifestItem
import com.ncm.app.plugin.model.PluginCategory
import com.ncm.app.plugin.model.PluginReleaseStatus
import com.ncm.app.plugin.runtime.InMemoryPluginRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMusicSourceViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun selectSourceUpdatesSelectedPluginIdAndKeepsOthersUnselected() = runTest(dispatcher) {
        val vm = OnlineMusicSourceViewModel(
            manifestProvider = { sampleManifest() },
            runtime = InMemoryPluginRuntime(emptyMap())
        )
        vm.refreshManifest()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, vm.uiState.value.manifestItems.size)
        vm.selectSource("linglan.kw")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("linglan.kw", vm.uiState.value.selectedPluginId)
        vm.selectSource("linglan.tx")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("linglan.tx", vm.uiState.value.selectedPluginId)
    }

    @Test
    fun connectFlowRejectsShortSecretAndAcceptsLongOne() = runTest(dispatcher) {
        val vm = OnlineMusicSourceViewModel(
            manifestProvider = { sampleManifest() },
            runtime = InMemoryPluginRuntime(emptyMap())
        )
        vm.connect("short")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LinglanAuthState.ERROR, vm.uiState.value.authState)

        vm.connect("valid-secret-1234")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LinglanAuthState.ACTIVE, vm.uiState.value.authState)
        assertEquals(2, vm.uiState.value.manifestItems.size) // 验证成功后自动拉取清单
    }

    @Test
    fun quickPasteValidationReportsInvalidThenAllowsRetryWithCorrectKey() = runTest(dispatcher) {
        val vm = OnlineMusicSourceViewModel(
            manifestProvider = { sampleManifest() },
            runtime = InMemoryPluginRuntime(emptyMap()),
            authClient = com.ncm.app.plugin.manifest.LinglanAuthClient(
                http = { _, secret ->
                    if (secret == "correct-key-1234") {
                        """{"code":200,"expireAt":9999999999999}"""
                    } else {
                        """{"code":401,"message":"密钥错误，请重新输入"}"""
                    }
                }
            )
        )

        val invalid = vm.validateAndConnect("wrong-key-1234")
        val valid = vm.validateAndConnect("correct-key-1234")

        assertEquals(true, invalid is MusicSourceKeyValidationResult.Invalid)
        assertEquals(MusicSourceKeyValidationResult.Valid, valid)
        assertEquals(LinglanAuthState.ACTIVE, vm.uiState.value.authState)
    }

    @Test
    fun cancellingValidationCannotLaterOverwriteDisconnectedState() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val vm = OnlineMusicSourceViewModel(
            manifestProvider = { sampleManifest() },
            runtime = InMemoryPluginRuntime(emptyMap()),
            authClient = com.ncm.app.plugin.manifest.LinglanAuthClient(
                http = { _, _ ->
                    started.complete(Unit)
                    awaitCancellation()
                }
            )
        )

        vm.connect("valid-secret-1234")
        dispatcher.scheduler.runCurrent()
        started.await()
        vm.cancelConnect()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(LinglanAuthState.DISCONNECTED, vm.uiState.value.authState)
    }

    @Test
    fun disconnectResetsStateAndDestroysRuntime() = runTest(dispatcher) {
        val runtime = InMemoryPluginRuntime(emptyMap())
        val vm = OnlineMusicSourceViewModel(
            manifestProvider = { sampleManifest() },
            runtime = runtime
        )
        vm.connect("valid-secret-1234")
        dispatcher.scheduler.advanceUntilIdle()
        vm.disconnect()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LinglanAuthState.DISCONNECTED, vm.uiState.value.authState)
        assertEquals(null, vm.uiState.value.selectedPluginId)
        assertEquals(false, runtime.isHealthy())
    }

    @Test
    fun selectSourceThroughRegistryFailsWithoutTrustRootAndRestoresPrevious() = runTest(dispatcher) {
        // 无信任根 → 签名门禁拒绝装载；失败必须恢复上一个当前来源并给出错误（GC #13）
        val registry = com.ncm.app.plugin.registry.PluginRegistry(
            runtimeFactory = { _, _, _ -> throw UnsupportedOperationException("不应到达") },
            downloader = { "script".toByteArray() },
            verifier = com.ncm.app.plugin.security.ManifestSignatureVerifier(trustRootB64 = "", now = { 0L }),
            cache = com.ncm.app.plugin.runtime.PluginScriptCache(
                java.nio.file.Files.createTempDirectory("vm-reg").toFile(),
                identityDigest = "u"
            )
        )
        val vm = OnlineMusicSourceViewModel(
            manifestProvider = { sampleManifest() },
            runtime = InMemoryPluginRuntime(emptyMap()),
            registry = registry,
            ioDispatcher = dispatcher
        )
        vm.refreshManifest()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectSource("linglan.kw")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(null, vm.uiState.value.selectedPluginId)
        assertEquals(true, vm.uiState.value.error?.contains("不可用") == true)
    }

    @Test
    fun selectSourceSucceedsThroughRegistryWhenSigned() = runTest(dispatcher) {
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubB64 = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        val verifier = com.ncm.app.plugin.security.ManifestSignatureVerifier(trustRootB64 = pubB64, now = { 1_000_000L })
        val script = "module.exports = { platform: 'kw', version: '1.0.0' };"
        val hash = com.ncm.app.plugin.security.ManifestSignatureVerifier.sha256Hex(script)
        val payload = "linglan.kw\n1.0.0\n$hash".toByteArray(Charsets.UTF_8)
        val sig = java.util.Base64.getEncoder().encodeToString(
            java.security.Signature.getInstance("SHA256withRSA").run { initSign(kp.private); update(payload); sign() }
        )
        val signedItem = com.ncm.app.plugin.manifest.ManifestItem(
            id = "linglan.kw", name = "酷我音乐", version = "1.0.0",
            url = "https://source.shiqianjiang.cn/script/mf/kw.js",
            category = com.ncm.app.plugin.model.PluginCategory.MUSIC, protocolVersion = 1,
            minHostVersion = null, status = com.ncm.app.plugin.model.PluginReleaseStatus.ACTIVE,
            sha256 = hash, signature = sig, signatureTimestamp = 1_000_000L
        )
        val runtime = InMemoryPluginRuntime(
            mapOf(
                "linglan.kw" to object : com.ncm.app.plugin.provider.MusicProvider {
                    override val pluginId: String get() = "linglan.kw"
                    override suspend fun search(
                        query: String,
                        page: Int,
                        type: String
                    ): com.ncm.app.plugin.provider.SearchOutcome =
                        com.ncm.app.plugin.provider.SearchOutcome(emptyList(), isEnd = true)
                    override suspend fun resolveMedia(
                        track: com.ncm.app.plugin.model.OnlineTrack,
                        quality: String?
                    ): com.ncm.app.plugin.model.ResolvedMedia = error("not used")
                    override suspend fun lyric(
                        track: com.ncm.app.plugin.model.OnlineTrack
                    ): com.ncm.app.plugin.provider.LyricOutcome =
                        com.ncm.app.plugin.provider.LyricOutcome(null, null, null, null)
                }
            )
        )
        val registry = com.ncm.app.plugin.registry.PluginRegistry(
            runtimeFactory = { _, _, _ -> runtime },
            downloader = { script.toByteArray() },
            verifier = verifier,
            cache = com.ncm.app.plugin.runtime.PluginScriptCache(
                java.nio.file.Files.createTempDirectory("vm-reg2").toFile(),
                identityDigest = "u"
            )
        )
        val vm = OnlineMusicSourceViewModel(
            manifestProvider = { listOf(signedItem) },
            runtime = runtime,
            registry = registry,
            ioDispatcher = dispatcher
        )
        vm.refreshManifest()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectSource("linglan.kw")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("linglan.kw", vm.uiState.value.selectedPluginId)
    }

    @Test
    fun switchingSourceKeepsPreviousProviderForFavoritesAndHistoryPlayback() = runTest(dispatcher) {
        val registry = com.ncm.app.plugin.registry.PluginRegistry(
            runtimeFactory = { pluginId, _, _ ->
                InMemoryPluginRuntime(mapOf(pluginId to SourceTestProvider(pluginId)))
            },
            downloader = { "cached-script".toByteArray() },
            verifier = com.ncm.app.plugin.security.ManifestSignatureVerifier(trustRootB64 = "", now = { 0L }),
            cache = com.ncm.app.plugin.runtime.PluginScriptCache(
                java.nio.file.Files.createTempDirectory("vm-retained-sources").toFile(),
                identityDigest = "u"
            ),
            requireSignedManifest = false
        )
        val vm = OnlineMusicSourceViewModel(
            manifestProvider = { sampleManifest() },
            runtime = com.ncm.app.plugin.registry.RegistryPluginRuntime(registry),
            registry = registry,
            ioDispatcher = dispatcher
        )

        vm.refreshManifest()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectSource("linglan.kw")
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectSource("linglan.tx")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("linglan.tx", vm.uiState.value.selectedPluginId)
        assertEquals(true, registry.currentProvider("linglan.kw") != null)
        assertEquals(true, registry.currentProvider("linglan.tx") != null)
    }
}

private class SourceTestProvider(
    override val pluginId: String
) : com.ncm.app.plugin.provider.MusicProvider {
    override suspend fun search(query: String, page: Int, type: String) =
        com.ncm.app.plugin.provider.SearchOutcome(emptyList(), isEnd = true)

    override suspend fun resolveMedia(
        track: com.ncm.app.plugin.model.OnlineTrack,
        quality: String?
    ): com.ncm.app.plugin.model.ResolvedMedia = error("not used")

    override suspend fun lyric(track: com.ncm.app.plugin.model.OnlineTrack) =
        com.ncm.app.plugin.provider.LyricOutcome(null, null, null, null)
}

private suspend fun sampleManifest(): List<ManifestItem> = listOf(
    ManifestItem(
        "linglan.kw", "Source KW", "1.0.0",
        "https://source.shiqianjiang.cn/script/mf/kw.js",
        PluginCategory.MUSIC, 1, null, PluginReleaseStatus.ACTIVE,
        com.ncm.app.plugin.security.ManifestSignatureVerifier.sha256Hex("cached-script")
    ),
    ManifestItem(
        "linglan.tx", "Source TX", "1.0.0",
        "https://source.shiqianjiang.cn/script/mf/tx.js",
        PluginCategory.MUSIC, 1, null, PluginReleaseStatus.ACTIVE,
        com.ncm.app.plugin.security.ManifestSignatureVerifier.sha256Hex("cached-script")
    )
)
