package com.example.facematcher

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executor
import kotlin.math.abs

class FaceAnalyzer(
    private val recognizer: FaceRecognizer,
    private val storage: FaceStorage,
    private val executor: Executor,
    private val onResult: (List<FaceResult>) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer {
    enum class Quality { SUITABLE, TOO_SMALL, CUT_OFF, POSE, BLURRED }

    data class FaceResult(
        val timestampNanos: Long,
        val frameWidth: Int,
        val frameHeight: Int,
        val bbox: Rect,
        val trackingId: Int?,
        val quality: Quality,
        val embedding: FloatArray?,
        val matchName: String?,
        val matchDistance: Float
    ) {
        val suitableForEnrollment get() = quality == Quality.SUITABLE && embedding != null
    }

    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.1f)
            .enableTracking()
            .build()
    )
    @Volatile private var processing = false
    private var lastAnalysisNanos = 0L
    private val minIntervalNanos = 250_000_000L

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (processing || now - lastAnalysisNanos < minIntervalNanos) {
            imageProxy.close()
            return
        }
        processing = true
        lastAnalysisNanos = now
        val bitmap = try { imageProxy.toBitmap() } catch (_: Exception) {
            imageProxy.close()
            processing = false
            onError("Frame conversion failed")
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()
        val rotated = try { rotateBitmap(bitmap, rotation) } catch (_: Exception) {
            processing = false
            onError("Frame rotation failed")
            return
        }
        faceDetector.process(InputImage.fromBitmap(rotated, 0))
            .addOnSuccessListener(executor) { faces ->
                try {
                    onResult(faces.map { face ->
                        val box = clampRect(face.boundingBox, rotated.width, rotated.height)
                        val quality = assessQuality(rotated, box, face.headEulerAngleY, face.headEulerAngleZ)
                        val embedding = if (quality == Quality.SUITABLE) {
                            val crop = Bitmap.createBitmap(rotated, box.left, box.top, box.width(), box.height())
                            recognizer.getEmbedding(crop)
                        } else null
                        val match = embedding?.let(storage::findBestMatch)
                        FaceResult(now, rotated.width, rotated.height, box, face.trackingId,
                            quality, embedding, match?.first, match?.second ?: Float.MAX_VALUE)
                    })
                } catch (_: Exception) { onError("Face analysis failed") }
            }
            .addOnFailureListener(executor) { onError("Face detector failed") }
            .addOnCompleteListener(executor) { processing = false }
    }

    private fun assessQuality(bitmap: Bitmap, box: Rect, yaw: Float, roll: Float): Quality {
        if (box.width() < 80 || box.height() < 80 ||
            box.width() < bitmap.width * 0.12f || box.height() < bitmap.height * 0.12f) return Quality.TOO_SMALL
        if (box.left < 4 || box.top < 4 ||
            box.right > bitmap.width - 4 || box.bottom > bitmap.height - 4) return Quality.CUT_OFF
        if (abs(yaw) > 25f || abs(roll) > 25f) return Quality.POSE
        if (laplacianVariance(bitmap, box) < 60.0) return Quality.BLURRED
        return Quality.SUITABLE
    }

    // Sample the center of the face; low Laplacian variance indicates little edge detail.
    private fun laplacianVariance(bitmap: Bitmap, box: Rect): Double {
        val side = 32
        val gray = IntArray(side * side)
        val left = box.left + box.width() / 6
        val top = box.top + box.height() / 6
        val width = box.width() * 2 / 3
        val height = box.height() * 2 / 3
        for (y in 0 until side) for (x in 0 until side) {
            val pixel = bitmap.getPixel(left + x * width / side, top + y * height / side)
            gray[y * side + x] = (((pixel shr 16) and 255) * 77 +
                ((pixel shr 8) and 255) * 150 + (pixel and 255) * 29) shr 8
        }
        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        for (y in 1 until side - 1) for (x in 1 until side - 1) {
            val i = y * side + x
            val edge = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - side] - gray[i + side]
            sum += edge
            sumSquares += edge.toDouble() * edge
            count++
        }
        return sumSquares / count - (sum / count) * (sum / count)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun clampRect(r: Rect, w: Int, h: Int): Rect = Rect(
        r.left.coerceIn(0, w), r.top.coerceIn(0, h),
        r.right.coerceIn(0, w), r.bottom.coerceIn(0, h)
    )

    fun close() = faceDetector.close()
}
