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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                        onAddFace = { name ->
                            val emb = faceResults
                                .maxByOrNull { it.bbox.width() * it.bbox.height() }
                                ?.embedding ?: return@ControlPanel
                            boundService?.faceStorage?.addFace(name, emb)
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
                        }
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
    onAddFace: (String) -> Unit,
    onClearFaces: () -> Unit,
    onRequestCamera: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAudio: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var dialogName by remember { mutableStateOf("") }

    val canStart = hasCameraPermission && hasOverlayPermission && hasNotificationPermission && hasAudioPermission
    val largestFace = faceResults.maxByOrNull { it.bbox.width() * it.bbox.height() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
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

        HorizontalDivider()

        Text(
            text = if (isRunning) "Active — watching for faces" else "Stopped",
            color = if (isRunning) Color(0xFF4CAF50) else Color.Gray,
            style = MaterialTheme.typography.bodyLarge
        )
        Text("Saved faces: $savedFaceCount", fontSize = 14.sp, color = Color.Gray)

        if (isRunning) {
            val names = faceResults.mapNotNull { it.matchName }
            Text(
                text = "In frame: ${faceResults.size}" +
                    if (names.isNotEmpty()) " · Recognized: ${names.joinToString(", ")}" else "",
                fontSize = 14.sp,
                color = Color.Gray
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
            onClick = { dialogName = ""; showAddDialog = true },
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

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Remember face") },
            text = {
                Column {
                    Text("Enter the name of the person currently in frame.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = dialogName,
                        onValueChange = { dialogName = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = dialogName.trim()
                    if (name.isNotEmpty()) {
                        onAddFace(name)
                        showAddDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
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
