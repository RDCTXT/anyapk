package com.anyapk.installer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var refreshButton: Button
    private lateinit var testConnectionButton: Button
    private lateinit var selectApkButton: Button
    private lateinit var checkUpdateButton: Button
    private lateinit var targetModeGroup: RadioGroup
    private lateinit var localTargetRadio: RadioButton
    private lateinit var remoteTargetRadio: RadioButton
    private lateinit var remoteConfigContainer: View
    private lateinit var remoteIpInput: EditText
    private lateinit var remotePairingPortInput: EditText
    private lateinit var remoteAdbPortInput: EditText
    private lateinit var saveTargetButton: Button
    private lateinit var toggleAdvancedButton: Button
    private lateinit var advancedPanel: View
    private lateinit var commandInput: EditText
    private lateinit var runCommandButton: Button
    private lateinit var disableAppCheckButton: Button
    private lateinit var enableAppCheckButton: Button
    private lateinit var commandOutputText: TextView

    private var activeTarget: AdbTarget = AdbTarget()
    private var statusCheckJob: Job? = null
    private var statusRequestVersion: Long = 0
    private var advancedVisible: Boolean = false

    private val selectApkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val intent = Intent(this, InstallActivity::class.java).apply {
                data = it
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        actionButton = findViewById(R.id.actionButton)
        refreshButton = findViewById(R.id.refreshButton)
        testConnectionButton = findViewById(R.id.testConnectionButton)
        selectApkButton = findViewById(R.id.selectApkButton)
        checkUpdateButton = findViewById(R.id.checkUpdateButton)
        targetModeGroup = findViewById(R.id.targetModeGroup)
        localTargetRadio = findViewById(R.id.localTargetRadio)
        remoteTargetRadio = findViewById(R.id.remoteTargetRadio)
        remoteConfigContainer = findViewById(R.id.remoteConfigContainer)
        remoteIpInput = findViewById(R.id.remoteIpInput)
        remotePairingPortInput = findViewById(R.id.remotePairingPortInput)
        remoteAdbPortInput = findViewById(R.id.remoteAdbPortInput)
        saveTargetButton = findViewById(R.id.saveTargetButton)
        toggleAdvancedButton = findViewById(R.id.toggleAdvancedButton)
        advancedPanel = findViewById(R.id.advancedPanel)
        commandInput = findViewById(R.id.commandInput)
        runCommandButton = findViewById(R.id.runCommandButton)
        disableAppCheckButton = findViewById(R.id.disableAppCheckButton)
        enableAppCheckButton = findViewById(R.id.enableAppCheckButton)
        commandOutputText = findViewById(R.id.commandOutputText)

        applyTargetToInputs(AdbTargetStore.getTarget(this))
        updateAdvancedVisibility()
        updateTargetUiState(activeTarget)

        refreshButton.setOnClickListener {
            checkStatus()
        }

        testConnectionButton.setOnClickListener {
            testConnection()
        }

        selectApkButton.setOnClickListener {
            selectApkLauncher.launch("application/vnd.android.package-archive")
        }

        checkUpdateButton.setOnClickListener {
            checkForUpdates()
        }

        saveTargetButton.setOnClickListener {
            persistTargetFromInputs(showToast = true)
            checkStatus()
        }

        toggleAdvancedButton.setOnClickListener {
            advancedVisible = !advancedVisible
            updateAdvancedVisibility()
        }

        targetModeGroup.setOnCheckedChangeListener { _, _ ->
            persistTargetFromInputs(showToast = false)
            updateTargetUiState(currentUiTarget())
            checkStatus()
        }

        runCommandButton.setOnClickListener {
            executeAdvancedCommand(commandInput.text.toString())
        }

        disableAppCheckButton.setOnClickListener {
            executeAdvancedCommand("pm disable-user com.android.packageinstaller")
        }

        enableAppCheckButton.setOnClickListener {
            executeAdvancedCommand("pm enable com.android.packageinstaller")
        }
    }

    override fun onResume() {
        super.onResume()
        checkStatus()
    }

    private fun checkStatus() {
        val target = persistTargetFromInputs(showToast = false)
        val requestVersion = ++statusRequestVersion
        updateTargetUiState(target)
        statusCheckJob?.cancel()
        statusCheckJob = lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                AdbInstaller.getConnectionStatus(this@MainActivity, target)
            }

            if (!shouldApplyStatusResult(target, requestVersion)) {
                return@launch
            }

            val isDeveloperModeEnabled = if (target.isLocalhost()) isDeveloperOptionsEnabled() else true
            val hasNotificationPermission = checkNotificationPermission()
            updateTargetUiState(target)
            checkUpdateButton.isEnabled = target.isLocalhost()

            when (status.state) {
                AdbInstaller.ConnectionState.CONNECTED -> {
                    showConnectedState(target)
                }
                AdbInstaller.ConnectionState.NEEDS_AUTHORIZATION -> {
                    showAuthorizationState(target, status.message)
                }
                else -> {
                    showSetupChecklist(target, status, isDeveloperModeEnabled, hasNotificationPermission)
                }
            }
        }
    }

    private fun applyTargetToInputs(target: AdbTarget) {
        activeTarget = target
        if (target.isLocalhost()) {
            localTargetRadio.isChecked = true
        } else {
            remoteTargetRadio.isChecked = true
        }
        remoteIpInput.setText(target.remoteIp)
        remotePairingPortInput.setText(target.remotePairingPort?.toString().orEmpty())
        remoteAdbPortInput.setText(target.remoteAdbPort?.toString().orEmpty())
        updateTargetUiState(activeTarget)
    }

    private fun persistTargetFromInputs(showToast: Boolean): AdbTarget {
        val target = AdbTarget(
            mode = if (remoteTargetRadio.isChecked) InstallMode.REMOTE else InstallMode.LOCALHOST,
            remoteIp = remoteIpInput.text.toString().trim(),
            remotePairingPort = remotePairingPortInput.text.toString().trim().toIntOrNull(),
            remoteAdbPort = remoteAdbPortInput.text.toString().trim().toIntOrNull()
        )
        AdbTargetStore.saveTarget(this, target)
        activeTarget = target
        updateTargetUiState(target)
        if (showToast) {
            Toast.makeText(
                this,
                if (target.isLocalhost()) "Using this device as the install target." else "Saved remote target ${target.displayName()}.",
                Toast.LENGTH_SHORT
            ).show()
        }
        return target
    }

    private fun currentUiTarget(): AdbTarget {
        return AdbTarget(
            mode = if (remoteTargetRadio.isChecked) InstallMode.REMOTE else InstallMode.LOCALHOST,
            remoteIp = remoteIpInput.text.toString().trim(),
            remotePairingPort = remotePairingPortInput.text.toString().trim().toIntOrNull(),
            remoteAdbPort = remoteAdbPortInput.text.toString().trim().toIntOrNull()
        )
    }

    private fun shouldApplyStatusResult(expectedTarget: AdbTarget, requestVersion: Long): Boolean {
        if (requestVersion != statusRequestVersion) {
            return false
        }
        val currentTarget = currentUiTarget()
        return currentTarget.mode == expectedTarget.mode &&
            currentTarget.remoteIp == expectedTarget.remoteIp &&
            currentTarget.remotePairingPort == expectedTarget.remotePairingPort &&
            currentTarget.remoteAdbPort == expectedTarget.remoteAdbPort
    }

    private fun updateTargetUiState(target: AdbTarget) {
        remoteConfigContainer.visibility = if (remoteTargetRadio.isChecked) View.VISIBLE else View.GONE
        checkUpdateButton.alpha = if (target.isLocalhost()) 1f else 0.5f
    }

    private fun updateAdvancedVisibility() {
        advancedPanel.visibility = if (advancedVisible) View.VISIBLE else View.GONE
        toggleAdvancedButton.text = if (advancedVisible) {
            getString(R.string.btn_toggle_advanced_hide)
        } else {
            getString(R.string.btn_toggle_advanced)
        }
    }

    private fun executeAdvancedCommand(command: String) {
        val trimmedCommand = command.trim()
        if (trimmedCommand.isEmpty()) {
            commandOutputText.text = "Enter a command to run."
            return
        }

        val target = persistTargetFromInputs(showToast = false)
        val targetLabel = target.displayName()

        commandOutputText.text = "Running on $targetLabel...\n$trimmedCommand"
        runCommandButton.isEnabled = false
        disableAppCheckButton.isEnabled = false
        enableAppCheckButton.isEnabled = false

        lifecycleScope.launch {
            val result = AdbInstaller.runShellCommand(this@MainActivity, target, trimmedCommand)
            result.onSuccess { output ->
                commandOutputText.text = "[$targetLabel]\n$ $trimmedCommand\n\n$output"
            }
            result.onFailure { error ->
                val message = error.message ?: "Command execution failed."
                commandOutputText.text = "[$targetLabel]\n$ $trimmedCommand\n\n$message"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }

            runCommandButton.isEnabled = true
            disableAppCheckButton.isEnabled = true
            enableAppCheckButton.isEnabled = true
        }
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // On older Android versions, notification permission is auto-granted
            true
        }
    }

    private fun showConnectedState(target: AdbTarget) {
        statusText.text = buildString {
            append("Ready to install APKs.\n\n")
            append("Current target: ${target.displayName()}\n")
            if (!target.isLocalhost()) {
                append("ADB: ${target.remoteIp}:${target.remoteAdbPort}\n")
            }
            append("\nOpen any APK file and select anyapk, or use the built-in picker below.")
        }
        actionButton.isEnabled = false
        actionButton.text = getString(R.string.btn_connected)
        testConnectionButton.visibility = View.GONE
        refreshButton.visibility = View.VISIBLE
        selectApkButton.visibility = View.VISIBLE
    }

    private fun showSetupChecklist(
        target: AdbTarget,
        report: AdbInstaller.ConnectionReport,
        devModeEnabled: Boolean,
        notificationPermission: Boolean
    ) {
        statusText.text = if (target.isLocalhost()) {
            buildString {
                append("Current target: this device\n\n")
                append("1. Enable Developer Options")
                if (!devModeEnabled) {
                    append(" by opening Settings -> About Phone and tapping Build Number 7 times.")
                }
                append("\n2. Grant notification permission so anyapk can collect the pairing code.")
                append("\n3. Start pairing and reply with: CODE PORT")
                append("\n\n")
                append(report.message ?: "Local wireless ADB is not ready yet.")
            }
        } else {
            buildString {
                append("Current target: ${target.displayName()}\n")
                append("Pairing port: ${target.remotePairingPort?.toString() ?: "optional / not set"}\n")
                append("ADB port: ${target.remoteAdbPort?.toString() ?: "not set"}\n\n")
                append("1. Enter the remote device IP and ADB port above.")
                append("\n2. If the device supports pairing codes, also enter the pairing port.")
                append("\n3. Watches that only show IP:5555 can usually use Test Connection directly without pairing.")
                append("\n\n")
                append(report.message ?: "Remote wireless ADB is not ready yet.")
            }
        }

        when {
            !notificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                actionButton.text = "Grant Notification Permission"
                actionButton.isEnabled = true
                actionButton.setOnClickListener {
                    requestNotificationPermission()
                }
            }
            target.isLocalhost() && !devModeEnabled -> {
                actionButton.text = "Open Settings"
                actionButton.isEnabled = true
                actionButton.setOnClickListener {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Please open Settings manually", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            target.isLocalhost() || target.canAttemptPairing() -> {
                actionButton.text = "Start Pairing"
                actionButton.isEnabled = true
                actionButton.setOnClickListener {
                    startPairingFlow(target)
                }
            }
            target.isRemoteConfiguredForConnection() -> {
                actionButton.text = getString(R.string.btn_test_connection)
                actionButton.isEnabled = true
                actionButton.setOnClickListener {
                    testConnection()
                }
            }
            else -> {
                actionButton.text = "Save Remote Target"
                actionButton.isEnabled = true
                actionButton.setOnClickListener {
                    persistTargetFromInputs(showToast = true)
                    checkStatus()
                }
            }
        }

        testConnectionButton.visibility = View.GONE
        selectApkButton.visibility = View.GONE
    }

    private fun isDeveloperOptionsEnabled(): Boolean {
        return try {
            android.provider.Settings.Global.getInt(
                contentResolver,
                android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            // If we can't determine, assume it's enabled to avoid confusion
            true
        }
    }

    private fun startPairingFlow(target: AdbTarget) {
        val persistedTarget = persistTargetFromInputs(showToast = false)
        if (!persistedTarget.canAttemptPairing()) {
            Toast.makeText(
                this,
                "Enter a pairing port first, or use Test Connection for single-port devices like watches.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val serviceIntent = Intent(this, PairingInputService::class.java).apply {
            putExtra(PairingInputService.EXTRA_TARGET_MODE, persistedTarget.mode.name)
            putExtra(PairingInputService.EXTRA_TARGET_DISPLAY, persistedTarget.displayName())
            putExtra(
                PairingInputService.EXTRA_REQUIRE_PORT_INPUT,
                persistedTarget.isLocalhost() || persistedTarget.remotePairingPort == null
            )
        }
        startService(serviceIntent)

        if (target.isLocalhost()) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Please open Settings → Developer Options → Wireless Debugging manually",
                    Toast.LENGTH_LONG
                ).show()
            }
            Toast.makeText(
                this,
                "Open Wireless Debugging on this device, tap Pair device, then reply in the notification with CODE PORT.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                this,
                "On the other device, open Wireless Debugging and Pair device. Then enter the pairing code in the notification here.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    this,
                    "✅ Notification permission granted!",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Notification permission is required for pairing. Please enable it in Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
            // Refresh status to update checklist
            checkStatus()
        }
    }

    private fun showAuthorizationState(target: AdbTarget, message: String?) {
        statusText.text = buildString {
            append("Authorization required for ${target.displayName()}.\n\n")
            append(message ?: "Use Test Connection to retry the ADB authorization flow.")
        }
        if (target.isLocalhost() || target.canAttemptPairing()) {
            actionButton.text = "Start Pairing"
            actionButton.isEnabled = true
            actionButton.setOnClickListener {
                startPairingFlow(target)
            }
        } else {
            actionButton.text = "Save Remote Target"
            actionButton.isEnabled = true
            actionButton.setOnClickListener {
                persistTargetFromInputs(showToast = true)
                checkStatus()
            }
        }
        testConnectionButton.visibility = View.VISIBLE
        testConnectionButton.isEnabled = true
        testConnectionButton.text = getString(R.string.btn_test_connection)
        selectApkButton.visibility = View.GONE
    }

    private fun testConnection() {
        testConnectionButton.isEnabled = false
        testConnectionButton.text = "Testing..."
        val target = persistTargetFromInputs(showToast = false)

        lifecycleScope.launch {
            val result = AdbInstaller.testConnection(this@MainActivity, target)

            result.onSuccess {
                Toast.makeText(
                    this@MainActivity,
                    "Connection confirmed for ${target.displayName()}.",
                    Toast.LENGTH_LONG
                ).show()
                checkStatus()
            }

            result.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    "Connection test failed: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
                testConnectionButton.isEnabled = true
                testConnectionButton.text = getString(R.string.btn_test_connection)
            }
        }
    }

    private fun checkForUpdates() {
        val target = persistTargetFromInputs(showToast = false)
        if (!target.isLocalhost()) {
            Toast.makeText(this, "App self-updates only work while 'This device' is selected.", Toast.LENGTH_LONG).show()
            return
        }

        checkUpdateButton.isEnabled = false
        checkUpdateButton.text = "Checking..."

        lifecycleScope.launch {
            val updateInfo = UpdateChecker.checkForUpdate(this@MainActivity)

            if (updateInfo != null) {
                showUpdateDialog(updateInfo)
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "You're running the latest version!",
                    Toast.LENGTH_SHORT
                ).show()
            }

            checkUpdateButton.isEnabled = true
            checkUpdateButton.text = "Check for Updates"
        }
    }

    private fun showUpdateDialog(updateInfo: UpdateChecker.UpdateInfo) {
        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName
        val message = buildString {
            append("A new version is available!\n\n")
            append("Current: $currentVersion\n")
            append("Latest: ${updateInfo.versionName}\n\n")
            if (updateInfo.releaseNotes.isNotBlank()) {
                append("What's new:\n")
                append(updateInfo.releaseNotes.take(200))
                if (updateInfo.releaseNotes.length > 200) {
                    append("...")
                }
                append("\n\n")
            }
            append("Note: The app will close during the update and restart with the new version.")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage(message)
            .setPositiveButton("Download & Install") { _, _ ->
                downloadAndInstallUpdate(updateInfo)
            }
            .setNegativeButton("Not Now", null)
            .setCancelable(true)
            .show()
    }

    private fun downloadAndInstallUpdate(updateInfo: UpdateChecker.UpdateInfo) {
        val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Downloading Update")
            .setMessage("Downloading version ${updateInfo.versionName}...\n0%")
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            val result = UpdateManager.downloadAndInstall(
                this@MainActivity,
                updateInfo.downloadUrl,
                updateInfo.versionName
            ) { progress ->
                progressDialog.setMessage("Downloading version ${updateInfo.versionName}...\n$progress%")
            }

            progressDialog.dismiss()

            result.onSuccess { message ->
                // Show a toast before the app closes
                Toast.makeText(
                    this@MainActivity,
                    "Installing update via ADB...\nApp will restart shortly.",
                    Toast.LENGTH_LONG
                ).show()
                // Note: App will be killed by Android during the update process
            }

            result.onFailure { error ->
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update Failed")
                    .setMessage("Failed to install update: ${error.message}\n\nMake sure ADB is connected and authorized.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
    }
}
