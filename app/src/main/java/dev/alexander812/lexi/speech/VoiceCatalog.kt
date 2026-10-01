package dev.alexander812.lexi.speech

data class VoiceEntry(
    val lang: String,
    val id: String,
    val title: String,
    val sizeBytes: Long,
)

val VOICE_CATALOG = listOf(
    VoiceEntry("ru", "ru_RU-ruslan-medium", "Руслан", 67_210_684L),
    VoiceEntry("en", "en_US-lessac-medium", "Lessac", 67_230_653L),
    VoiceEntry("es", "es_ES-sharvard-medium", "Sharvard", 80_318_184L),
    VoiceEntry("fr", "fr_FR-siwis-medium", "Siwis", 67_207_459L),
    VoiceEntry("it", "it_IT-paola-medium", "Paola", 67_221_173L),
    VoiceEntry("de", "de_DE-thorsten-medium", "Thorsten", 67_214_254L),
    VoiceEntry("zh", "zh_CN-huayan-medium", "Huayan", 67_255_926L),
)

fun voiceFor(lang: String): VoiceEntry? = VOICE_CATALOG.firstOrNull { it.lang == lang }

fun voiceUrl(modelId: String): String =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$modelId.tar.bz2"
