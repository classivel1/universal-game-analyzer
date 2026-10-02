package com.estilodocampo.gameanalyzer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var spinner: Spinner
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var updateManager: UpdateManager

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_START
                    putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(CaptureService.EXTRA_DATA, result.data)
                    putExtra(CaptureService.EXTRA_PROFILE, spinner.selectedItemPosition)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                status.text = "Status: captura iniciada. Abra o Aviator."
            } else {
                status.text = "Status: permissão de captura negada."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        status = findViewById(R.id.statusText)
        spinner = findViewById(R.id.profileSpinner)

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        findViewById<TextView>(R.id.versionText).text =
            "Versão " + (packageInfo.versionName ?: "—")

        updateManager = UpdateManager(
            this,
            status,
            findViewById(R.id.updateButton)
        )
        updateManager.register()
        updateManager.check(auto = true)

        val profileAdapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            listOf("Aviator — leitura de multiplicadores")
        )
        profileAdapter.setDropDownViewResource(R.layout.spinner_item)
        spinner.adapter = profileAdapter

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                22
            )
        }

        findViewById<Button>(R.id.overlayPermissionButton).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:\$packageName")
                    )
                )
            } else {
                status.text = "Status: sobreposição já permitida."
            }
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                status.text = "Status: primeiro permita a sobreposição."
                return@setOnClickListener
            }
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_STOP
            })
            status.text = "Status: parado."
        }
    }

    override fun onResume() {
        super.onResume()
        if (::updateManager.isInitialized) {
            updateManager.onResume()
        }
    }

    override fun onDestroy() {
        if (::updateManager.isInitialized) {
            updateManager.unregister()
        }
        super.onDestroy()
    }
}
