package com.anyapk.installer

import android.content.Context
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.*

object AdbInstaller {

    private const val LOCALHOST = "127.0.0.1"
    private const val CONNECTION_CHECK_CACHE_MS = 2000L
    private const val INSTALL_OUTPUT_POLL_MS = 100L
    private const val INSTALL_OUTPUT_MAX_WAIT_MS = 120000L
    private const val INSTALL_OUTPUT_SETTLE_MS = 1000L
    private const val NO_INSTALL_OUTPUT_MESSAGE = "Device returned no output during package install."
    private const val NO_COMMAND_OUTPUT_MESSAGE = "Command completed with no output."

    enum class ConnectionState {
        CONNECTED,
        NEEDS_PAIRING,
        NEEDS_REMOTE_CONFIG,
        NEEDS_AUTHORIZATION,
        UNREACHABLE,
        ERROR
    }

    data class ConnectionReport(
        val state: ConnectionState,
        val message: String? = null
    )

    private data class StreamTranscript(
        val text: String,
        val timedOut: Boolean
    )

    @Volatile
    private var lastConnectionCheck: Long = 0
    @Volatile
    private var lastConnectionStatus: ConnectionReport = ConnectionReport(ConnectionState.NEEDS_PAIRING)
    @Volatile
    private var lastTargetKey: String = ""

    fun getConnectionStatus(context: Context, target: AdbTarget, forceCheck: Boolean = false): ConnectionReport {
        val now = System.currentTimeMillis()
        val targetKey = target.cacheKey()
        if (!forceCheck && targetKey == lastTargetKey && (now - lastConnectionCheck) < CONNECTION_CHECK_CACHE_MS) {
            return lastConnectionStatus
        }

        val status = when {
            !target.hasRemoteAddress() -> {
                ConnectionReport(ConnectionState.NEEDS_REMOTE_CONFIG, "Enter a remote IP address.")
            }
            !target.isRemoteConfiguredForConnection() -> {
                ConnectionReport(ConnectionState.NEEDS_REMOTE_CONFIG, "Enter the remote device's ADB port to connect.")
            }
            else -> {
                try {
                    withManager(context) { manager ->
                        if (!connectToTarget(manager, context, target, 3000)) {
                            if (target.isLocalhost()) {
                                ConnectionReport(ConnectionState.NEEDS_PAIRING, "Enable wireless debugging and pair this device.")
                            } else {
                                ConnectionReport(
                                    ConnectionState.UNREACHABLE,
                                    buildString {
                                        append("Couldn't reach ${target.remoteIp}:${target.remoteAdbPort}.")
                                        if (target.remotePairingPort == null) {
                                            append(" If this is a watch showing IP:5555, try Test Connection directly.")
                                        }
                                    }
                                )
                            }
                        } else {
                            runShellCheck(manager)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    ConnectionReport(ConnectionState.ERROR, e.message ?: "Connection check failed.")
                }
            }
        }

        lastConnectionCheck = now
        lastConnectionStatus = status
        lastTargetKey = targetKey
        return status
    }

    suspend fun pair(context: Context, target: AdbTarget, pairingCode: String, pairingPortOverride: Int? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        return@withContext try {
            val pairingPort = pairingPortOverride ?: target.remotePairingPort
            if (pairingPort == null || pairingPort <= 0) {
                return@withContext Result.failure(Exception("Missing pairing port"))
            }
            val host = if (target.isLocalhost()) LOCALHOST else target.remoteIp
            if (host.isBlank()) {
                return@withContext Result.failure(Exception("Missing target host"))
            }
            withManager(context) { manager ->
                manager.pair(host, pairingPort, pairingCode)
            }
            invalidateCache()
            Result.success(true)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun testConnection(context: Context, target: AdbTarget): Result<Boolean> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!target.isRemoteConfiguredForConnection()) {
                return@withContext Result.failure(Exception("Target is missing connection details."))
            }
            val report = withManager(context) { manager ->
                if (!connectToTarget(manager, context, target, 10000)) {
                    if (target.isLocalhost()) {
                        ConnectionReport(ConnectionState.NEEDS_PAIRING, "Could not connect to local ADB.")
                    } else {
                        ConnectionReport(ConnectionState.UNREACHABLE, "Could not reach ${target.remoteIp}:${target.remoteAdbPort}.")
                    }
                } else {
                    runShellCheck(manager)
                }
            }
            when (report.state) {
                ConnectionState.CONNECTED -> {
                    invalidateCache()
                    Result.success(true)
                }
                ConnectionState.NEEDS_AUTHORIZATION -> Result.failure(Exception(report.message ?: "Authorization required."))
                else -> Result.failure(Exception(report.message ?: "Connection test failed."))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(Exception("Authorization required. Check the debugging prompt and try again."))
        }
    }

    suspend fun install(context: Context, target: AdbTarget, apkPath: String): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!target.isRemoteConfiguredForConnection()) {
                return@withContext Result.failure(Exception("Target is missing connection details."))
            }

            invalidateCache()

            val result = withManager(context) { manager ->
                if (!connectToTarget(manager, context, target, 10000)) {
                    return@withManager Result.failure(
                        Exception(
                            if (target.isLocalhost()) {
                                "Failed to connect to local ADB. Make sure wireless debugging is enabled and you've paired."
                            } else {
                                "Failed to connect to ${target.remoteIp}:${target.remoteAdbPort}. Check the IP, ADB port, and pairing state."
                            }
                        )
                    )
                }

                val apkFile = java.io.File(apkPath)
                val apkSize = apkFile.length()
                var stream: AdbStream? = null
                try {
                    stream = manager.openStream("exec:cmd package install -S $apkSize")
                    stream.openOutputStream().use { outputStream ->
                        java.io.FileInputStream(apkFile).use { input ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                            }
                            outputStream.flush()
                        }
                    }

                    val inputStream = stream.openInputStream()
                    val transcript = readStreamTranscript(
                        inputStream = inputStream,
                        maxWaitMs = INSTALL_OUTPUT_MAX_WAIT_MS,
                        terminalMarkers = listOf("Success", "Failure"),
                        settleAfterTerminalMs = INSTALL_OUTPUT_SETTLE_MS
                    )
                    val installResult = transcript.text.ifEmpty {
                        if (transcript.timedOut) {
                            "Timed out waiting for package install output."
                        } else {
                            NO_INSTALL_OUTPUT_MESSAGE
                        }
                    }
                    if (installResult.contains("Success", ignoreCase = true)) {
                        invalidateCache(ConnectionReport(ConnectionState.CONNECTED), target.cacheKey())
                        Result.success(installResult)
                    } else if (transcript.timedOut) {
                        Result.failure(Exception(formatInstallTimeoutMessage(installResult)))
                    } else {
                        Result.failure(Exception(installResult))
                    }
                } finally {
                    try {
                        stream?.close()
                    } catch (ignored: Exception) {
                    }
                }
            }

