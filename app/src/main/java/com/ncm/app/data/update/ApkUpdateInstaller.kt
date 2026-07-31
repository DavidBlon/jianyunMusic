package com.ncm.app.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApkUpdateInstaller(private val context: Context) {
    private val preferences = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val downloadManager = context.getSystemService(DownloadManager::class.java)

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun download(update: UpdateInfo): Long {
        require(AppUpdateChecker.isHttpsDownloadUrl(update.downloadUrl)) {
            "Update download URL must use HTTPS"
        }
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("简云音乐 ${update.versionName}")
            .setDescription("正在下载更新")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "JianYunMusic-v${update.versionName}.apk"
            )
        return downloadManager.enqueue(request).also { id ->
            preferences.edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_SHA256, update.sha256)
                .apply()
        }
    }

    suspend fun installIfCurrentDownload(downloadId: Long): InstallResult {
        val verificationResult = withContext(Dispatchers.IO) {
            if (downloadId != preferences.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD)) {
                return@withContext InstallResult.NotCurrentDownload
            }
            val uri = downloadManager.getUriForDownloadedFile(downloadId)
                ?: return@withContext InstallResult.DownloadUnavailable
            val expectedSha256 = preferences.getString(KEY_SHA256, "").orEmpty()
            val verified = runCatching {
                context.contentResolver.openInputStream(uri)?.let { input ->
                    AppUpdateChecker.sha256Matches(input, expectedSha256)
                } ?: false
            }.getOrDefault(false)
            if (!verified) {
                downloadManager.remove(downloadId)
                clearDownload()
                return@withContext InstallResult.VerificationFailed
            }
            uri
        }
        if (verificationResult is InstallResult) return verificationResult

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(
                verificationResult as Uri,
                "application/vnd.android.package-archive"
            )
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        clearDownload()
        return InstallResult.InstallOpened
    }

    private fun clearDownload() {
        preferences.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_SHA256)
            .apply()
    }

    private companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_SHA256 = "sha256"
        const val NO_DOWNLOAD = -1L
    }
}

enum class InstallResult {
    NotCurrentDownload,
    DownloadUnavailable,
    VerificationFailed,
    InstallOpened
}
