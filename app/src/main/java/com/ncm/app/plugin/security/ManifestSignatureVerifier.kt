package com.ncm.app.plugin.security

import com.ncm.app.plugin.manifest.ManifestItem
import com.ncm.app.plugin.model.PluginReleaseStatus

/**
 * 生产签名验证（GC #10）：认证清单把插件 ID/版本/SHA-256 绑定，验证应用信任的
 * 聆澜签名密钥生成的签名。信任根未配置时一律拒绝——不能发布远程脚本执行能力。
 * 负载布局为 "id\nversion\nsha256hex"（UTF-8），RSA-SHA256 签名。
 * 注意：真实信任根与签名负载布局以聆澜公布为准（§17）；算法与密钥格式在此实现，
 * 上线前只需替换 trustRootB64 与（若公布不同）负载拼接方式。
 */
sealed interface VerifyDecision {
    data object Ok : VerifyDecision
    data class Invalid(val reason: String) : VerifyDecision
}

class ManifestSignatureVerifier(
    private val trustRootB64: String,
    private val now: () -> Long
) {
    fun verify(
        item: ManifestItem,
        script: String,
        signatureBase64: String,
        signatureTimestamp: Long
    ): VerifyDecision {
        if (trustRootB64.isBlank()) return VerifyDecision.Invalid("missing trust root")
        if (item.sha256.isNullOrBlank()) return VerifyDecision.Invalid("missing sha256")
        val ageMs = now() - signatureTimestamp
        if (ageMs < 0 || ageMs > MAX_SIGNATURE_AGE_MS) return VerifyDecision.Invalid("signature too old")
        return try {
            val scriptHash = sha256Hex(script)
            if (!scriptHash.equals(item.sha256, ignoreCase = true)) return VerifyDecision.Invalid("script hash mismatch")
            val payload = "${item.id}\n${item.version}\n$scriptHash".toByteArray(Charsets.UTF_8)
            val signature = java.util.Base64.getDecoder().decode(signatureBase64)
            val signatureJce = java.security.Signature.getInstance("SHA256withRSA")
            signatureJce.initVerify(readPublicKey(trustRootB64))
            signatureJce.update(payload)
            if (signatureJce.verify(signature)) VerifyDecision.Ok else VerifyDecision.Invalid("bad signature")
        } catch (e: Exception) {
            VerifyDecision.Invalid("verification failed: ${e.message}")
        }
    }

    private fun readPublicKey(b64: String): java.security.PublicKey {
        val der = java.util.Base64.getDecoder().decode(b64)
        return java.security.KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(der))
    }

    companion object {
        private const val MAX_SIGNATURE_AGE_MS = 300_000L

        fun sha256Hex(input: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

fun isRevoked(status: PluginReleaseStatus): Boolean =
    status == PluginReleaseStatus.REVOKED || status == PluginReleaseStatus.DISABLED
