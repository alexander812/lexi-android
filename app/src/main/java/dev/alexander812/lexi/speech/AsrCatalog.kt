package dev.alexander812.lexi.speech

data class AsrEntry(
    val id: String,
    val title: String,
    val sizeBytes: Long,
    val languages: Set<String>,
    val archiveName: String,
)

val ASR_ENTRY = AsrEntry(
    id = "nemo-fast-conformer-ctc-20k",
    title = "Распознавание речи",
    sizeBytes = 97_500_000L,
    languages = setOf("de", "en", "es", "fr", "it", "ru"),
    archiveName = "sherpa-onnx-nemo-fast-conformer-ctc-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8",
)

const val ASR_MODEL_FILE = "model.int8.onnx"
const val ASR_TOKENS_FILE = "tokens.txt"

fun asrArchiveUrl(entry: AsrEntry): String =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${entry.archiveName}.tar.bz2"
