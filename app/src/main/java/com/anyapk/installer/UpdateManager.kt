package com.anyapk.installer

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages APK download and installation for app updates
 */
object UpdateManager {
    private const val TAG = "UpdateManager"

    /**
     * Downloads and installs an APK update via ADB
     * @param context Application context
     * @param downloadUrl URL to download the APK from
     * @param versionName Version name for the filename
     * @param onProgress Optional progress callback (0-100)
     *
     * NOTE: The app will be killed during self-update. This is expected behavior.
     * The ADB installation will complete even after the app closes.
     */
    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        versionName: String,
        onProgress: ((Int) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting download from: $downloadUrl")

            // Download the APK
            val apkFile = downloadApk(context, downloadUrl, versionName, onProgress)

            // Install the APK via ADB (same method used for regular installs)
            Log.d(TAG, "Installing update via ADB: ${apkFile.absolutePath}")
            val installResult = AdbInstaller.install(
                context,
                AdbTarget(mode = InstallMode.LOCALHOST),
                apkFile.absolutePath
            )

            installResult.onSuccess { message ->
                Log.d(TAG, "Update installation started: $message")
                Log.d(TAG, "App will be killed and restarted with new version")
            }

            installResult.onFailure { error ->
                Log.e(TAG, "Update installation failed: ${error.message}")
            }

            installResult
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading/installing update", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads the APK file
     */
    private suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        versionName: String,
        onProgress: ((Int) -> Unit)?
    ): File = withContext(Dispatchers.IO) {
        val fileName = "anyapk-$versionName.apk"
        val outputFile = File(context.cacheDir, fileName)

        if (outputFile.exists()) {
            outputFile.delete()
        }

        val url = URL(downloadUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 30000

        val totalBytes = connection.contentLength
        Log.d(TAG, "Download size: ${totalBytes / 1024 / 1024} MB")

        connection.inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastProgress = 0

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    if (totalBytes > 0) {
                        val progress = ((totalBytesRead * 100) / totalBytes).toInt()
                        if (progress != lastProgress) {
                            lastProgress = progress
                            withContext(Dispatchers.Main) {
                                onProgress?.invoke(progress)
                            }
                        }
                    }
                }
            }
        }

        Log.d(TAG, "Download complete: ${outputFile.absolutePath}")
        outputFile
    }

    /**
     * Alternative: Use Android's DownloadManager (shows in notification)
     * Note: This is kept for reference but downloadAndInstall() is the recommended approach
     */
    fun downloadWithManager(
        context: Context,
        downloadUrl: String,
        versionName: String
    ): Long {
        val fileName = "anyapk-$versionName.apk"

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("anyapk Update")
            .setDescription("Downloading version $versionName")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return downloadManager.enqueue(request)
    }

    /**
     * Broadcast receiver for DownloadManager completion
     */
    class DownloadCompleteReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (downloadId == -1L) return

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)

            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val uriString = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    val apkUri = Uri.parse(uriString)

                    // Install the downloaded APK
                    val installIntent = Intent(Intent.ACTION_VIEW)
                    installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive")
                    installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(installIntent)
                }
            }
            cursor.close()
        }
    }
}
