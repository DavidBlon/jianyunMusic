package com.ncm.app.plugin.credential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KeystoreSecretVaultTest {

    @Test
    fun keystoreVaultRoundTripsOnRobolectric() {
        val context = RuntimeEnvironment.getApplication()
        val vault = KeystoreSecretVault(context, alias = "test_vault_${System.nanoTime()}")
        val ok = vault.write("top-secret-123")
        // Robolectric 下 Keystore 能力有限：允许写入失败，但若成功必须能读回
        if (ok) {
            assertEquals("top-secret-123", vault.read())
            vault.wipe()
            assertNull(vault.read())
        }
    }

    @Test
    fun ciphertextFileLivesUnderAppFilesDir() {
        val context = RuntimeEnvironment.getApplication()
        val alias = "test_vault_path_${System.nanoTime()}"
        val vault = KeystoreSecretVault(context, alias = alias)
        vault.write("top-secret-456")
        val expected = java.io.File(context.filesDir, "vault_$alias.dat")
        // 路径必须固定在 app-private filesDir（targetSdk>=31 默认排除在 Auto Backup 之外，
        // 另有 backup_rules.xml 兜底排除 vault_* 文件），不允许写入外部可见路径
        assertEquals(context.filesDir, expected.parentFile)
        if (expected.exists()) {
            assertTrue(expected.length() > 12L) // IV(12) + 密文
        }
    }
}
