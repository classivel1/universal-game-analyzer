package com.estilodocampo.gameanalyzer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(
    private val activity: Activity,
    private val status: TextView,
    private val button: Button
) {
    private var pendingFile: File? = null
    @Volatile private var downloading = false

    init {
        button.setOnClickListener { check(false) }
    }

    fun register() = Unit

    fun unregister() = Unit

    fun onResume() {
        val file = pendingFile ?: return
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()
        ) {
            installApk(file)
        }
    }

    fun check(auto: Boolean) {
        if (downloading) return

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
                connection.instanceFollowRedirects = true
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
                        status.text = "Status: nova versão " + tag + " disponível."
                        button.text = "ATUALIZAR AGORA — " + tag
                        button.visibility = View.VISIBLE
                        button.setOnClickListener {
                            downloadUpdate(apkUrl!!, tag)
                        }
                    } else {
                        button.text = "Verificar atualização"
                        button.setOnClickListener { check(false) }

                        if (!auto) {
                            status.text = "Status: aplicativo já está atualizado."
                        }
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    button.isEnabled = true
                    button.text = "Verificar atualização"
                    button.setOnClickListener { check(false) }

                    if (!auto) {
                        status.text = "Status: não foi possível verificar atualização."
                    }
                }
            }
        }.start()
    }

    private fun downloadUpdate(url: String, tag: String) {
        if (downloading) return
        downloading = true

        status.text = "Status: iniciando download " + tag + "…"
        button.text = "BAIXANDO… 0%"
        button.isEnabled = false

        Thread {
            try {
                val dir = File(activity.cacheDir, "updates")
                if (!dir.exists()) dir.mkdirs()

                val file = File(dir, "aviator-analyzer-" + tag + ".apk")
                if (file.exists()) file.delete()

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "AviatorAnalyzer-Android")
                connection.connect()

                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP " + connection.responseCode)
                }

                val total = connection.contentLengthLong
                var downloaded = 0L
                var lastPercent = -1

                connection.inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break

                            output.write(buffer, 0, read)
                            downloaded += read

                            if (total > 0) {
                                val percent = ((downloaded * 100L) / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    activity.runOnUiThread {
                                        button.text = "BAIXANDO… " + percent + "%"
                                        status.text = "Status: baixando atualização " + tag + "…"
                                    }
                                }
                            }
                        }
                    }
                }

                if (!file.exists() || file.length() < 1_000_000L) {
                    throw IllegalStateException("APK incompleto")
                }

                pendingFile = file
                downloading = false

                activity.runOnUiThread {
                    button.text = "INSTALAR ATUALIZAÇÃO"
                    button.isEnabled = true
                    status.text = "Status: download concluído. Abrindo instalador…"
                    installPendingUpdate()
                }
            } catch (e: Exception) {
                downloading = false
                activity.runOnUiThread {
                    button.text = "TENTAR NOVAMENTE"
                    button.isEnabled = true
                    status.text = "Status: falha no download. Toque para tentar novamente."
                    button.setOnClickListener { downloadUpdate(url, tag) }
                }
            }
        }.start()
    }

    private fun installPendingUpdate() {
        val file = pendingFile ?: return

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            status.text = "Status: permita instalar apps deste aplicativo uma vez."
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.packageName)
                )
            )
            return
        }

        installApk(file)
    }

    private fun installApk(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                activity,
                activity.packageName + ".fileprovider",
                file
            )

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
            status.text = "Status: não foi possível abrir o instalador."
            button.text = "INSTALAR ATUALIZAÇÃO"
            button.isEnabled = true
            button.setOnClickListener { installPendingUpdate() }
        }
    }
}
