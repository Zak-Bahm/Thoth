package com.bahm.thoth.knowledge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ZimDownloadForegroundService : Service() {

    companion object {
        private const val TAG = "ZimDownloadFg"
        private const val CHANNEL_ID = "zim_download"
        private const val NOTIFICATION_ID = 1

        private const val EXTRA_URL = "url"
        private const val EXTRA_FILENAME = "filename"
        private const val ACTION_CANCEL = "com.bahm.thoth.CANCEL_DOWNLOAD"

        private val _progress = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val progress: StateFlow<DownloadState> = _progress.asStateFlow()

        fun start(context: Context, url: String, filename: String) {
            val intent = Intent(context, ZimDownloadForegroundService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FILENAME, filename)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ZimDownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(
            val bytesDownloaded: Long,
            val totalBytes: Long,
            val filename: String,
        ) : DownloadState()
        data class Complete(val filename: String) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    @Inject lateinit var zimDownloadService: ZimDownloadService

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager
    private var lastNotificationUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Log.d(TAG, "Cancel requested")
            zimDownloadService.cancelDownload()
            _progress.value = DownloadState.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val filename = intent.getStringExtra(EXTRA_FILENAME) ?: return START_NOT_STICKY

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Preparing download...", 0, -1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        serviceScope.launch {
            _progress.value = DownloadState.Downloading(0, -1, filename)

            zimDownloadService.download(url, filename)
                .catch { e ->
                    Log.e(TAG, "Download failed: ${e.message}", e)
                    _progress.value = DownloadState.Error(e.message ?: "Download failed")
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildNotification("Download failed", 0, -1),
                    )
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
                .collect { dp ->
                    _progress.value = DownloadState.Downloading(
                        dp.bytesDownloaded,
                        dp.totalBytes,
                        filename,
                    )
                    updateNotification(dp.bytesDownloaded, dp.totalBytes)
                }

            // If we finished collecting without an error, download is complete
            if (_progress.value is DownloadState.Downloading) {
                Log.d(TAG, "Download complete: $filename")
                _progress.value = DownloadState.Complete(filename)
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification("Download complete", 100, 100),
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ZIM Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress while downloading Wikipedia archives"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val cancelIntent = Intent(this, ZimDownloadForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this, 0, cancelIntent, PendingIntent.FLAG_IMMUTABLE,
        )

        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPending = openIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading Wikipedia")
            .setContentText(text)
            .setProgress(max, progress, max < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(
                Notification.Action.Builder(
                    null, "Cancel", cancelPending,
                ).build(),
            )
            .build()
    }

    private fun updateNotification(bytesDownloaded: Long, totalBytes: Long) {
        val now = System.currentTimeMillis()
        // Throttle notification updates to every 2 seconds to avoid notification rate limiting
        if (now - lastNotificationUpdate < 2_000) return
        lastNotificationUpdate = now

        val text = if (totalBytes > 0) {
            val pct = (bytesDownloaded * 100 / totalBytes).toInt()
            val downloadedGb = bytesDownloaded / 1_000_000_000.0
            val totalGb = totalBytes / 1_000_000_000.0
            "${"%.1f".format(downloadedGb)} / ${"%.1f".format(totalGb)} GB ($pct%)"
        } else {
            "${"%.1f".format(bytesDownloaded / 1_000_000_000.0)} GB"
        }

        val progress = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
        val max = if (totalBytes > 0) 100 else -1

        notificationManager.notify(NOTIFICATION_ID, buildNotification(text, progress, max))
    }
}
