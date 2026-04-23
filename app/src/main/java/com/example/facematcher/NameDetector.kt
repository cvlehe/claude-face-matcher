package com.example.facematcher

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var speechRecognizer: SpeechRecognizer? = null
    private val recentPhrases = ArrayDeque<String>()
    private var isActive = false

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private val gemini by lazy {
        GenerativeModel(modelName = "gemini-1.5-flash", apiKey = geminiApiKey)
    }

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
        listen()
    }

    fun stop() {
        isActive = false
        job.cancel()
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
    }

    private fun listen() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
        speechRecognizer?.destroy()
        val recognizer = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else
            SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer.apply {
            setRecognitionListener(recognitionListener)
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                }
            )
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) processPhrase(text)
            if (isActive) mainHandler.post { listen() }
        }

        override fun onError(error: Int) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            val retryDelayMs = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1000L
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> return // stop retrying
                else -> 300L
            }
            if (isActive) mainHandler.postDelayed({ listen() }, retryDelayMs)
        }

        override fun onReadyForSpeech(params: Bundle?) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
        }
        override fun onEndOfSpeech() {
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processPhrase(text: String) {
        if (recentPhrases.size >= 6) recentPhrases.removeFirst()
        recentPhrases.addLast(text)

        // Fast path: direct introduction pattern
        introRegex.find(text)?.groupValues?.getOrNull(1)?.let { name ->
            mainHandler.post { onNameDetected(name.capitalizeFirst()) }
            return
        }

        // Context path: short reply after a name question (e.g. "What's your name?" → "Joe")
        val recentHadQuestion = recentPhrases.dropLast(1).any { nameQuestionRegex.containsMatchIn(it) }
        val wordCount = text.trim().split(Regex("\\s+")).size
        if (recentHadQuestion && wordCount <= 4 && geminiApiKey.isNotBlank()) {
            scope.launch {
                askGemini(recentPhrases.joinToString(". "), text)?.let { name ->
                    mainHandler.post { onNameDetected(name) }
                }
            }
        }
    }

    private suspend fun askGemini(recentContext: String, latestPhrase: String): String? {
        return try {
            val prompt = """
                You are detecting name introductions in a conversation.

                Recent conversation: "$recentContext"
                Latest phrase: "$latestPhrase"

                Did the latest phrase contain someone stating their first name — either as a direct introduction or as a reply to being asked their name? If yes, reply with ONLY the first name (capitalized). If no, reply with exactly: NONE
            """.trimIndent()
            val result = gemini.generateContent(prompt).text?.trim() ?: return null
            if (result == "NONE" || result.isBlank() || result.contains(Regex("[\\n\\r.,!?]"))) null
            else result
        } catch (_: Exception) {
            null
        }
    }

    private fun String.capitalizeFirst() = replaceFirstChar { it.uppercase() }
}
