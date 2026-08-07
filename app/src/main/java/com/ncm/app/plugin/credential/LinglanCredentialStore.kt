package com.ncm.app.plugin.credential

/** 密钥存储边界：Android 用 Keystore 实现（P2T2），JVM 测试用内存实现。 */
interface SecretVault {
    fun write(value: String): Boolean
    fun read(): String?
    fun wipe()
}

/** 基于 Android Keystore 的加密存储；wipe 先撤销 Keystore 密钥再删文件（GC #8 加密擦除）。 */
class KeystoreSecretVault(
    private val appContext: android.content.Context,
    private val alias: String
) : SecretVault {
    private val keyStore: java.security.KeyStore by lazy {
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }
    private val file = java.io.File(appContext.filesDir, "vault_$alias.dat")

    private fun getOrCreateKey(): javax.crypto.SecretKey {
        val existing = keyStore.getKey(alias, null) as? javax.crypto.SecretKey
        if (existing != null) return existing
        val generator = javax.crypto.KeyGenerator.getInstance(
            "AES", "AndroidKeyStore"
        ).apply {
            init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    alias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }
        return generator.generateKey()
    }

    override fun write(value: String): Boolean {
        return try {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            file.writeBytes(iv + encrypted)
            true
        } catch (_: Exception) { false }
    }

    override fun read(): String? {
        return try {
            if (!file.exists()) return null
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return null
            val iv = bytes.copyOfRange(0, 12)
            val encrypted = bytes.copyOfRange(12, bytes.size)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, getOrCreateKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    override fun wipe() {
        try {
            keyStore.getKey(alias, null)?.let { keyStore.deleteEntry(alias) }
        } catch (_: Exception) { /* 忽略 */ }
        file.delete()
    }
}

/** 领域门面：设置页与 ViewModel 只依赖它，不直接碰加密细节。 */
class LinglanCredentialStore(private val vault: SecretVault) {
    fun save(secret: String): Boolean {
        val normalized = secret.trim()
        if (normalized.length < 8) return false
        return vault.write(normalized)
    }
    fun read(): String? = vault.read()
    fun masked(): String? = read()?.let { "••••${it.takeLast(4)}" }
    fun clear() { vault.wipe() }
    fun hasCredential(): Boolean = !read().isNullOrBlank()
}
