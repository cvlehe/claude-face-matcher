package com.example.facematcher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.facematcher.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var faceRecognizer: FaceRecognizer? = null
    private lateinit var faceStorage: FaceStorage

    @Volatile
    private var lastFaceResults: List<FaceAnalyzer.FaceResult> = emptyList()

    private val recentToasts = mutableMapOf<String, Long>()
    private val toastCooldownMs = 3000L

    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        faceStorage = FaceStorage(this)

        try {
            faceRecognizer = FaceRecognizer(this)
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Face model missing")
                .setMessage(
                    "Could not load ${FaceRecognizer.MODEL_FILE} from assets.\n\n" +
                        "Place a FaceNet-512 TFLite model at:\n" +
                        "app/src/main/assets/${FaceRecognizer.MODEL_FILE}\n\n" +
                        "Expected input: ${FaceRecognizer.INPUT_SIZE}x${FaceRecognizer.INPUT_SIZE}x3, " +
                        "output: ${FaceRecognizer.EMBEDDING_SIZE}-d embedding.\n\n" +
                        "Error: ${e.message}"
                )
                .setCancelable(false)
                .setPositiveButton("Exit") { _, _ -> finish() }
                .show()
            return
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.addFaceButton.setOnClickListener { onAddFaceClicked() }
        binding.flipCameraButton.setOnClickListener { flipCamera() }

        updateStatus(emptyList())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val recognizer = faceRecognizer ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analyzer = FaceAnalyzer(recognizer, faceStorage) { results ->
                onFaceResults(results)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, analyzer) }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun flipCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera()
    }

    private fun onFaceResults(results: List<FaceAnalyzer.FaceResult>) {
        lastFaceResults = results
        runOnUiThread {
            val now = System.currentTimeMillis()
            for (r in results) {
                val name = r.matchName ?: continue
                val last = recentToasts[name] ?: 0L
                if (now - last > toastCooldownMs) {
                    recentToasts[name] = now
                    Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
                }
            }
            updateStatus(results)
        }
    }

    private fun updateStatus(results: List<FaceAnalyzer.FaceResult>) {
        val count = results.size
        val saved = faceStorage.size()
        val matches = results.mapNotNull { it.matchName }
        val matchText = if (matches.isNotEmpty()) " | Seen: ${matches.joinToString(", ")}" else ""
        binding.statusText.text = "In frame: $count | Saved: $saved$matchText"
    }

    private fun onAddFaceClicked() {
        val results = lastFaceResults
        if (results.isEmpty()) {
            Toast.makeText(
                this,
                "No face detected. Point the camera at a face and try again.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val face = results.maxBy { it.bbox.width() * it.bbox.height() }
        val embedding = face.embedding

        val input = EditText(this).apply {
            hint = "Name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this)
            .setTitle("Save face")
            .setMessage("Enter the name of the person in the frame.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Name cannot be empty.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                faceStorage.addFace(name, embedding)
                Toast.makeText(this, "Saved: $name", Toast.LENGTH_SHORT).show()
                updateStatus(lastFaceResults)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        faceRecognizer?.close()
    }
}
