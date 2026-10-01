package dev.alexander812.lexi.speech

import android.content.Context
import java.io.File

class VoiceStorage(context: Context) {

    private val root = File(context.filesDir, "tts")

    fun rootDir(): File = root

    fun voiceDir(id: String): File = File(root, id)

    fun sharedDataDir(): File = File(root, "espeak-ng-data")

    fun modelFile(entry: VoiceEntry): File = File(voiceDir(entry.id), "${entry.id}.onnx")

    fun tokensFile(entry: VoiceEntry): File = File(voiceDir(entry.id), "tokens.txt")

    fun dataDir(entry: VoiceEntry): File {
        val shared = sharedDataDir()
        if (isDataDir(shared)) return shared
        return File(voiceDir(entry.id), "espeak-ng-data")
    }

    fun isInstalled(entry: VoiceEntry): Boolean =
        modelFile(entry).length() > 0L && tokensFile(entry).length() > 0L && isDataDir(dataDir(entry))

    fun delete(entry: VoiceEntry) {
        voiceDir(entry.id).deleteRecursively()
    }

    private fun isDataDir(dir: File): Boolean = File(dir, "phontab").length() > 0L
}
