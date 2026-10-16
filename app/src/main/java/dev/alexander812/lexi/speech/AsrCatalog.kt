package dev.alexander812.lexi.speech

enum class AsrEngine { NEMO_CTC, WHISPER }

data class AsrEntry(
    val id: String,
    val title: String,
    val description: String,
    val sizeBytes: Long,
    val languages: Set<String>,
    val archiveName: String,
    val engine: AsrEngine,
    val tokenFile: String,
    val modelFile: String? = null,
    val encoderFile: String? = null,
    val decoderFile: String? = null,
) {
    val files: List<String> = listOfNotNull(modelFile, encoderFile, decoderFile, tokenFile)
}

val ASR_EN_ENTRY = AsrEntry(
    id = "nemo-en-conformer-small",
    title = "Английская модель",
    description = "NeMo Conformer CTC: заметно точнее для английских слов",
    sizeBytes = 76_482_338L,
    languages = setOf("en"),
    archiveName = "sherpa-onnx-nemo-ctc-en-conformer-small",
    engine = AsrEngine.NEMO_CTC,
    tokenFile = "tokens.txt",
    modelFile = "model.int8.onnx",
)

val ASR_MULTILINGUAL_ENTRY = AsrEntry(
    id = "whisper-tiny-multilingual",
    title = "Мультиязычная модель",
    description = "Whisper tiny: распознавание на русском, испанском, французском и английском",
    sizeBytes = 116_204_861L,
    languages = setOf("en", "es", "fr", "ru"),
    archiveName = "sherpa-onnx-whisper-tiny",
    engine = AsrEngine.WHISPER,
    tokenFile = "tiny-tokens.txt",
    encoderFile = "tiny-encoder.int8.onnx",
    decoderFile = "tiny-decoder.int8.onnx",
)

val ASR_ENTRIES: List<AsrEntry> = listOf(ASR_EN_ENTRY, ASR_MULTILINGUAL_ENTRY)

fun coversLanguage(entry: AsrEntry, language: String): Boolean = language in entry.languages

fun preferredAsrEntries(language: String): List<AsrEntry> =
    ASR_ENTRIES.filter { coversLanguage(it, language) }.sortedBy { it.languages.size }

fun findAsrEntryById(id: String?): AsrEntry? =
    ASR_ENTRIES.find { it.id == id } ?: ASR_ENTRIES.firstOrNull()

fun asrArchiveUrl(entry: AsrEntry): String =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${entry.archiveName}.tar.bz2"
