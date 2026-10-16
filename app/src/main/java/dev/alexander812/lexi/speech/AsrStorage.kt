package dev.alexander812.lexi.speech

import android.content.Context
import java.io.File

class AsrStorage(context: Context) {

    private val root = File(context.filesDir, "asr")

    fun modelDir(entry: AsrEntry): File = File(root, entry.id)

    fun file(entry: AsrEntry, name: String): File = File(modelDir(entry), name)

    fun tokensFile(entry: AsrEntry): File = file(entry, entry.tokenFile)

    fun isInstalled(entry: AsrEntry): Boolean = entry.files.all { file(entry, it).length() > 0L }

    fun delete(entry: AsrEntry) {
        modelDir(entry).deleteRecursively()
    }

    fun cleanup() {
        val known = ASR_ENTRIES.map { it.id }.toSet()

        root.listFiles()?.forEach { item ->
            if (item.name !in known) item.deleteRecursively()
        }
    }
}
