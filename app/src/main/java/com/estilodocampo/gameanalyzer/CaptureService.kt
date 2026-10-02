package com.estilodocampo.gameanalyzer

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_PROFILE = "profile"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 9870
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var analyzer: Analyzer? = null
    private var overlay: TextView? = null
    private var windowManager: WindowManager? = null
    private var lastFrame = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }

            startForeground(NOTIF_ID, notification("Iniciando leitura do Aviator…"))
            startCapture(resultCode, data)
        }
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent?) {
        if (data == null) return

        analyzer?.close()
        analyzer = Analyzer()

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val width = 540
        val height = (width * metrics.heightPixels.toDouble() / metrics.widthPixels)
            .toInt().coerceAtLeast(720)

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        projection?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            },
            android.os.Handler(mainLooper)
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "AviatorAnalyzer",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            null
        )

        reader?.setOnImageAvailableListener({ imageReader ->
            val now = System.currentTimeMillis()
            if (now - lastFrame < 900) {
                imageReader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            lastFrame = now
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val padding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + padding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)

                analyzer?.process(cropped) { result ->
                    if (result != null) {
                        updateOverlay(result)
                        updateNotification(result)
                    }
                    runCatching { cropped.recycle() }
                    runCatching { bitmap.recycle() }
                }
            } finally {
                image.close()
            }
        }, android.os.Handler(mainLooper))

        showOverlay(
            "AVIATOR ANALYZER\n" +
                "Lendo histórico de multiplicadores…\n" +
                "Aguarde novas rodadas."
        )
    }

    private fun showOverlay(text: String) {
        if (!Settings.canDrawOverlays(this)) return

        if (overlay == null) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            overlay = TextView(this).apply {
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(0xDD111827.toInt())
                textSize = 14f
                setPadding(22, 14, 22, 14)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 20
                y = 120
            }

            overlay?.setOnTouchListener(
                object : View.OnTouchListener {
                    private var startX = 0f
                    private var startY = 0f
                    private var originX = 0
                    private var originY = 0

                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                startX = event.rawX
                                startY = event.rawY
                                originX = params.x
                                originY = params.y
                            }
                            MotionEvent.ACTION_MOVE -> {
                                params.x = originX + (event.rawX - startX).toInt()
                                params.y = originY + (event.rawY - startY).toInt()
                                windowManager?.updateViewLayout(v, params)
                            }
                        }
                        return true
                    }
                }
            )

            windowManager?.addView(overlay, params)
        }

        overlay?.text = text
    }

    private fun updateOverlay(result: Analyzer.Result) {
        val last = "%.2f".format(result.latest)
        val sampleSize = result.last20.size

        showOverlay(
            "AVIATOR · HISTÓRICO\n" +
                "Último lido: ${last}x · Rodadas ${result.rounds}\n" +
                "Últ. $sampleSize: <2x ${result.under2Pct}% · 2x+ ${result.over2Pct}%\n" +
                "3x+ ${result.over3Pct}% · 5x+ ${result.over5Pct}% · 10x+ ${result.over10Pct}%\n" +
                "Sequência <2x: ${result.lowStreak} · Volatilidade: ${result.volatility}\n" +
                "Estatística do histórico; não prevê a próxima rodada."
        )
    }

    private fun updateNotification(result: Analyzer.Result) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIF_ID,
            notification(
                "Rodadas ${result.rounds} · <2x ${result.under2Pct}% · " +
                    "volatilidade ${result.volatility}"
            )
        )
    }

    private fun notification(text: String) =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Aviator Analyzer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Leitura do Aviator",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onDestroy() {
        reader?.close()
        virtualDisplay?.release()
        projection?.stop()
        analyzer?.close()

        overlay?.let {
            runCatching { windowManager?.removeView(it) }
        }

        super.onDestroy()
    }
}
