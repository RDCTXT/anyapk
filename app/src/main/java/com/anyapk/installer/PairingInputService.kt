package com.anyapk.installer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PairingInputService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var requirePortInput: Boolean = true
    private var targetDisplay: String = "this device"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createPairingNotification(): Notification {
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_INPUT)
            .setLabel(
                if (requirePortInput) {
                    "Code and Port (e.g., 123456 37829)"
                } else {
                    "Pairing Code (e.g., 123456)"
                }
            )
            .build()

        val replyIntent = Intent(this, PairingInputReceiver::class.java).apply {
            action = ACTION_PAIRING_INPUT
        }

        val replyPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val shortText = if (requirePortInput) {
            "Reply with CODE PORT for $targetDisplay"
        } else {
            "Reply with the pairing code for $targetDisplay"
        }
        val longText = if (requirePortInput) {
            "Open Settings -> Wireless Debugging on $targetDisplay.\n\nThen tap Reply and enter: CODE PORT\n\nExample: 123456 37829\n\nIf the target only shows IP:5555, skip pairing and use Test Connection instead."
        } else {
            "Open Wireless Debugging on $targetDisplay and note the pairing code.\n\nThen tap Reply and enter just the code.\n\nThe saved pairing port will be used automatically."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pair with $targetDisplay")
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(longText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_input_add,
                    "Reply",
                    replyPendingIntent
                )
                    .addRemoteInput(remoteInput)
                    .build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pairing Input",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Enter pairing code directly from notification"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        requirePortInput = intent?.getBooleanExtra(EXTRA_REQUIRE_PORT_INPUT, true) ?: true
        targetDisplay = intent?.getStringExtra(EXTRA_TARGET_DISPLAY) ?: "this device"
        startForeground(NOTIFICATION_ID, createPairingNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "pairing_input_channel"
        const val NOTIFICATION_ID = 3001
        const val KEY_PAIRING_INPUT = "pairing_input"
        const val ACTION_PAIRING_INPUT = "com.anyapk.installer.PAIRING_INPUT"
        const val EXTRA_TARGET_MODE = "target_mode"
        const val EXTRA_TARGET_DISPLAY = "target_display"
        const val EXTRA_REQUIRE_PORT_INPUT = "require_port_input"
    }
}
