package dev.alexander812.lexi.speech

import android.content.Context
import java.io.File

class AsrStorage(context: Context) {

    private val root = File(context.filesDir, "asr")

    fun modelDir(entry: AsrEntry): File = File(root, entry.id)

    fun modelFile(entry: AsrEntry): File = File(modelDir(entry), ASR_MODEL_FILE)

    fun tokensFile(entry: AsrEntry): File = File(modelDir(entry), ASR_TOKENS_FILE)

    fun isInstalled(entry: AsrEntry): Boolean =
        modelFile(entry).length() > 0L && tokensFile(entry).length() > 0L

    fun delete(entry: AsrEntry) {
        modelDir(entry).deleteRecursively()
    }
}
