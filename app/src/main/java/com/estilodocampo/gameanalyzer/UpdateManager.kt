package com.estilodocampo.gameanalyzer

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(
    private val activity: Activity,
    private val status: TextView,
    private val button: Button
) {
    private val downloadManager =
        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private var downloadId = -1L
    private var pendingApkUri: Uri? = null
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id != downloadId) return

            pendingApkUri = downloadManager.getUriForDownloadedFile(downloadId)
            button.isEnabled = true

            if (pendingApkUri == null) {
                status.text = "Status: falha ao baixar atualização."
                button.text = "Verificar atualização"
                button.setOnClickListener { check(false) }
                return
            }

            status.text = "Status: atualização baixada. Abrindo instalador…"
            installPendingUpdate()
        }
    }

    init {
        button.setOnClickListener { check(false) }
    }

    fun register() {
        if (receiverRegistered) return

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(receiver, filter)
        }

        receiverRegistered = true
    }

    fun unregister() {
        if (!receiverRegistered) return
        runCatching { activity.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    fun onResume() {
        val uri = pendingApkUri ?: return
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()
        ) {
            installApk(uri)
        }
    }

    fun check(auto: Boolean) {
        if (!auto) {
            status.text = "Status: verificando atualização…"
            button.isEnabled = false
        }

        Thread {
            try {
                val connection =
                    URL("https://api.github.com/repos/classivel1/universal-game-analyzer/releases/latest")
                        .openConnection() as HttpURLConnection

                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("User-Agent", "AviatorAnalyzer-Android")
                connection.setRequestProperty("Accept", "application/vnd.github+json")

                val body = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                val json = JSONObject(body)
                val tag = json.optString("tag_name")
                val remoteCode = tag.substringAfterLast('.').toIntOrNull() ?: 0
                val assets = json.optJSONArray("assets")

                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                }

                val packageInfo =
                    activity.packageManager.getPackageInfo(activity.packageName, 0)
                val currentCode =
                    if (Build.VERSION.SDK_INT >= 28) {
                        packageInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode
                    }

                val available =
                    remoteCode > currentCode &&
                        !apkUrl.isNullOrBlank()

                activity.runOnUiThread {
                    button.isEnabled = true

                    if (available) {
                        status.text =
                            "Status: nova versão $tag disponível."
                        button.text = "ATUALIZAR AGORA — $tag"
                        button.visibility = View.VISIBLE
                        button.setOnClickListener {
                            downloadUpdate(apkUrl!!, tag)
                        }
                    } else {
                        button.text = "Verificar atualização"
                        button.setOnClickListener { check(false) }

                        if (!auto) {
                            status.text =
                                "Status: aplicativo já está atualizado."
                        }
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    button.isEnabled = true
                    button.text = "Verificar atualização"
                    button.setOnClickListener { check(false) }

                    if (!auto) {
                        status.text =
                            "Status: não foi possível verificar atualização."
                    }
                }
            }
        }.start()
    }

    private fun downloadUpdate(url: String, tag: String) {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Aviator Analyzer $tag")
            .setDescription("Baixando atualização")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(
                activity,
                Environment.DIRECTORY_DOWNLOADS,
                "aviator-analyzer-$tag.apk"
            )

        downloadId = downloadManager.enqueue(request)
        status.text = "Status: baixando atualização $tag…"
        button.text = "Baixando…"
        button.isEnabled = false
    }

    private fun installPendingUpdate() {
        val uri = pendingApkUri ?: return

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            status.text =
                "Status: permita instalar apps deste aplicativo uma vez."
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }

        installApk(uri)
    }

    private fun installApk(uri: Uri) {
        runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        uri,
                        "application/vnd.android.package-archive"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure {
            status.text =
                "Status: não foi possível abrir o instalador da atualização."
        }
    }
}
