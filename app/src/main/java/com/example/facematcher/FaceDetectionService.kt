package com.example.facematcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
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
        const val ACTION_START = "com.example.facematcher.START_SESSION"
        const val ACTION_STOP = "com.example.facematcher.STOP_SESSION"
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "face_detection"
        private const val OVERLAY_HIDE_DELAY_MS = 3000L
        private const val RECOGNITION_COOLDOWN_MS = 4000L

        /** True only while an explicitly started session exists in this process. */
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
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraPreview: Preview? = null
    private var faceAnalyzer: FaceAnalyzer? = null
    private var activeCamera: Camera? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private var activeCameraSelector: CameraSelector? = null
    private var activeLensFacing: Int? = null
    private var cameraStarting = false
    private var sessionStarted = false
    private var generation = 0
    private var lastFrameNanos = 0L
    private val cameraWatchdog = object : Runnable {
        override fun run() {
            if (!sessionStarted || imageAnalysis == null) return
            val ageMs = (SystemClock.elapsedRealtimeNanos() - lastFrameNanos) / 1_000_000
            if (ageMs > 5_000) {
                lastFaceResults = emptyList()
                onFaceResultsChanged?.invoke(emptyList())
                updateStatus(sessionStatus.copy(phase = SessionStatus.Phase.UNAVAILABLE,
                    camera = "No camera frames"))
            }
            mainHandler.postDelayed(this, 2_000)
        }
    }
    var sessionStatus = SessionStatus()
        private set
    var onSessionStatusChanged: ((SessionStatus) -> Unit)? = null

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
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        faceStorage = FaceStorage(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        faceRecognizer = try { FaceRecognizer(this) } catch (_: Exception) { null }
        if (BuildConfig.GEMINI_API_KEY.isNotBlank()) {
            nameDetector = NameDetector(this, BuildConfig.GEMINI_API_KEY, { name ->
                val embedding = lastFaceResults
                    .filter { it.suitableForEnrollment }
                    .maxByOrNull { it.bbox.width() * it.bbox.height() }
                    ?.embedding ?: return@NameDetector
                faceStorage.addFace(name, embedding)
                showOverlay("Remembered: $name")
            }) { listening, detail ->
                if (sessionStarted) updateStatus(sessionStatus.copy(
                    microphone = if (listening) "Active" else "Available",
                    speech = detail))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSession(); return START_NOT_STICKY }
            ACTION_START -> if (!sessionStarted) startSession()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun updateStatus(status: SessionStatus) {
        sessionStatus = status
        onSessionStatusChanged?.invoke(status)
        if (sessionStarted) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun startSession() {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted || !audioGranted) {
            updateStatus(SessionStatus(SessionStatus.Phase.UNAVAILABLE,
                if (cameraGranted) "Available" else "Permission needed",
                if (audioGranted) "Available" else "Permission needed", "Off"))
            stopSelf()
            return
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            updateStatus(SessionStatus(SessionStatus.Phase.UNAVAILABLE, "Unavailable", "Unavailable", "Off"))
            stopSelf()
            return
        }
        sessionStarted = true
        isRunning = true
        generation++
        updateStatus(SessionStatus(SessionStatus.Phase.STARTING, "Starting", "Available", "Starting"))
        startCamera()
        if (nameDetector == null) {
            updateStatus(sessionStatus.copy(speech = "Unavailable (Gemini key required by current build)"))
        } else {
            if (nameDetector?.start() == true) {
                updateStatus(sessionStatus.copy(speech = "Starting"))
                nameDetector?.resume()
            } else {
                updateStatus(sessionStatus.copy(speech = "Recognizer unavailable"))
            }
        }
    }

    private fun stopSession() {
        if (!sessionStarted) { stopSelf(); return }
        sessionStarted = false
        isRunning = false
        generation++
        nameDetector?.stop()
        mainHandler.removeCallbacks(cameraWatchdog)
        activeCamera?.cameraInfo?.cameraState?.removeObservers(this)
        cameraProvider?.unbindAll()
        imageAnalysis?.clearAnalyzer()
        faceAnalyzer?.close()
        faceAnalyzer = null
        activeCamera = null
        cameraProvider = null
        imageAnalysis = null
        cameraPreview = null
        activeCameraSelector = null
        activeLensFacing = null
        cameraStarting = false
        lastFaceResults = emptyList()
        mainHandler.removeCallbacks(hideOverlayRunnable)
        removeOverlay()
        updateStatus(SessionStatus())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        val stop = PendingIntent.getService(this, 1,
            Intent(this, FaceDetectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Face Matcher")
            .setContentText("Camera: ${sessionStatus.camera} · Speech: ${sessionStatus.speech}")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(tap)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }

    private fun startCamera() {
        if (cameraStarting || cameraPreview != null) return
        val recognizer = faceRecognizer ?: run {
            updateStatus(sessionStatus.copy(phase = SessionStatus.Phase.UNAVAILABLE, camera = "Model unavailable"))
            return
        }
        cameraStarting = true
        val requestedGeneration = generation
        val future = try { ProcessCameraProvider.getInstance(this) } catch (_: Exception) {
            cameraStarting = false
            updateStatus(sessionStatus.copy(phase = SessionStatus.Phase.UNAVAILABLE, camera = "Unavailable"))
            return
        }
        future.addListener({
            var pendingAnalyzer: FaceAnalyzer? = null
            try {
                if (!sessionStarted || generation != requestedGeneration) return@addListener
                val provider = future.get()
                val selector = cameraSelector(provider) ?: run {
                    updateStatus(sessionStatus.copy(phase = SessionStatus.Phase.UNAVAILABLE, camera = "No camera"))
                    return@addListener
                }
                val analyzer = FaceAnalyzer(recognizer, faceStorage, cameraExecutor, { results ->
                    mainHandler.post {
                        if (!sessionStarted || generation != requestedGeneration ||
                            results.firstOrNull()?.let {
                                SystemClock.elapsedRealtimeNanos() - it.timestampNanos > 2_000_000_000L
                            } == true) return@post
                        lastFrameNanos = SystemClock.elapsedRealtimeNanos()
                        if (sessionStatus.camera != "Active") {
                            updateStatus(sessionStatus.copy(phase = SessionStatus.Phase.ACTIVE, camera = "Active"))
                        }
                        lastFaceResults = results
                        onFaceResultsChanged?.invoke(results)
                        handleRecognitions(results)
                    }
                }, { detail ->
                    mainHandler.post {
                        if (!sessionStarted || generation != requestedGeneration) return@post
                        updateStatus(sessionStatus.copy(phase = SessionStatus.Phase.UNAVAILABLE, camera = detail))
                    }
                })
                pendingAnalyzer = analyzer
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { ia ->
                        ia.setAnalyzer(cameraExecutor, analyzer)
                    }
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewSurfaceProvider)
                }
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, selector, analysis, preview)
                cameraProvider = provider
                imageAnalysis = analysis
                cameraPreview = preview
                faceAnalyzer = analyzer
                pendingAnalyzer = null
                activeCamera = camera
                observeCamera(camera)
                activeCameraSelector = selector
                activeLensFacing = when (selector) {
                    CameraSelector.DEFAULT_BACK_CAMERA -> CameraSelector.LENS_FACING_BACK
                    CameraSelector.DEFAULT_FRONT_CAMERA -> CameraSelector.LENS_FACING_FRONT
                    else -> null
                }
                lastFrameNanos = SystemClock.elapsedRealtimeNanos()
                mainHandler.removeCallbacks(cameraWatchdog)
                mainHandler.postDelayed(cameraWatchdog, 2_000)
                updateStatus(sessionStatus.copy(phase = SessionStatus.Phase.STARTING, camera = "Waiting for frames"))
            } catch (_: Exception) {
                pendingAnalyzer?.close()
                imageAnalysis?.clearAnalyzer()
                faceAnalyzer?.close()
                faceAnalyzer = null
                updateStatus(sessionStatus.copy(phase = SessionStatus.Phase.UNAVAILABLE, camera = "Camera bind failed"))
            } finally {
                cameraStarting = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    fun attachPreviewSurface(surfaceProvider: Preview.SurfaceProvider) {
        previewSurfaceProvider = surfaceProvider
        cameraPreview?.setSurfaceProvider(surfaceProvider)
    }

    fun detachPreviewSurface(surfaceProvider: Preview.SurfaceProvider) {
        if (previewSurfaceProvider !== surfaceProvider) return
        previewSurfaceProvider = null
        cameraPreview?.setSurfaceProvider(null)
    }

    /** Switches the camera used by both live analysis and the visible preview. */
    fun switchCamera(): Boolean {
        val provider = cameraProvider ?: return false
        val analysis = imageAnalysis ?: return false
        val preview = cameraPreview ?: return false
        val oldSelector = activeCameraSelector ?: return false
        val nextSelector = when (activeLensFacing) {
            CameraSelector.LENS_FACING_BACK -> CameraSelector.DEFAULT_FRONT_CAMERA
            CameraSelector.LENS_FACING_FRONT -> CameraSelector.DEFAULT_BACK_CAMERA
            else -> return false
        }
        val hasOtherCamera = try { provider.hasCamera(nextSelector) } catch (_: Exception) { false }
        if (!hasOtherCamera) return false

        return try {
            activeCamera?.cameraInfo?.cameraState?.removeObservers(this)
            provider.unbind(analysis, preview)
            val camera = provider.bindToLifecycle(this, nextSelector, analysis, preview)
            activeCamera = camera
            observeCamera(camera)
            activeCameraSelector = nextSelector
            activeLensFacing = nextSelector.lensFacing
            lastFaceResults = emptyList()
            onFaceResultsChanged?.invoke(emptyList())
            lastFrameNanos = SystemClock.elapsedRealtimeNanos()
            updateStatus(sessionStatus.copy(phase = SessionStatus.Phase.STARTING, camera = "Waiting for frames"))
            true
        } catch (_: Exception) {
            try {
                val camera = provider.bindToLifecycle(this, oldSelector, analysis, preview)
                activeCamera = camera
                observeCamera(camera)
            } catch (_: Exception) {
                activeCamera = null
                updateStatus(sessionStatus.copy(phase = SessionStatus.Phase.UNAVAILABLE, camera = "Camera bind failed"))
            }
            false
        }
    }

    private fun observeCamera(camera: Camera) {
        camera.cameraInfo.cameraState.observe(this) { state ->
            if (sessionStarted && activeCamera === camera && state.error != null) {
                lastFaceResults = emptyList()
                onFaceResultsChanged?.invoke(emptyList())
                updateStatus(sessionStatus.copy(phase = SessionStatus.Phase.UNAVAILABLE,
                    camera = "Camera interrupted"))
            }
        }
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
        if (sessionStarted) stopSession()
        super.onDestroy()
        isRunning = false
        mainHandler.removeCallbacks(hideOverlayRunnable)
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        faceRecognizer?.close()
        removeOverlay()
    }
}
