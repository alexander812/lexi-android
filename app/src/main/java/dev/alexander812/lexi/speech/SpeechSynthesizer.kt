package dev.alexander812.lexi.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

sealed interface SpeakOutcome {
    data class Success(val utteranceId: String) : SpeakOutcome
    data class Failure(val reason: String) : SpeakOutcome
}

class SpeechSynthesizer(context: Context) {

    private var engine: TextToSpeech? = null
    private var state: State = State.Initializing
    private var pending: PendingUtterance? = null
    private var sequence = 0

    private enum class State { Initializing, Ready, Unavailable }

    private data class PendingUtterance(
        val text: String,
        val locale: Locale,
        val onOutcome: (SpeakOutcome) -> Unit,
    )

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            synchronized(this) {
                if (status == TextToSpeech.SUCCESS) {
                    Log.i(TAG, "engine ready")
                    state = State.Ready
                    applyAudioAttributes()
                    flushPending()
                } else {
                    Log.w(TAG, "engine init failed: $status")
                    state = State.Unavailable
                    pending?.onOutcome?.invoke(SpeakOutcome.Failure(REASON_UNAVAILABLE))
                    pending = null
                }
            }
        }
    }

    @Synchronized
    fun isLanguageSupported(lang: String): Boolean? {
        if (state == State.Initializing) return null
        val engine = engine ?: return false
        return engine.isLanguageAvailable(localeFor(lang)) >= TextToSpeech.LANG_AVAILABLE
    }

    @Synchronized
    fun speak(text: String?, lang: String?, onOutcome: (SpeakOutcome) -> Unit) {
        val query = text?.trim().orEmpty()
        if (query.isEmpty()) {
            onOutcome(SpeakOutcome.Failure(REASON_TEXT_REQUIRED))
            return
        }

        val locale = localeFor(lang)
        when (state) {
            State.Unavailable -> onOutcome(SpeakOutcome.Failure(REASON_UNAVAILABLE))
            State.Initializing -> {
                pending?.onOutcome?.invoke(SpeakOutcome.Failure(REASON_SUPERSEDED))
                pending = PendingUtterance(query, locale, onOutcome)
            }
            State.Ready -> onOutcome(speakNow(query, locale))
        }
    }

    @Synchronized
    fun release() {
        pending?.onOutcome?.invoke(SpeakOutcome.Failure(REASON_UNAVAILABLE))
        pending = null
        engine?.stop()
        engine?.shutdown()
        engine = null
        state = State.Unavailable
    }

    private fun flushPending() {
        val queued = pending ?: return
        pending = null
        queued.onOutcome(speakNow(queued.text, queued.locale))
    }

    private fun applyAudioAttributes() {
        engine?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    private fun speakNow(text: String, locale: Locale): SpeakOutcome {
        val engine = engine ?: return SpeakOutcome.Failure(REASON_UNAVAILABLE)
        val availability = engine.setLanguage(locale)
        if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "language unavailable: $locale ($availability)")
            return SpeakOutcome.Failure(REASON_LANGUAGE)
        }
        sequence += 1
        val utteranceId = "lexi-$sequence"
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak failed: $locale ($result)")
            return SpeakOutcome.Failure(REASON_SPEAK_FAILED)
        }
        Log.i(TAG, "speak queued: $locale, ${text.length} chars")
        return SpeakOutcome.Success(utteranceId)
    }

    private fun localeFor(lang: String?): Locale {
        val code = lang?.trim()?.lowercase().orEmpty()
        return LANGUAGE_LOCALES[code] ?: Locale.forLanguageTag(code)
    }

    companion object {
        private const val TAG = "LexiSpeech"

        private val LANGUAGE_LOCALES = mapOf(
            "ru" to Locale("ru", "RU"),
            "en" to Locale("en", "US"),
            "es" to Locale("es", "ES"),
            "fr" to Locale("fr", "FR"),
            "it" to Locale("it", "IT"),
            "zh" to Locale.SIMPLIFIED_CHINESE,
            "de" to Locale("de", "DE"),
        )

        private const val REASON_TEXT_REQUIRED = "text_required"
        private const val REASON_UNAVAILABLE = "speech_unavailable"
        private const val REASON_LANGUAGE = "language_not_supported"
        private const val REASON_SPEAK_FAILED = "speak_failed"
        private const val REASON_SUPERSEDED = "superseded"
    }
}
