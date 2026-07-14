package com.example.facematcher

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

class FaceAnalyzer(
    private val recognizer: FaceRecognizer,
    private val storage: FaceStorage,
    private val onResult: (List<FaceResult>) -> Unit
) : ImageAnalysis.Analyzer {

    data class FaceResult(
        val bbox: Rect,
        val embedding: FloatArray,
        val matchName: String?,
        val matchDistance: Float,
        val faceBitmap: Bitmap
    )

    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            // Low floor so faces on a screen or across a room are still detected;
            // 0.15 required a face to span 15% of the frame width.
            .setMinFaceSize(0.08f)
            .build()
    )

    @Volatile
    private var processing = false
    private var lastAnalysisMs = 0L
    private val minIntervalMs = 500L

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (processing || now - lastAnalysisMs < minIntervalMs) {
            imageProxy.close()
            return
        }
        processing = true
        lastAnalysisMs = now

        val rotation = imageProxy.imageInfo.rotationDegrees
        val bitmap: Bitmap = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            imageProxy.close()
            processing = false
            onResult(emptyList())
            return
        }
        imageProxy.close()

        val rotated = rotateBitmap(bitmap, rotation)
        val inputImage = InputImage.fromBitmap(rotated, 0)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                val results = faces.mapNotNull { face ->
                    val clamped = clampRect(face.boundingBox, rotated.width, rotated.height)
                    if (clamped.width() < 16 || clamped.height() < 16) return@mapNotNull null

                    val cropped = try {
                        Bitmap.createBitmap(rotated, clamped.left, clamped.top, clamped.width(), clamped.height())
                    } catch (e: Exception) {
                        return@mapNotNull null
                    }

                    val embedding = recognizer.getEmbedding(cropped)
                    val match = storage.findBestMatch(embedding)
                    FaceResult(
                        bbox = clamped,
                        embedding = embedding,
                        matchName = match?.first,
                        matchDistance = match?.second ?: Float.MAX_VALUE,
                        faceBitmap = cropped
                    )
                }
                onResult(results)
            }
            .addOnFailureListener {
                onResult(emptyList())
            }
            .addOnCompleteListener {
                processing = false
            }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun clampRect(r: Rect, w: Int, h: Int): Rect {
        return Rect(
            r.left.coerceIn(0, w),
            r.top.coerceIn(0, h),
            r.right.coerceIn(0, w),
            r.bottom.coerceIn(0, h)
        )
    }
}
