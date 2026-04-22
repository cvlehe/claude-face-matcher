package com.example.facematcher

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

class FaceRecognizer(context: Context) {

    companion object {
        const val MODEL_FILE = "facenet_512.tflite"
        const val INPUT_SIZE = 160
        const val EMBEDDING_SIZE = 512
    }

    private val interpreter: Interpreter
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
            .apply { order(ByteOrder.nativeOrder()) }
    private val output = Array(1) { FloatArray(EMBEDDING_SIZE) }
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val floatPixels = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)

    init {
        val model = FileUtil.loadMappedFile(context, MODEL_FILE)
        val options = Interpreter.Options().apply { setNumThreads(4) }
        interpreter = Interpreter(model, options)
    }

    @Synchronized
    fun getEmbedding(faceBitmap: Bitmap): FloatArray {
        val resized = if (faceBitmap.width == INPUT_SIZE && faceBitmap.height == INPUT_SIZE) {
            faceBitmap
        } else {
            Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
        }

        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        var idx = 0
        var sum = 0f
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF).toFloat()
            val g = ((pixel shr 8) and 0xFF).toFloat()
            val b = (pixel and 0xFF).toFloat()
            floatPixels[idx++] = r
            floatPixels[idx++] = g
            floatPixels[idx++] = b
            sum += r + g + b
        }
        val n = floatPixels.size
        val mean = sum / n
        var sqSum = 0f
        for (v in floatPixels) {
            val d = v - mean
            sqSum += d * d
        }
        val std = max(sqrt(sqSum / n), 1f / sqrt(n.toFloat()))

        inputBuffer.rewind()
        for (v in floatPixels) {
            inputBuffer.putFloat((v - mean) / std)
        }

        interpreter.run(inputBuffer, output)

        val raw = output[0]
        var l2 = 0f
        for (v in raw) l2 += v * v
        val norm = sqrt(l2) + 1e-8f
        val normalized = FloatArray(EMBEDDING_SIZE)
        for (i in raw.indices) normalized[i] = raw[i] / norm
        return normalized
    }

    fun close() {
        interpreter.close()
    }
}
