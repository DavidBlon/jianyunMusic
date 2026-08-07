package com.ncm.app.plugin.registry

import com.ncm.app.plugin.manifest.ManifestItem
import com.ncm.app.plugin.model.PluginCategory
import com.ncm.app.plugin.model.PluginReleaseStatus
import com.ncm.app.plugin.runtime.InMemoryPluginRuntime
import com.ncm.app.plugin.runtime.PluginScriptCache
import com.ncm.app.plugin.security.ManifestSignatureVerifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryTest {

    private fun item(
        id: String = "kw",
        url: String = "https://provider.example/kw/v1.js",
        status: PluginReleaseStatus = PluginReleaseStatus.ACTIVE,
        sha256: String? = "abc",
        signature: String? = null,
        signatureTimestamp: Long? = null
    ) = ManifestItem(id, "酷我", "1.0.0", url, PluginCategory.MUSIC, 1, null, status, sha256, signature, signatureTimestamp)

    private fun cache(): PluginScriptCache = PluginScriptCache(
        java.nio.file.Files.createTempDirectory("reg").toFile(),
        identityDigest = "u"
    )

    @Test
    fun installFailsWhenSignatureGateNotReady() = runTest {
        val registry = PluginRegistry(
            runtimeFactory = { _, _, _ -> throw UnsupportedOperationException("不应到达") },
            downloader = { "script-body".toByteArray() },
            verifier = ManifestSignatureVerifier(trustRootB64 = "", now = { 0L }),
            cache = cache()
        )
        val result = registry.install(item())
        assertTrue(result.isFailure)  // 无信任根 → 拒绝远程脚本
    }

    @Test
    fun revokedPluginCannotBeInstalled() = runTest {
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubB64 = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        val verifier = ManifestSignatureVerifier(trustRootB64 = pubB64, now = { 1_000_000L })
        val script = "module.exports = { platform: 'kw', version: '1.0.0' };"
        val hash = ManifestSignatureVerifier.sha256Hex(script)
        val payload = "linglan.kw\n1.0.0\n$hash".toByteArray(Charsets.UTF_8)
        val sig = java.util.Base64.getEncoder().encodeToString(
            java.security.Signature.getInstance("SHA256withRSA").run { initSign(kp.private); update(payload); sign() }
        )
        val registry = PluginRegistry(
            runtimeFactory = { _, _, _ -> throw UnsupportedOperationException("不应到达") },
            downloader = { script.toByteArray() },
            verifier = verifier,
            cache = cache()
        )
        val result = registry.install(item(status = PluginReleaseStatus.REVOKED, sha256 = hash, signature = sig, signatureTimestamp = 1_000_000L))
        assertTrue(result.isFailure)
    }

    @Test
    fun installExposesProviderWhenSignedAndValid() = runTest {
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubB64 = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        val verifier = ManifestSignatureVerifier(trustRootB64 = pubB64, now = { 1_000_000L })
        val script = "module.exports = { platform: 'kw', version: '1.0.0' };"
        val hash = ManifestSignatureVerifier.sha256Hex(script)
        val payload = "linglan.kw\n1.0.0\n$hash".toByteArray(Charsets.UTF_8)
        val sig = java.util.Base64.getEncoder().encodeToString(
            java.security.Signature.getInstance("SHA256withRSA").run { initSign(kp.private); update(payload); sign() }
        )
        val provider = FakeProvider("linglan.kw")
        val runtime = InMemoryPluginRuntime(mapOf("linglan.kw" to provider))
        val registry = PluginRegistry(
            runtimeFactory = { _, _, _ -> runtime },
            downloader = { script.toByteArray() },
            verifier = verifier,
            cache = cache()
        )
        val result = registry.install(item(id = "linglan.kw", sha256 = hash, signature = sig, signatureTimestamp = 1_000_000L))
        assertTrue(result.isSuccess)
        assertTrue(registry.currentProvider("linglan.kw") != null)
    }

    private class FakeProvider(private val id: String) : com.ncm.app.plugin.provider.MusicProvider {
        override val pluginId: String get() = id
        override suspend fun search(
            query: String,
            page: Int,
            type: String
        ): com.ncm.app.plugin.provider.SearchOutcome =
            com.ncm.app.plugin.provider.SearchOutcome(emptyList(), isEnd = true)
        override suspend fun resolveMedia(
            track: com.ncm.app.plugin.model.OnlineTrack,
            quality: String?
        ): com.ncm.app.plugin.model.ResolvedMedia = error("not implemented")
        override suspend fun lyric(
            track: com.ncm.app.plugin.model.OnlineTrack
        ): com.ncm.app.plugin.provider.LyricOutcome =
            com.ncm.app.plugin.provider.LyricOutcome(null, null, null, null)
    }

    @Test
    fun oversizeScriptIsRejected() = runTest {
        val registry = PluginRegistry(
            runtimeFactory = { _, _, _ -> throw UnsupportedOperationException("不应到达") },
            downloader = { ByteArray(3 * 1024 * 1024) },
            verifier = ManifestSignatureVerifier(trustRootB64 = "", now = { 0L }),
            cache = cache()
        )
        val result = registry.install(item())
        assertTrue(result.isFailure)
    }

    @Test
    fun transitionModeInstallsUnsignedScriptWhenShaMatches() = runTest {
        // 过渡模式（requireSignedManifest=false，2026-08 聆澜联调前）：无签名脚本仅校验可选 SHA-256
        val script = "module.exports = { platform: 'kw', version: '1.0.0' };"
        val hash = ManifestSignatureVerifier.sha256Hex(script)
        val provider = FakeProvider("linglan.kw")
        val runtime = InMemoryPluginRuntime(mapOf("linglan.kw" to provider))
        val registry = PluginRegistry(
            runtimeFactory = { _, _, _ -> runtime },
            downloader = { script.toByteArray() },
            verifier = ManifestSignatureVerifier(trustRootB64 = "", now = { 0L }),
            cache = cache(),
            requireSignedManifest = false
        )
        val result = registry.install(item(id = "linglan.kw", sha256 = hash))
        assertTrue(result.isSuccess)
        assertTrue(registry.currentProvider("linglan.kw") != null)
    }

    @Test
    fun transitionModeRejectsTamperedScriptWhenShaProvided() = runTest {
        val script = "module.exports = { platform: 'kw', version: '1.0.0' };"
        val hash = ManifestSignatureVerifier.sha256Hex(script)
        val registry = PluginRegistry(
            runtimeFactory = { _, _, _ -> throw UnsupportedOperationException("不应到达") },
            downloader = { (script + "\n// tampered").toByteArray() },
            verifier = ManifestSignatureVerifier(trustRootB64 = "", now = { 0L }),
            cache = cache(),
            requireSignedManifest = false
        )
        val result = registry.install(item(id = "linglan.kw", sha256 = hash))
        assertTrue(result.isFailure)
    }
}
