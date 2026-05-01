package com.example.facematcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.properties.Delegates

/**
 * Listens to ambient conversation and extracts a person's name when they introduce
 * themselves. Two detection paths:
 *
 * 1. Regex — fast, offline: "I'm Joe", "My name is Joe", "Call me Joe", etc.
 * 2. Gemini — context-aware: short reply after the user asked "What's your name?"
 */
class NameDetector(
    private val context: Context,
    private val geminiApiKey: String,
    private val onNameDetected: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isActive = false
    private var isListening: Boolean by Delegates.observable(false) { prop, old, newValue ->
        println("cv-isListening: $newValue")
    }

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // Prevents duplicate name callbacks when a partial result already triggered detection.
    private var detectedThisSession = false

    private var previousPhrase: String? = null;


    private val introRegex = Regex(
        """(?:i(?:'m| am)|my name(?:'s| is)|name(?:'s| is)|call me|they call me)\s+([A-Za-z]+)""",
        RegexOption.IGNORE_CASE
    )
    private val nameQuestionRegex = Regex(
        """(?:what(?:'s| is) your name|your name|name again|who are you|remind me)""",
        RegexOption.IGNORE_CASE
    )

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        isActive = true
    }

    fun resume() {
        if (!isActive || isListening) return
        isListening = true
        listen()
    }

    fun pause() {
        isListening = false
        speechRecognizer?.stopListening()
    }

    private fun destroyRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    fun stop() {
        isActive = false
        job.cancel()
        pause()
        destroyRecognizer()
    }

    private val listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
    }

    private fun listen(recreate: Boolean = false) {
        detectedThisSession = false
        if (recreate || speechRecognizer == null) {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(recognitionListener)
            }
        }
        speechRecognizer?.startListening(listenIntent)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            println("cv-results: ${results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)}")
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.joinToString(". ").orEmpty()
            if (text.isNotBlank()) processPhrase(text)
            if (isListening) mainHandler.post { listen() }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            println("cv-partialResults: ${partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)}")

        }

        override fun onError(error: Int) {
            val recreate: Boolean
            val retryDelayMs: Long
            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { recreate = false; retryDelayMs = 2000L }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> return
                SpeechRecognizer.ERROR_NO_MATCH -> { recreate = false; retryDelayMs = 300L }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> { recreate = true; retryDelayMs = 1000L }
                else -> { recreate = true; retryDelayMs = 1000L }
            }
            if (isListening) mainHandler.postDelayed({ listen(recreate) }, retryDelayMs)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onEndOfSpeech() {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processPhrase(text: String) {
        if (detectedThisSession) return

        // Fast path: direct introduction pattern
        introRegex.find(text)?.groupValues?.getOrNull(1)?.let { name ->
            detectedThisSession = true
            mainHandler.post { onNameDetected(name.capitalizeFirst()) }
            return
        }

        // Context path: ask Gemini if there's any sign a name was stated.
        // questionInCurrent: question + answer arrived in one result or partial.
        // questionInPrevious: question came earlier, current phrase is a short reply.
        // shortWithRecentQuestion: recognizer split question/answer across sessions;
        //   recent context has the question and current result is 1-2 words (likely the name).
        val questionInCurrent = nameQuestionRegex.containsMatchIn(text)
        val questionInPrevious =  previousPhrase != null&&  nameQuestionRegex.containsMatchIn(
            previousPhrase!!
        )
        val shouldAskGemini = geminiApiKey.isNotBlank() &&  questionInPrevious

        if(questionInCurrent) {
            previousPhrase = text
        }else if (shouldAskGemini) {
            scope.launch {
                askGemini(text, previousPhrase)?.let { name ->
                    detectedThisSession = true
                    mainHandler.post { onNameDetected(name) }
                }
            }
        }
    }

    private suspend fun askGemini(latestPhrase: String, previous: String?): String? =
        withContext(Dispatchers.IO) {
            try {
                val prompt = """
                    You are analysing ambient speech captured by a wearable device. The transcript may mix speech from multiple people — a question from one person and an answer from another can appear in the same phrase. Recognition delays also mean phrases can arrive slightly out of chronological order.

                    Previous transcript segment: "$previous"
                    Latest transcript segment: "$latestPhrase"

                    Task: extract the first name of the person who was introduced or who introduced themselves. This includes:
                    - Direct introductions: "I'm Joe", "My name is Joe", "Call me Joe"
                    - A name question ("what's your name", "your name", "who are you") appearing near a short word that is plausibly a first name — even if the name appears just before the question due to recognition delay, treat it as the answer
                    - The pattern "What's your name [Name]" means [Name] is the answer

                    Reply with ONLY the first name (capitalised) if found, or exactly NONE if no name was stated.
                """.trimIndent()
                previousPhrase = null

                val requestBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            })
                        })
                    })
                }.toString()

                val url = URL("https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash:generateContent?key=$geminiApiKey")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    outputStream.use { it.write(requestBody.toByteArray()) }
                }

                val responseText = if (conn.responseCode == 200) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                    println("cv-GEMINI HTTP ${conn.responseCode}: $err")
                    return@withContext null
                }

                val result = JSONObject(responseText)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()

                println("cv-RESULT: $result")
                if (result == "NONE" || result.isBlank() || result.contains(Regex("[\\n\\r.,!?]"))) null
                else result
            } catch (e: Exception) {
                println("cv-GEMINI ERROR: ${e::class.simpleName}: ${e.message}")
                null
            }
        }

    private fun String.capitalizeFirst() = replaceFirstChar { it.uppercase() }
}