            result
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(Exception(e.message ?: NO_INSTALL_OUTPUT_MESSAGE, e))
        }
    }

    private fun formatInstallTimeoutMessage(output: String): String {
        return if (output.isBlank() || output == "Timed out waiting for package install output.") {
            "Timed out waiting for package install result."
        } else {
            "Timed out waiting for package install result. Partial output:\n$output"
        }
    }

    suspend fun runShellCommand(context: Context, target: AdbTarget, command: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmedCommand = command.trim()
        if (trimmedCommand.isEmpty()) {
            return@withContext Result.failure(Exception("Enter a shell command to run."))
        }
        if (!target.isRemoteConfiguredForConnection()) {
            return@withContext Result.failure(Exception("Target is missing connection details."))
        }

        return@withContext try {
            withManager(context) { manager ->
                if (!connectToTarget(manager, context, target, 10000)) {
                    return@withManager Result.failure(
                        Exception(
                            if (target.isLocalhost()) {
                                "Failed to connect to local ADB. Make sure wireless debugging is enabled and you've paired."
                            } else {
                                "Failed to connect to ${target.remoteIp}:${target.remoteAdbPort}. Check the IP, ADB port, and connection state."
                            }
                        )
                    )
                }

                executeService(manager, "shell:$trimmedCommand", NO_COMMAND_OUTPUT_MESSAGE)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(Exception(e.message ?: "Command execution failed.", e))
        }
    }

    private fun invalidateCache(
        report: ConnectionReport = ConnectionReport(ConnectionState.NEEDS_PAIRING),
        targetKey: String = ""
    ) {
        lastConnectionCheck = 0
        lastConnectionStatus = report
        lastTargetKey = targetKey
    }

    private fun runShellCheck(manager: AbsAdbConnectionManager): ConnectionReport {
        var stream: AdbStream? = null
        return try {
            stream = manager.openStream("shell:echo test")
            val buffer = ByteArray(128)
            val inputStream = stream.openInputStream()
            var totalWait = 0
            while (totalWait < 5000) {
                if (inputStream.available() > 0) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val output = String(buffer, 0, bytesRead)
                        return if (output.contains("test")) {
                            ConnectionReport(ConnectionState.CONNECTED)
                        } else {
                            ConnectionReport(ConnectionState.NEEDS_AUTHORIZATION, "Connected, but ADB authorization is still required.")
                        }
                    }
                } else {
                    Thread.sleep(100)
                    totalWait += 100
                }
            }
            ConnectionReport(ConnectionState.NEEDS_AUTHORIZATION, "Connected, but no shell response was returned.")
        } catch (e: Exception) {
            e.printStackTrace()
            ConnectionReport(ConnectionState.NEEDS_AUTHORIZATION, "ADB authorization is required before installs can continue.")
        } finally {
            try {
                stream?.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private suspend fun executeService(
        manager: AbsAdbConnectionManager,
        destination: String,
        emptyOutputFallback: String
    ): Result<String> {
        var stream: AdbStream? = null
        return try {
            stream = manager.openStream(destination)
            val transcript = readStreamTranscript(
                inputStream = stream.openInputStream(),
                maxWaitMs = 15000L
            )
            val output = transcript.text.trim()
            Result.success(
                when {
                    output.isNotEmpty() -> output
                    transcript.timedOut -> "Timed out waiting for command output."
                    else -> emptyOutputFallback
                }
            )
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Command execution failed.", e))
        } finally {
            try {
                stream?.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private suspend fun readStreamTranscript(
        inputStream: java.io.InputStream,
        maxWaitMs: Long,
        terminalMarkers: List<String> = emptyList(),
        settleAfterTerminalMs: Long = 0L
    ): StreamTranscript {
        val output = StringBuilder()
        val buffer = ByteArray(1024)
        var totalWaitMs = 0L
        var settleWaitMs = 0L

        while (totalWaitMs < maxWaitMs) {
            if (inputStream.available() > 0) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) {
                    return StreamTranscript(output.toString().trim(), timedOut = false)
                }
                if (bytesRead > 0) {
                    output.append(String(buffer, 0, bytesRead))
                    settleWaitMs = 0L
                }
                continue
            }

            delay(INSTALL_OUTPUT_POLL_MS)
            totalWaitMs += INSTALL_OUTPUT_POLL_MS

            if (terminalMarkers.isEmpty() || settleAfterTerminalMs <= 0L) {
                continue
            }

            val currentOutput = output.toString()
            val hasTerminalMarker = terminalMarkers.any { marker ->
                currentOutput.contains(marker, ignoreCase = true)
            }
            if (hasTerminalMarker) {
                settleWaitMs += INSTALL_OUTPUT_POLL_MS
                if (settleWaitMs >= settleAfterTerminalMs) {
                    return StreamTranscript(currentOutput.trim(), timedOut = false)
                }
            }
        }

        return StreamTranscript(output.toString().trim(), timedOut = true)
    }

    private fun connectToTarget(
        manager: AbsAdbConnectionManager,
        context: Context,
        target: AdbTarget,
        timeoutMs: Long
    ): Boolean {
        return if (target.isLocalhost()) {
            manager.autoConnect(context, timeoutMs)
        } else {
            manager.connect(target.remoteIp, target.remoteAdbPort ?: return false)
        }
    }

    private inline fun <T> withManager(context: Context, block: (AbsAdbConnectionManager) -> T): T {
        val manager = object : AbsAdbConnectionManager() {
            private val delegate = AdbConnectionManager.getInstance(context)

            override fun getPrivateKey() = delegate.getPrivateKey()
            override fun getCertificate() = delegate.getCertificate()
            override fun getDeviceName() = delegate.getDeviceName()
        }
        manager.setApi(android.os.Build.VERSION.SDK_INT)
        return try {
            block(manager)
        } finally {
            try {
                manager.close()
            } catch (ignored: Exception) {
            }
        }
    }
}
