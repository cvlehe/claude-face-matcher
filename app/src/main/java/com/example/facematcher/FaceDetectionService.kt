package com.example.facematcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceDetectionService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "face_detection"
        private const val OVERLAY_HIDE_DELAY_MS = 3000L
        private const val RECOGNITION_COOLDOWN_MS = 4000L

        /** True while the service is alive; used by Activity to decide whether to bind. */
        var isRunning = false
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService() = this@FaceDetectionService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var cameraExecutor: ExecutorService

    private var faceRecognizer: FaceRecognizer? = null
    lateinit var faceStorage: FaceStorage
    private var nameDetector: NameDetector? = null
    private var cameraPreview: Preview? = null

    private var overlayView: TextView? = null
    private val recentRecognitions = mutableMapOf<String, Long>()
    private val hideOverlayRunnable = Runnable { removeOverlay() }

    @Volatile
    var lastFaceResults: List<FaceAnalyzer.FaceResult> = emptyList()
        private set

    /** Activity subscribes to get live results while in foreground. */
    var onFaceResultsChanged: ((List<FaceAnalyzer.FaceResult>) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        faceStorage = FaceStorage(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        faceRecognizer = try { FaceRecognizer(this) } catch (_: Exception) { null }
        if (BuildConfig.GEMINI_API_KEY.isNotBlank()) {
            nameDetector = NameDetector(this, BuildConfig.GEMINI_API_KEY) { name ->
                val embedding = lastFaceResults
                    .maxByOrNull { it.bbox.width() * it.bbox.height() }
                    ?.embedding ?: return@NameDetector
                faceStorage.addFace(name, embedding)
                showOverlay("Remembered: $name")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(
            NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        startCamera()
        nameDetector?.start()
        nameDetector?.resume()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Face Detection", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Face Matcher")
            .setContentText("Watching for familiar faces…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    private fun startCamera() {
        val recognizer = faceRecognizer ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = cameraSelector(provider) ?: return@addListener
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { ia ->
                    ia.setAnalyzer(cameraExecutor, FaceAnalyzer(recognizer, faceStorage) { results ->
                        lastFaceResults = results
                        mainHandler.post {
                            onFaceResultsChanged?.invoke(results)
                            handleRecognitions(results)
                        }
                    })
                }
            val preview = Preview.Builder().build().also { cameraPreview = it }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, imageAnalysis, preview)
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    fun attachPreviewSurface(surfaceProvider: Preview.SurfaceProvider) {
        cameraPreview?.setSurfaceProvider(surfaceProvider)
    }

    fun detachPreviewSurface() {
        cameraPreview?.setSurfaceProvider(null)
    }

    // XR emulator cameras report no lens-facing metadata, so fall back to the
    // first available camera rather than requiring DEFAULT_BACK_CAMERA.
    private fun cameraSelector(provider: ProcessCameraProvider): CameraSelector? = when {
        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
        provider.availableCameraInfos.isNotEmpty() -> CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { it == provider.availableCameraInfos.first() } }
            .build()
        else -> null
    }

    private fun handleRecognitions(results: List<FaceAnalyzer.FaceResult>) {
        val now = System.currentTimeMillis()
        val newNames = results.mapNotNull { r ->
            val name = r.matchName ?: return@mapNotNull null
            val last = recentRecognitions[name] ?: 0L
            if (now - last > RECOGNITION_COOLDOWN_MS) {
                recentRecognitions[name] = now
                name
            } else null
        }
        if (newNames.isNotEmpty()) showOverlay(newNames.joinToString(" · "))

    }

    private fun showOverlay(text: String) {
        if (!Settings.canDrawOverlays(this)) return
        mainHandler.removeCallbacks(hideOverlayRunnable)

        if (overlayView == null) {
            overlayView = TextView(this).apply {
                setTextColor(android.graphics.Color.WHITE)
                textSize = 32f
                setPadding(60, 28, 60, 28)
                setBackgroundColor(0xCC000000.toInt())
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 140
            }
            windowManager.addView(overlayView, params)
        }

        overlayView?.text = text
        mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        mainHandler.removeCallbacks(hideOverlayRunnable)
        nameDetector?.stop()
        cameraExecutor.shutdown()
        faceRecognizer?.close()
        removeOverlay()
    }
}
