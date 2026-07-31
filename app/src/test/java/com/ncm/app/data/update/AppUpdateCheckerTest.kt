package com.ncm.app.data.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun `parses website update json`() {
        val json = """
            {
              "versionCode": 9,
              "versionName": "1.4.0",
              "downloadUrl": "https://music.deltabound.top/downloads/JianYunMusic-v1.4.0.apk",
              "sha256": "ABCDEF",
              "releaseNotes": "新增功能",
              "forceUpdate": true
            }
        """.trimIndent()

        val update = AppUpdateChecker.parse(json)

        assertEquals(9, update.versionCode)
        assertEquals("1.4.0", update.versionName)
        assertEquals("https://music.deltabound.top/downloads/JianYunMusic-v1.4.0.apk", update.downloadUrl)
        assertEquals("ABCDEF", update.sha256)
        assertEquals("新增功能", update.releaseNotes)
        assertTrue(update.forceUpdate)
    }

    @Test
    fun `only a greater server version code is an update`() {
        assertTrue(AppUpdateChecker.isUpdateAvailable(serverVersionCode = 9, currentVersionCode = 8))
        assertFalse(AppUpdateChecker.isUpdateAvailable(serverVersionCode = 8, currentVersionCode = 8))
        assertFalse(AppUpdateChecker.isUpdateAvailable(serverVersionCode = 7, currentVersionCode = 8))
    }

    @Test
    fun `only valid https download urls are accepted`() {
        assertTrue(AppUpdateChecker.isHttpsDownloadUrl("https://music.deltabound.top/app.apk"))
        assertFalse(AppUpdateChecker.isHttpsDownloadUrl("http://music.deltabound.top/app.apk"))
        assertFalse(AppUpdateChecker.isHttpsDownloadUrl(""))
        assertFalse(AppUpdateChecker.isHttpsDownloadUrl("not a url"))
    }

    @Test
    fun `sha256 verification accepts matching hash case insensitively and empty checksum`() {
        val apk = File.createTempFile("update-", ".apk")
        try {
            apk.writeText("hello", Charsets.UTF_8)
            assertTrue(
                AppUpdateChecker.sha256Matches(
                    apk.inputStream(),
                    "2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824"
                )
            )
            assertTrue(AppUpdateChecker.sha256Matches(apk.inputStream(), ""))
            assertFalse(AppUpdateChecker.sha256Matches(apk.inputStream(), "00"))
        } finally {
            apk.delete()
        }
    }
}
