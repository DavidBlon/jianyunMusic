package com.ncm.app.plugin.security

import com.ncm.app.plugin.manifest.ManifestItem
import com.ncm.app.plugin.model.PluginCategory
import com.ncm.app.plugin.model.PluginReleaseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestSignatureVerifierTest {

    @Test
    fun missingTrustRootNeverAllowsRemoteScript() {
        val verifier = ManifestSignatureVerifier(trustRootB64 = "", now = { 0L })
        val item = ManifestItem("kw", "酷我", "1.0.0", "https://x/kw.js", PluginCategory.MUSIC, 1, null, PluginReleaseStatus.ACTIVE, "sha256")
        val decision = verifier.verify(item, "script", "sig", 1L)
        assertTrue(decision is VerifyDecision.Invalid)
    }

    @Test
    fun validSignatureAndHashAreAccepted() {
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubB64 = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        val verifier = ManifestSignatureVerifier(trustRootB64 = pubB64, now = { 1_000_000L })
        val script = "module.exports = { platform: 'kw', version: '1.0.0' };"
        val hash = ManifestSignatureVerifier.sha256Hex(script)
        val payload = "linglan.kw\n1.0.0\n$hash".toByteArray(Charsets.UTF_8)
        val sig = java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(kp.private); update(payload); sign()
        }
        val item = ManifestItem("linglan.kw", "酷我", "1.0.0", "https://x/kw.js", PluginCategory.MUSIC, 1, null, PluginReleaseStatus.ACTIVE, hash)
        val decision = verifier.verify(item, script, java.util.Base64.getEncoder().encodeToString(sig), 1_000_000L)
        assertTrue(decision is VerifyDecision.Ok)
    }

    @Test
    fun tamperedScriptIsRejected() {
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubB64 = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        val verifier = ManifestSignatureVerifier(trustRootB64 = pubB64, now = { 1_000_000L })
        val script = "module.exports = { platform: 'kw', version: '1.0.0' };"
        val hash = ManifestSignatureVerifier.sha256Hex(script)
        val payload = "linglan.kw\n1.0.0\n$hash".toByteArray(Charsets.UTF_8)
        val sig = java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(kp.private); update(payload); sign()
        }
        val tampered = script + "\n// attacker"
        val item = ManifestItem("linglan.kw", "酷我", "1.0.0", "https://x/kw.js", PluginCategory.MUSIC, 1, null, PluginReleaseStatus.ACTIVE, hash)
        // 脚本正文被改 → SHA-256 不匹配清单 → 拒绝
        val decision = verifier.verify(item, tampered, java.util.Base64.getEncoder().encodeToString(sig), 1_000_000L)
        assertTrue(decision is VerifyDecision.Invalid)
    }

    @Test
    fun staleSignatureIsRejected() {
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubB64 = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        val verifier = ManifestSignatureVerifier(trustRootB64 = pubB64, now = { 2_000_000L })
        val script = "module.exports = { platform: 'kw', version: '1.0.0' };"
        val hash = ManifestSignatureVerifier.sha256Hex(script)
        val payload = "linglan.kw\n1.0.0\n$hash".toByteArray(Charsets.UTF_8)
        val sig = java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(kp.private); update(payload); sign()
        }
        val item = ManifestItem("linglan.kw", "酷我", "1.0.0", "https://x/kw.js", PluginCategory.MUSIC, 1, null, PluginReleaseStatus.ACTIVE, hash)
        // 签名时间 1_000_000，当前 2_000_000：超过 5 分钟新鲜期 → 拒绝
        val decision = verifier.verify(item, script, java.util.Base64.getEncoder().encodeToString(sig), 1_000_000L)
        assertTrue(decision is VerifyDecision.Invalid)
    }

    @Test
    fun revokedOrMandatoryUpdateCannotRun() {
        assertTrue(isRevoked(PluginReleaseStatus.REVOKED))
        assertTrue(isRevoked(PluginReleaseStatus.DISABLED))
        assertEquals(false, isRevoked(PluginReleaseStatus.ACTIVE))
        assertEquals(false, isRevoked(PluginReleaseStatus.MANDATORY_UPDATE))
    }
}
