package com.example.facematcher

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var isBound = false
    private var boundService: FaceDetectionService? = null

    private val isServiceRunning = mutableStateOf(false)
    private val faceResults = mutableStateListOf<FaceAnalyzer.FaceResult>()
    private val hasCameraPermission = mutableStateOf(false)
    private val hasOverlayPermission = mutableStateOf(false)
    private val hasNotificationPermission = mutableStateOf(false)
    private val hasAudioPermission = mutableStateOf(false)
    private val savedFaceCount = mutableIntStateOf(0)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as FaceDetectionService.LocalBinder).getService()
            boundService = svc
            isBound = true
            isServiceRunning.value = true
            savedFaceCount.intValue = svc.faceStorage.size()
            svc.onFaceResultsChanged = { results ->
                faceResults.clear()
                faceResults.addAll(results)
                savedFaceCount.intValue = svc.faceStorage.size()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            boundService?.onFaceResultsChanged = null
            boundService = null
            isBound = false
        }
    }

    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var audioPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedFaceCount.intValue = FaceStorage(this).size()

        cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> hasCameraPermission.value = granted }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> hasNotificationPermission.value = granted }

        audioPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> hasAudioPermission.value = granted }

        refreshPermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ControlPanel(
                        isRunning = isServiceRunning.value,
                        hasCameraPermission = hasCameraPermission.value,
                        hasOverlayPermission = hasOverlayPermission.value,
                        hasNotificationPermission = hasNotificationPermission.value,
                        hasAudioPermission = hasAudioPermission.value,
                        faceResults = faceResults,
                        savedFaceCount = savedFaceCount.intValue,
                        onStartStop = { if (isServiceRunning.value) stopDetection() else startDetection() },
                        onClearFaces = {
                            boundService?.faceStorage?.clearAll()
                                ?: FaceStorage(this).clearAll()
                            savedFaceCount.intValue = 0
                        },
                        onAddFace = { name, embedding ->
                            boundService?.faceStorage?.addFace(name, embedding)
                            savedFaceCount.intValue = boundService?.faceStorage?.size()
                                ?: savedFaceCount.intValue
                        },
                        onRequestCamera = {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onRequestNotification = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onRequestOverlay = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        },
                        onRequestAudio = {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onAttachPreview = { boundService?.attachPreviewSurface(it) },
                        onDetachPreview = { boundService?.detachPreviewSurface() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshPermissions()
        if (FaceDetectionService.isRunning && !isBound) {
            bindService(Intent(this, FaceDetectionService::class.java), serviceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from system Settings
        hasOverlayPermission.value = Settings.canDrawOverlays(this)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            boundService?.onFaceResultsChanged = null
            unbindService(serviceConnection)
            isBound = false
            boundService = null
        }
    }

    private fun refreshPermissions() {
        hasCameraPermission.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        hasOverlayPermission.value = Settings.canDrawOverlays(this)
        hasNotificationPermission.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        hasAudioPermission.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startDetection() {
        val intent = Intent(this, FaceDetectionService::class.java)
        startForegroundService(intent)
        if (!isBound) bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        isServiceRunning.value = true
    }

    private fun stopDetection() {
        if (isBound) {
            boundService?.onFaceResultsChanged = null
            unbindService(serviceConnection)
            isBound = false
            boundService = null
        }
        stopService(Intent(this, FaceDetectionService::class.java))
        isServiceRunning.value = false
        faceResults.clear()
        savedFaceCount.intValue = FaceStorage(this).size()
    }
}

@Composable
fun ControlPanel(
    isRunning: Boolean,
    hasCameraPermission: Boolean,
    hasOverlayPermission: Boolean,
    hasNotificationPermission: Boolean,
    hasAudioPermission: Boolean,
    faceResults: List<FaceAnalyzer.FaceResult>,
    savedFaceCount: Int,
    onStartStop: () -> Unit,
    onAddFace: (name: String, embedding: FloatArray) -> Unit,
    onClearFaces: () -> Unit,
    onRequestCamera: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAudio: () -> Unit,
    onAttachPreview: (CameraPreview.SurfaceProvider) -> Unit = {},
    onDetachPreview: () -> Unit = {}
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var showCameraPreview by remember { mutableStateOf(false) }
    var previewFullscreen by remember { mutableStateOf(false) }

    // Hosted in movableContentOf so the same PreviewView instance (and its camera surface)
    // moves between the inline slot and the fullscreen overlay instead of being torn down
    // and reattached when toggling.
    val cameraPreviewContent = remember {
        movableContentOf { modifier: Modifier ->
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { onAttachPreview(it.surfaceProvider) }
                },
                onRelease = { onDetachPreview() },
                modifier = modifier
            )
        }
    }
    // Snapshot of the face taken when "Remember this face…" was tapped, so the still and
    // embedding stay consistent even as live results keep updating underneath.
    var capturedFace by remember { mutableStateOf<FaceAnalyzer.FaceResult?>(null) }

    val canStart = hasCameraPermission && hasOverlayPermission && hasNotificationPermission && hasAudioPermission
    val largestFace = faceResults.maxByOrNull { it.bbox.width() * it.bbox.height() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Face Matcher", style = MaterialTheme.typography.headlineMedium)

        HorizontalDivider()

        if (!hasCameraPermission) {
            PermissionRow("Camera access required", "Grant", onRequestCamera)
        }
        if (!hasNotificationPermission) {
            PermissionRow("Notification permission required", "Grant", onRequestNotification)
        }
        if (!hasAudioPermission) {
            PermissionRow("Microphone required for automatic name detection", "Grant", onRequestAudio)
        }
        if (!hasOverlayPermission) {
            PermissionRow("Overlay permission required for AR name display", "Grant", onRequestOverlay)
        }

        Text(
            text = if (isRunning) "Active — watching for faces" else "Stopped",
            color = if (isRunning) Color(0xFF4CAF50) else Color.Gray,
            style = MaterialTheme.typography.bodyLarge
        )
        Text("Saved faces: $savedFaceCount", fontSize = 14.sp, color = Color.Gray)

        if (isRunning) {
            val names = faceResults.mapNotNull { it.matchName }
            val unrecognizedCount = faceResults.count { it.matchName == null }
            Text(
                text = "In frame: ${faceResults.size}" +
                    if (names.isNotEmpty()) " · Recognized: ${names.joinToString(", ")}" else "",
                fontSize = 14.sp,
                color = Color.Gray
            )
            Text(
                text = "Unrecognized faces: $unrecognizedCount",
                fontSize = 14.sp,
                color = if (unrecognizedCount > 0) Color(0xFFFF9800) else Color.Gray
            )
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = onStartStop,
            enabled = canStart || isRunning,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFFB00020) else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (isRunning) "Stop" else "Start")
        }

        OutlinedButton(
            onClick = { capturedFace = largestFace },
            enabled = isRunning && largestFace != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    !isRunning -> "Start detection to add a face"
                    largestFace == null -> "No face in frame"
                    else -> "Remember this face…"
                }
            )
        }

        OutlinedButton(
            onClick = {
                showCameraPreview = !showCameraPreview
                if (!showCameraPreview) previewFullscreen = false
            },
            enabled = isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    !isRunning -> "Start detection to view camera"
                    showCameraPreview -> "Hide camera preview"
                    else -> "Show camera preview"
                }
            )
        }

        if (isRunning && showCameraPreview && !previewFullscreen) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                cameraPreviewContent(Modifier.fillMaxSize())
                TextButton(
                    onClick = { previewFullscreen = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color(0x99000000), RoundedCornerShape(8.dp))
                ) {
                    Text("Full screen", color = Color.White)
                }
            }
        }

        OutlinedButton(
            onClick = { showClearDialog = true },
            enabled = savedFaceCount > 0,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color(0xFFB00020)
            )
        ) {
            Text("Delete all saved faces")
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Delete all faces?") },
            text = { Text("This will permanently remove all $savedFaceCount saved face${if (savedFaceCount == 1) "" else "s"}. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearFaces()
                    showClearDialog = false
                }) { Text("Delete", color = Color(0xFFB00020)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (isRunning && showCameraPreview && previewFullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            cameraPreviewContent(Modifier.fillMaxSize())
            TextButton(
                onClick = { previewFullscreen = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp, 50.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
            ) {
                Text("Exit full screen", color = Color.White)
            }
        }
    }

    capturedFace?.let { face ->
        AddFaceScreen(
            face = face,
            onSubmit = { name ->
                onAddFace(name, face.embedding)
                capturedFace = null
            },
            onCancel = { capturedFace = null }
        )
    }
}

@Composable
fun AddFaceScreen(
    face: FaceAnalyzer.FaceResult,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp, 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Remember face", style = MaterialTheme.typography.headlineMedium)

            Image(
                bitmap = face.faceBitmap.asImageBitmap(),
                contentDescription = "Captured face",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { onSubmit(name.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun PermissionRow(label: String, buttonLabel: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFFFF9800))
        TextButton(onClick = onClick) { Text(buttonLabel) }
    }
}

@Preview(showBackground = true, name = "Running — face recognized")
@Composable
private fun PreviewControlPanelRunning() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            ControlPanel(
                isRunning = true,
                hasCameraPermission = true,
                hasOverlayPermission = true,
                hasNotificationPermission = true,
                hasAudioPermission = true,
                faceResults = emptyList(),
                savedFaceCount = 3,
                onStartStop = {}, onAddFace = { _, _ -> }, onClearFaces = {},
                onRequestCamera = {}, onRequestNotification = {},
                onRequestOverlay = {}, onRequestAudio = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "Stopped — missing permissions")
@Composable
private fun PreviewControlPanelStopped() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            ControlPanel(
                isRunning = false,
                hasCameraPermission = false,
                hasOverlayPermission = false,
                hasNotificationPermission = false,
                hasAudioPermission = false,
                faceResults = emptyList(),
                savedFaceCount = 0,
                onStartStop = {}, onAddFace = { _, _ -> }, onClearFaces = {},
                onRequestCamera = {}, onRequestNotification = {},
                onRequestOverlay = {}, onRequestAudio = {}
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPermissionRow() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        PermissionRow("Camera access required", "Grant", {})
    }
}
