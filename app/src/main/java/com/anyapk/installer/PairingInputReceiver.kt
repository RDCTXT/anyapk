package com.anyapk.installer

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PairingInputReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PairingInputService.ACTION_PAIRING_INPUT) {
            return
        }

        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        if (remoteInput != null) {
            val input = remoteInput.getCharSequence(PairingInputService.KEY_PAIRING_INPUT)?.toString()
            val target = AdbTargetStore.getTarget(context)

            if (input.isNullOrEmpty()) {
                Toast.makeText(context, "Please enter the pairing code information", Toast.LENGTH_SHORT).show()
                return
            }

            val parts = input.trim().split("\\s+".toRegex())
            val code = parts[0]
            val portInt = when {
                parts.size >= 2 -> parts[1].toIntOrNull()
                target.isLocalhost() -> null
                else -> target.remotePairingPort
            }

            if (portInt == null || portInt <= 0) {
                val message = if (target.isLocalhost()) {
                    "Invalid format. Use: CODE PORT (e.g., 123456 37829)"
                } else {
                    "Missing or invalid pairing port. Enter CODE PORT, or use Test Connection for single-port devices like watches."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                return
            }

            showProgressNotification(context, target)

            scope.launch {
                val result = AdbInstaller.pair(context, target, code, portInt)

                result.onSuccess {
                    showSuccessNotification(context, target)
                    Toast.makeText(
                        context,
                        "Pairing successful for ${target.displayName()}!",
                        Toast.LENGTH_LONG
                    ).show()

                    val serviceIntent = Intent(context, PairingInputService::class.java)
                    context.stopService(serviceIntent)
                }

                result.onFailure { error ->
                    showErrorNotification(context, target, error.message ?: "Unknown error")
                    Toast.makeText(
                        context,
                        "Pairing failed: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showProgressNotification(context: Context, target: AdbTarget) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, PairingInputService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pairing ${target.displayName()}...")
            .setContentText("Connecting to ${target.displayName()}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        notificationManager.notify(PairingInputService.NOTIFICATION_ID, notification)
    }

    private fun showSuccessNotification(context: Context, target: AdbTarget) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, PairingInputService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pairing Successful!")
            .setContentText("${target.displayName()} paired successfully")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(PairingInputService.NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(context: Context, target: AdbTarget, error: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, PairingInputService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Pairing Failed for ${target.displayName()}")
            .setContentText(error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(PairingInputService.NOTIFICATION_ID, notification)
    }
}
