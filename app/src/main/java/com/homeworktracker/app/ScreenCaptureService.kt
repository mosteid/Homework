package com.homeworktracker.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * خدمة مشاركة الشاشة في الخلفية (Foreground Service - MediaProjection)
 * تقوم بالتقاط إطارات الشاشة أثناء تصفح واتساب وقراءة أسماء الطلاب وإظهار شريط تحكم عائم.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.homeworktracker.app.ACTION_START_SCAN"
        const val ACTION_STOP = "com.homeworktracker.app.ACTION_STOP_SCAN"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_STUDENTS = "extra_students"
        const val EXTRA_CLASS_NAME = "extra_class_name"

        private const val CHANNEL_ID = "homework_screen_scan_channel"
        private const val NOTIF_ID = 4041
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isProcessingFrame = AtomicBoolean(false)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var ocrProcessor: ArabicOcrProcessor? = null
    private var scanJob: Job? = null

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var tvOverlayStatus: TextView? = null
    private var tvOverlayCount: TextView? = null

    private var activeStudents: List<String> = emptyList()
    private var activeClassName: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCaptureAndReturnToApp()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                activeStudents = intent.getStringArrayListExtra(EXTRA_STUDENTS) ?: emptyList()
                activeClassName = intent.getStringExtra(EXTRA_CLASS_NAME) ?: ""

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundWithNotification()
                startScreenCapture(resultCode, resultData)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        createNotificationChannel()

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            102,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("الفصل: $activeClassName — افتح واتساب ومرر الرسائل")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "إنهاء المسح والمراجعة", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startScreenCapture(resultCode: Int, resultData: Intent) {
        ScanResultBus.resetSession()
        showFloatingOverlayIfPermitted()

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            stopCaptureAndReturnToApp()
            return
        }
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCaptureAndReturnToApp()
            }
        }, mainHandler)

        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        // تقليص الدقة قليلاً لتسريع القراءة وتقليل استهلاك الذاكرة
        val scale = if (metrics.widthPixels > 1080) 0.5f else 0.75f
        val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(480)
        val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(800)
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "HomeworkWhatsAppScanner",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            mainHandler
        )

        ocrProcessor = ArabicOcrProcessor(applicationContext)

        scanJob = serviceScope.launch {
            updateStatus("جارٍ تجهيز محرك القراءة العربي...")
            val ready = ocrProcessor?.ensureInitialized { msg ->
                updateStatus(msg)
            } ?: false

            if (!ready) {
                delay(2000)
                withContext(Dispatchers.Main) { stopCaptureAndReturnToApp() }
                return@launch
            }

            updateStatus("مشاركة الشاشة نشطة ✓ افتح واتساب")

            while (isActive) {
                captureAndAnalyzeOnce()
                delay(2000)
            }
        }
    }

    private fun captureAndAnalyzeOnce() {
        if (!isProcessingFrame.compareAndSet(false, true)) return
        try {
            val image = imageReader?.acquireLatestImage() ?: return
            val bitmap: Bitmap? = try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val fullBitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                Bitmap.createBitmap(fullBitmap, 0, 0, image.width, image.height).also {
                    if (it != fullBitmap) fullBitmap.recycle()
                }
            } finally {
                image.close()
            }

            if (bitmap != null) {
                try {
                    val detected = ocrProcessor?.extractMatchingNames(bitmap, activeStudents) ?: emptyList()
                    var addedAny = false
                    for (name in detected) {
                        if (ScanResultBus.addDetectedName(name)) {
                            addedAny = true
                        }
                    }
                    if (addedAny) {
                        val total = ScanResultBus.getDetectedCount()
                        mainHandler.post {
                            tvOverlayCount?.text = "تم رصد: $total طالب"
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (_: Exception) {
        } finally {
            isProcessingFrame.set(false)
        }
    }

    private fun updateStatus(text: String) {
        ScanResultBus.notifyStatus(text)
        mainHandler.post {
            tvOverlayStatus?.text = "📡 $text"
        }
    }

    private fun showFloatingOverlayIfPermitted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }
        mainHandler.post {
            try {
                windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                val inflater = LayoutInflater.from(this)
                val view = inflater.inflate(R.layout.overlay_scanner_controls, null)

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 110
                }

                tvOverlayStatus = view.findViewById(R.id.tvOverlayStatus)
                tvOverlayCount = view.findViewById(R.id.tvOverlayCount)
                val btnStop = view.findViewById<TextView>(R.id.btnOverlayStop)
                btnStop.setOnClickListener {
                    stopCaptureAndReturnToApp()
                }

                // إمكانية سحب الشريط العائم لأي مكان على الشاشة
                var initialX = 0
                var initialY = 0
                var initialTouchX = 0f
                var initialTouchY = 0f
                view.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager?.updateViewLayout(view, params)
                            true
                        }
                        else -> false
                    }
                }

                overlayView = view
                windowManager?.addView(view, params)
            } catch (_: Exception) {
            }
        }
    }

    private fun removeFloatingOverlay() {
        mainHandler.post {
            try {
                overlayView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {
            } finally {
                overlayView = null
            }
        }
    }

    private fun stopCaptureAndReturnToApp() {
        scanJob?.cancel()
        scanJob = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        ocrProcessor?.release()
        ocrProcessor = null

        removeFloatingOverlay()
        ScanResultBus.notifyFinished()

        // العودة تلقائياً لتطبيق متابعة الواجبات لعرض شاشة المراجعة والاعتماد
        val bringToFront = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(bringToFront)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        removeFloatingOverlay()
        ocrProcessor?.release()
        super.onDestroy()
    }
}
