package com.example.facematcher

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.google.mlkit.genai.prompt.Generation
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
    companion object {
        // Hypotheses below this confidence are ignored; scores < 0 mean "unknown" and are kept.
        private const val MIN_CONFIDENCE = 0.3f

        // How long a heard name question stays usable as context for a later short reply.
        private const val QUESTION_CONTEXT_TTL_MS = 15_000L

        // Watchdog: if the recognizer delivers no callback at all for this long, assume it
        // stalled (died without onError) and force-restart it so listening never silently stops.
        private const val WATCHDOG_STALL_MS = 60_000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 15_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening: Boolean by Delegates.observable(false) { prop, old, newValue ->
        println("cv-isListening: $newValue")
    }

    // Set when the on-device recognizer rejects the language so we fall back to the default one.
    private var onDeviceFailed = false

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // Prevents duplicate name callbacks when a partial result already triggered detection.
    private var detectedThisSession = false

    private var previousPhrase: String? = null
    private var previousPhraseAtMs = 0L

    private val introRegex = Regex(
        """(?:i(?:'m| am)|my name(?:'s| is)|name(?:'s| is)|call me|they call me)\s+([A-Za-z]+)""",
        RegexOption.IGNORE_CASE
    )
    private val nameQuestionRegex = Regex(
        """(?:what(?:'s| is) your name|your name|name again|who are you|remind me)""",
        RegexOption.IGNORE_CASE
    )

    // Leading words to skip in a reply before the name: "uh, it's Joe" → "Joe".
    private val replyFillers = setOf(
        "um", "uh", "oh", "ah", "well", "so", "yeah", "yes", "okay", "ok", "sure",
        "hi", "hey", "hello", "it's", "its", "i'm", "im"
    )

    // Common words that shouldn't be mistaken for a name when they're the whole reply.
    private val nonNameWords = replyFillers + setOf(
        "no", "not", "nothing", "what", "who", "why", "when", "where", "how",
        "sorry", "thanks", "thank", "please", "name", "your", "my", "me", "you",
        "is", "was", "it", "that", "this", "the", "a", "an", "and", "but",
        "right", "cool", "nice", "good", "great", "fine", "sir", "man", "dude"
    )

    /** Starts listening and keeps listening until [stop] — the mic is always on. */
    fun start() {
        if (isListening || !SpeechRecognizer.isRecognitionAvailable(context)) return
        isListening = true
        lastCallbackAtMs = SystemClock.elapsedRealtime()
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_CHECK_INTERVAL_MS)
        listen()
    }

    private fun destroyRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    fun stop() {
        isListening = false
        job.cancel()
        mainHandler.removeCallbacks(watchdogRunnable)
        speechRecognizer?.stopListening()
        destroyRecognizer()
    }

    private var lastCallbackAtMs = 0L

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isListening) return
            if (SystemClock.elapsedRealtime() - lastCallbackAtMs > WATCHDOG_STALL_MS) {
                println("cv-watchdog: recognizer stalled, restarting")
                lastCallbackAtMs = SystemClock.elapsedRealtime()
                listen(recreate = true)
            }
            mainHandler.postDelayed(this, WATCHDOG_CHECK_INTERVAL_MS)
        }
    }

    private val listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
        // Keep this short: in noisy environments long silence thresholds never trigger,
        // so sessions time out without ever delivering a final result.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
    }

    private fun listen(recreate: Boolean = false) {
        detectedThisSession = false
        if (recreate || speechRecognizer == null) {
            destroyRecognizer()
            speechRecognizer = createRecognizer().apply {
                setRecognitionListener(recognitionListener)
            }
        }
        speechRecognizer?.startListening(listenIntent)
    }

    // On-device recognition avoids network round-trips, so sessions start faster and keep
    // working offline. Fall back to the default recognizer if unsupported or it rejects
    // the language.
    private fun createRecognizer(): SpeechRecognizer =
        if (!onDeviceFailed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private val recognitionListener = object : RecognitionListener {
        private fun heartbeat() {
            lastCallbackAtMs = SystemClock.elapsedRealtime()
        }

        override fun onResults(results: Bundle) {
            heartbeat()
            val alternatives = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val confidences = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            println("cv-results: $alternatives confidences=${confidences?.toList()}")
            // Some recognizers (e.g. Pixel on-device) report 0.0 to mean "no confidence
            // available" rather than the documented -1, so treat <= 0 as unknown and keep it.
            processResults(alternatives.filterIndexed { i, _ ->
                val score = confidences?.getOrNull(i) ?: -1f
                score <= 0f || score >= MIN_CONFIDENCE
            })
            if (isListening) mainHandler.post { listen() }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            heartbeat()
            val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            println("cv-partialResults: $partials")
            // Sessions in noisy environments often die (NO_MATCH/timeout) before onResults
            // fires, so run the fast regex path on partial text too. The context/Gemini
            // path waits for final results.
            if (detectedThisSession) return
            for (partial in partials) {
                if (tryDirectIntro(partial)) return
            }
            // Remember a name question heard mid-session: noisy sessions often die
            // before final results, and the answer then arrives in a later session.
            partials.firstOrNull { nameQuestionRegex.containsMatchIn(it) }?.let {
                previousPhrase = it
                previousPhraseAtMs = SystemClock.elapsedRealtime()
            }
        }

        override fun onError(error: Int) {
            heartbeat()
            val recreate: Boolean
            val retryDelayMs: Long
            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { recreate = false; retryDelayMs = 2000L }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> return
                SpeechRecognizer.ERROR_NO_MATCH -> { recreate = false; retryDelayMs = 300L }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                    onDeviceFailed = true; recreate = true; retryDelayMs = 1000L
                }
                else -> { recreate = true; retryDelayMs = 1000L }
            }
            if (isListening) mainHandler.postDelayed({ listen(recreate) }, retryDelayMs)
        }

        override fun onReadyForSpeech(params: Bundle?) { heartbeat() }
        override fun onEndOfSpeech() { heartbeat() }
        override fun onBeginningOfSpeech() { heartbeat() }
        override fun onRmsChanged(rmsdB: Float) { heartbeat() }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Returns true if a direct "I'm X" style introduction was found and dispatched. */
    private fun tryDirectIntro(text: String): Boolean {
        val name = introRegex.find(text)?.groupValues?.getOrNull(1) ?: return false
        detectedThisSession = true
        dispatchName(name.capitalizeFirst())
        return true
    }

    private fun dispatchName(name: String) {
        println("cv-NAME detected: $name")
        mainHandler.post {
            previousPhrase = null
            onNameDetected(name)
        }
    }

    private fun processResults(alternatives: List<String>) {
        if (detectedThisSession || alternatives.isEmpty()) return

        // Fast path: direct introduction pattern in any hypothesis.
        for (alternative in alternatives) {
            if (tryDirectIntro(alternative)) return
        }

        // Context path: ask Gemini if there's any sign a name was stated.
        // questionInCurrent: question + answer may have arrived in one result.
        // questionInPrevious: question came in an earlier session and the current phrase
        //   is a plausible reply. Questions expire after QUESTION_CONTEXT_TTL_MS so stale
        //   context can't turn random later chatter into a Gemini query.
        val text = alternatives.first()
        val now = SystemClock.elapsedRealtime()
        val questionInCurrent = nameQuestionRegex.containsMatchIn(text)
        val freshPrevious = previousPhrase
            ?.takeIf { now - previousPhraseAtMs < QUESTION_CONTEXT_TTL_MS }
        val questionInPrevious = freshPrevious != null && nameQuestionRegex.containsMatchIn(freshPrevious)

        if (questionInCurrent) {
            previousPhrase = text
            previousPhraseAtMs = now
        }

        // Deterministic path — no Gemini needed: the short reply to a name question IS
        // the name. Handles both the answer arriving in the same phrase as the question
        // and in a later phrase within the context window.
        for (alternative in alternatives) {
            val candidate = when {
                questionInCurrent -> nameQuestionRegex.find(alternative)?.let { match ->
                    extractNameFromReply(alternative.substring(match.range.last + 1))
                }
                questionInPrevious -> extractNameFromReply(alternative)
                else -> null
            }
            if (candidate != null) {
                detectedThisSession = true
                dispatchName(candidate)
                return
            }
        }

        // Fallback for messier phrasings the heuristics above can't parse.
        if (questionInCurrent || questionInPrevious) {
            scope.launch {
                askGemini(text, freshPrevious)?.let { name ->
                    detectedThisSession = true
                    dispatchName(name)
                }
            }
        }
    }

    /**
     * For a short utterance following a name question, extract a plausible first name.
     * Skips leading fillers ("uh, it's Joe"), then accepts at most two remaining words
     * ("Joe", "Joe Smith") so full sentences fall through to the Gemini path instead.
     */
    private fun extractNameFromReply(text: String): String? {
        val words = text.trim()
            .split(Regex("\\s+"))
            .map { it.trim { c -> !c.isLetter() && c != '\'' } }
            .filter { it.isNotEmpty() }
            .dropWhile { it.lowercase() in replyFillers }
        if (words.isEmpty() || words.size > 2) return null
        val candidate = words.first()
        if (candidate.length < 2) return null
        if (candidate.lowercase() in nonNameWords) return null
        if (!candidate.all { it.isLetter() }) return null
        return candidate.capitalizeFirst()
    }

    private fun buildPrompt(latestPhrase: String, previous: String?) = """
        You are analysing ambient speech captured by a wearable device. The transcript may mix
        speech from multiple people — a question from one person and an answer from another can
        appear in the same phrase. Recognition delays also mean phrases can arrive slightly out
        of chronological order.

        Previous transcript segment: "$previous"
        Latest transcript segment: "$latestPhrase"

        Task: extract the first name of the person who was introduced or who introduced
        themselves. This includes:
        - Direct introductions: "I'm Joe", "My name is Joe", "Call me Joe"
        - A name question ("what's your name", "your name", "who are you") appearing near a
          short word that is plausibly a first name — even if the name appears just before the
          question due to recognition delay, treat it as the answer
        - The pattern "What's your name [Name]" means [Name] is the answer

        Reply with ONLY the first name (capitalised) if found, or exactly NONE if no name was stated.
    """.trimIndent()

    private fun String.isValidName() =
        isNotBlank() && this != "NONE" && !contains(Regex("[\\n\\r.,!?]"))

    private suspend fun askGemini(latestPhrase: String, previous: String?): String? {
        val prompt = buildPrompt(latestPhrase, previous)
        return askGeminiNano(prompt) ?: askGeminiRest(prompt)
    }

    /** On-device inference via Gemini Nano — no network, no API key required. */
    private suspend fun askGeminiNano(prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            val client = Generation.getClient()
            val status = client.checkStatus()
            if (status != FeatureStatus.AVAILABLE) {
                println("cv-NANO: not available (status=$status)")
                return@withContext null
            }
            val response = client.generateContent(prompt)
            val result = response.candidates.firstOrNull()?.text?.trim() ?: return@withContext null
            println("cv-NANO RESULT: $result")
            if (result.isValidName()) result else null
        } catch (e: Exception) {
            println("cv-NANO ERROR: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    /** Cloud fallback via Gemini REST API. */
    private suspend fun askGeminiRest(prompt: String): String? = withContext(Dispatchers.IO) {
        if (geminiApiKey.isBlank()) return@withContext null
        try {
            println("cv-REST: calling Gemini REST API")
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
                println("cv-REST HTTP ${conn.responseCode}: $err")
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

            println("cv-REST RESULT: $result")
            if (result.isValidName()) result else null
        } catch (e: Exception) {
            println("cv-REST ERROR: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun String.capitalizeFirst() = replaceFirstChar { it.uppercase() }
}
