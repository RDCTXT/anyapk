package com.anyapk.installer

import android.content.Context

enum class InstallMode {
    LOCALHOST,
    REMOTE
}

data class AdbTarget(
    val mode: InstallMode = InstallMode.LOCALHOST,
    val remoteIp: String = "",
    val remotePairingPort: Int? = null,
    val remoteAdbPort: Int? = null
) {
    fun isLocalhost(): Boolean = mode == InstallMode.LOCALHOST

    fun isRemoteConfiguredForPairing(): Boolean {
        return isLocalhost() || (remoteIp.isNotBlank() && remotePairingPort != null)
    }

    fun canAttemptPairing(): Boolean {
        return isLocalhost() || (remoteIp.isNotBlank() && remotePairingPort != null)
    }

    fun isRemoteConfiguredForConnection(): Boolean {
        return isLocalhost() || (remoteIp.isNotBlank() && remoteAdbPort != null)
    }

    fun hasRemoteAddress(): Boolean {
        return isLocalhost() || remoteIp.isNotBlank()
    }

    fun displayName(): String {
        return if (isLocalhost()) "this device" else remoteIp.ifBlank { "remote device" }
    }

    fun cacheKey(): String {
        return listOf(mode.name, remoteIp, remotePairingPort ?: "", remoteAdbPort ?: "").joinToString("|")
    }
}

object AdbTargetStore {
    private const val PREFS_NAME = "adb_target"
    private const val KEY_MODE = "mode"
    private const val KEY_REMOTE_IP = "remote_ip"
    private const val KEY_REMOTE_PAIRING_PORT = "remote_pairing_port"
    private const val KEY_REMOTE_ADB_PORT = "remote_adb_port"

    fun getTarget(context: Context): AdbTarget {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeName = prefs.getString(KEY_MODE, InstallMode.LOCALHOST.name) ?: InstallMode.LOCALHOST.name
        val mode = runCatching { InstallMode.valueOf(modeName) }.getOrDefault(InstallMode.LOCALHOST)
        return AdbTarget(
            mode = mode,
            remoteIp = prefs.getString(KEY_REMOTE_IP, "")?.trim().orEmpty(),
            remotePairingPort = if (prefs.contains(KEY_REMOTE_PAIRING_PORT)) prefs.getInt(KEY_REMOTE_PAIRING_PORT, 0).takeIf { it > 0 } else null,
            remoteAdbPort = if (prefs.contains(KEY_REMOTE_ADB_PORT)) prefs.getInt(KEY_REMOTE_ADB_PORT, 0).takeIf { it > 0 } else null
        )
    }

    fun saveTarget(context: Context, target: AdbTarget) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_MODE, target.mode.name)
            putString(KEY_REMOTE_IP, target.remoteIp.trim())
            if (target.remotePairingPort != null) {
                putInt(KEY_REMOTE_PAIRING_PORT, target.remotePairingPort)
            } else {
                remove(KEY_REMOTE_PAIRING_PORT)
            }
            if (target.remoteAdbPort != null) {
                putInt(KEY_REMOTE_ADB_PORT, target.remoteAdbPort)
            } else {
                remove(KEY_REMOTE_ADB_PORT)
            }
        }.apply()
    }
}
