package dev.alexander812.lexi.speech

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

sealed interface RecognizeOutcome {
    data class Success(
        val transcript: String,
        val confidence: Float?,
        val alternatives: List<String>,
    ) : RecognizeOutcome
    data class Failure(val reason: String) : RecognizeOutcome
}

class RecognitionService(private val activity: ComponentActivity) {

    private var recognizer: SpeechRecognizer? = null
    private var pending: ((RecognizeOutcome) -> Unit)? = null
    private var pendingLang: String = DEFAULT_LOCALE
    private var listening = false

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val callback = pending ?: return@registerForActivityResult
        if (granted) {
            startListening()
        } else {
            pending = null
            callback(RecognizeOutcome.Failure(REASON_PERMISSION))
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            deliver(resultsOutcome(results))
        }

        override fun onError(error: Int) {
            deliver(RecognizeOutcome.Failure(reasonFor(error)))
        }
    }

    fun recognize(lang: String?, onOutcome: (RecognizeOutcome) -> Unit) {
        activity.runOnUiThread {
            if (pending != null || listening) {
                onOutcome(RecognizeOutcome.Failure(REASON_BUSY))
                return@runOnUiThread
            }
            if (!isAvailable()) {
                onOutcome(RecognizeOutcome.Failure(REASON_NOT_AVAILABLE))
                return@runOnUiThread
            }
            pending = onOutcome
            pendingLang = localeFor(lang)
            if (hasPermission()) {
                startListening()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    fun release() {
        activity.runOnUiThread {
            pending = null
            listening = false
            recognizer?.destroy()
            recognizer = null
        }
    }

    fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(activity) || isOnDeviceAvailable()

    private fun isOnDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startListening() {
        try {
            val current = recognizer ?: createRecognizer().also {
                it.setRecognitionListener(listener)
                recognizer = it
            }
            listening = true
            current.startListening(buildIntent())
        } catch (error: Exception) {
            listening = false
            deliver(RecognizeOutcome.Failure(REASON_FAILED))
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        if (SpeechRecognizer.isRecognitionAvailable(activity)) {
            return SpeechRecognizer.createSpeechRecognizer(activity)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
        }

        throw IllegalStateException(REASON_NOT_AVAILABLE)
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, pendingLang)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
    }

    private fun resultsOutcome(results: Bundle?): RecognizeOutcome {
        val candidates = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        if (candidates.isEmpty()) return RecognizeOutcome.Failure(REASON_NO_SPEECH)

        val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        val confidence = scores?.firstOrNull()?.takeIf { it > 0f }

        return RecognizeOutcome.Success(candidates.first(), confidence, candidates.drop(1))
    }

    private fun deliver(outcome: RecognizeOutcome) {
        listening = false
        val callback = pending ?: return
        pending = null
        callback(outcome)
    }

    private fun localeFor(lang: String?): String {
        val code = lang?.trim()?.lowercase().orEmpty()
        return LOCALES[code] ?: code.ifEmpty { DEFAULT_LOCALE }
    }

    companion object {
        private const val DEFAULT_LOCALE = "en-US"
        private const val MAX_RESULTS = 3
        private const val REASON_BUSY = "busy"
        private const val REASON_FAILED = "recognition_failed"
        private const val REASON_NO_SPEECH = "no_speech"
        private const val REASON_NOT_AVAILABLE = "not_available"
        private const val REASON_PERMISSION = "permission_denied"

        private val LOCALES = mapOf(
            "de" to "de-DE",
            "en" to "en-US",
            "es" to "es-ES",
            "fr" to "fr-FR",
            "it" to "it-IT",
            "ru" to "ru-RU",
            "zh" to "zh-CN",
        )

        private fun reasonFor(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "audio_error"
            SpeechRecognizer.ERROR_CLIENT -> "cancelled"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> REASON_PERMISSION
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> REASON_NO_SPEECH
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> REASON_BUSY
            SpeechRecognizer.ERROR_SERVER -> "server_error"
            else -> REASON_FAILED
        }
    }
}
