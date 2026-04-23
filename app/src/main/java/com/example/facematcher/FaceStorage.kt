package com.example.facematcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

class FaceStorage(context: Context) {

    companion object {
        const val FILE_NAME = "faces.json"
        const val MATCH_THRESHOLD = 0.8f
    }

    data class StoredFace(val name: String, val embedding: FloatArray)

    private val file = File(context.filesDir, FILE_NAME)
    private val faces: MutableList<StoredFace> = loadFromDisk().toMutableList()

    @Synchronized
    fun addFace(name: String, embedding: FloatArray) {
        faces.add(StoredFace(name, embedding.copyOf()))
        saveToDisk()
    }

    @Synchronized
    fun findBestMatch(embedding: FloatArray): Pair<String, Float>? {
        var bestName: String? = null
        var bestDist = Float.MAX_VALUE
        for (f in faces) {
            val d = l2Distance(embedding, f.embedding)
            if (d < bestDist) {
                bestDist = d
                bestName = f.name
            }
        }
        return if (bestName != null && bestDist < MATCH_THRESHOLD) bestName to bestDist else null
    }

    @Synchronized
    fun size(): Int = faces.size

    @Synchronized
    fun clearAll() {
        faces.clear()
        file.delete()
    }

    private fun l2Distance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum.toDouble()).toFloat()
    }

    private fun loadFromDisk(): List<StoredFace> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val result = mutableListOf<StoredFace>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val embArr = obj.getJSONArray("embedding")
                val emb = FloatArray(embArr.length()) { embArr.getDouble(it).toFloat() }
                result.add(StoredFace(name, emb))
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveToDisk() {
        val arr = JSONArray()
        for (f in faces) {
            val obj = JSONObject()
            obj.put("name", f.name)
            val embArr = JSONArray()
            for (v in f.embedding) embArr.put(v.toDouble())
            obj.put("embedding", embArr)
            arr.put(obj)
        }
        file.writeText(arr.toString())
    }
}
