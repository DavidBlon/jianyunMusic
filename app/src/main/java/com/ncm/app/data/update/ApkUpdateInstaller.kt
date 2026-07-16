package com.ncm.app.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

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
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("简韵音乐 ${update.versionName}")
            .setDescription("正在下载更新")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "JianYunMusic-v${update.versionName}.apk"
            )
        return downloadManager.enqueue(request).also { id ->
            preferences.edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        }
    }

    fun installIfCurrentDownload(downloadId: Long): Boolean {
        if (downloadId != preferences.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD)) return false
        val uri = downloadManager.getUriForDownloadedFile(downloadId) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        preferences.edit().remove(KEY_DOWNLOAD_ID).apply()
        return true
    }

    private companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        const val NO_DOWNLOAD = -1L
    }
}
