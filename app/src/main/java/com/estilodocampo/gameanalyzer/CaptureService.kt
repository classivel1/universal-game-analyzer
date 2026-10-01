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
            val resultCode = intent.getIntExtra(
                EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED
            )

            val data = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }

            val profile = intent.getIntExtra(EXTRA_PROFILE, 0)

            startForeground(
                NOTIF_ID,
                notification("Iniciando captura…")
            )

            startCapture(resultCode, data, profile)
        }

        return START_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        data: Intent?,
        profile: Int
    ) {
        if (data == null) return

        analyzer = Analyzer(profile)

        val manager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        projection = manager.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val width = 540
        val height =
            (width * metrics.heightPixels.toDouble() / metrics.widthPixels)
                .toInt()
                .coerceAtLeast(720)

        reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        projection?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            },
            android.os.Handler(mainLooper)
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "GameAnalyzer",
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

            if (now - lastFrame < 450) {
                imageReader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            lastFrame = now

            val image =
                imageReader.acquireLatestImage()
                    ?: return@setOnImageAvailableListener

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

                val cropped =
                    Bitmap.createBitmap(bitmap, 0, 0, width, height)

                analyzer?.process(cropped)?.let { analysis ->
                    updateOverlay(analysis)
                    updateNotification(analysis)
                }

                bitmap.recycle()
                cropped.recycle()
            } finally {
                image.close()
            }
        }, android.os.Handler(mainLooper))

        showOverlay("COLETANDO\nCapturando tela…")
    }

    private fun showOverlay(text: String) {
        if (!Settings.canDrawOverlays(this)) return

        if (overlay == null) {
            windowManager =
                getSystemService(WINDOW_SERVICE) as WindowManager

            overlay = TextView(this).apply {
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(0xCC111827.toInt())
                textSize = 15f
                setPadding(24, 16, 24, 16)
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

                    override fun onTouch(
                        v: View,
                        event: MotionEvent
                    ): Boolean {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                startX = event.rawX
                                startY = event.rawY
                                originX = params.x
                                originY = params.y
                            }

                            MotionEvent.ACTION_MOVE -> {
                                params.x =
                                    originX + (event.rawX - startX).toInt()
                                params.y =
                                    originY + (event.rawY - startY).toInt()

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
        showOverlay(
            "${result.label}\n" +
                "Evidência ${result.score}/100 · " +
                "Contextos ${result.matches} · " +
                "Rodadas ${result.rounds}\n" +
                "Estado válido para o próximo giro"
        )
    }

    private fun updateNotification(result: Analyzer.Result) {
        val manager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        manager.notify(
            NOTIF_ID,
            notification(
                "${result.label} · Evidência ${result.score}/100"
            )
        )
    }

    private fun notification(text: String) =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Game Analyzer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Captura e análise",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onDestroy() {
        reader?.close()
        virtualDisplay?.release()
        projection?.stop()

        overlay?.let {
            runCatching {
                windowManager?.removeView(it)
            }
        }

        super.onDestroy()
    }
}
