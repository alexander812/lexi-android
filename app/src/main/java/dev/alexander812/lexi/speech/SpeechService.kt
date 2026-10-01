package dev.alexander812.lexi.speech

class SpeechService(
    private val system: SpeechSynthesizer,
    private val voices: VoiceManager,
    private val local: LocalTts,
) {

    fun speak(text: String?, lang: String?, onOutcome: (SpeakOutcome) -> Unit) {
        val query = text?.trim().orEmpty()
        if (query.isEmpty()) {
            onOutcome(SpeakOutcome.Failure(REASON_TEXT_REQUIRED))
            return
        }

        val code = lang?.trim()?.lowercase().orEmpty()
        val entry = voiceFor(code)

        if (entry != null && voices.isInstalled(code)) {
            local.speak(entry, query, onOutcome)
            return
        }

        if (system.isLanguageSupported(code) != false) {
            system.speak(query, code, onOutcome)
            return
        }

        if (entry == null) {
            onOutcome(SpeakOutcome.Failure(REASON_LANGUAGE))
            return
        }

        onOutcome(SpeakOutcome.Failure(REASON_VOICE_MISSING))
    }

    fun onVoiceDeleted(lang: String) {
        local.unload(lang)
    }

    fun release() {
        system.release()
        local.release()
    }

    companion object {
        private const val REASON_TEXT_REQUIRED = "text_required"
        private const val REASON_LANGUAGE = "language_not_supported"
        private const val REASON_VOICE_MISSING = "voice_missing"
    }
}
