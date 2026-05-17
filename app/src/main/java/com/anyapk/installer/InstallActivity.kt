package com.anyapk.installer

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class InstallActivity : AppCompatActivity() {

    private lateinit var apkUri: Uri
    private lateinit var infoText: TextView
    private lateinit var installButton: Button
    private lateinit var target: AdbTarget

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_install)

        infoText = findViewById(R.id.infoText)
        installButton = findViewById(R.id.installButton)
        target = AdbTargetStore.getTarget(this)

        apkUri = intent.data ?: run {
            Toast.makeText(this, getString(R.string.error_no_apk), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val fileName = apkUri.lastPathSegment ?: "Unknown APK"
        infoText.text = getString(R.string.install_ready, "$fileName\nTarget: ${target.displayName()}")

        installButton.setOnClickListener {
            installApk()
        }
    }

    private fun installApk() {
        installButton.isEnabled = false
        infoText.text = getString(R.string.installing) + "\n\nTarget: ${target.displayName()}"

        lifecycleScope.launch(Dispatchers.IO) {
            val tempFile = File(cacheDir, "temp_install.apk")
            try {
                if (tempFile.exists()) {
                    tempFile.delete()
                }

                val inputStream = contentResolver.openInputStream(apkUri)
                    ?: throw IOException(getString(R.string.install_failed_open_apk))

                inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (!tempFile.exists()) {
                    throw IOException(getString(R.string.install_failed_copy_apk))
                }

                if (tempFile.length() <= 0) {
                    throw IOException(getString(R.string.install_failed_empty_apk))
                }

                val result = AdbInstaller.install(this@InstallActivity, target, tempFile.absolutePath)

                result.onSuccess { message ->
                    tempFile.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@InstallActivity,
                            "Installed on ${target.displayName()}!",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                }

                result.onFailure { error ->
                    val errorMsg = error.message ?: "Unknown error"
                    tempFile.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@InstallActivity, getString(R.string.install_failed, errorMsg), Toast.LENGTH_LONG).show()
                        installButton.isEnabled = true
                        infoText.text = getString(R.string.install_failed, errorMsg)
                    }
                }

            } catch (e: Exception) {
                val errorMsg = e.message?.takeIf { it.isNotBlank() }
                    ?: "${getString(R.string.install_failed_before_adb)} (${e.javaClass.simpleName})"
                tempFile.delete()
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@InstallActivity, getString(R.string.install_failed, errorMsg), Toast.LENGTH_LONG).show()
                    installButton.isEnabled = true
                    infoText.text = getString(R.string.install_failed, errorMsg)
                }
            }
        }
    }
}
