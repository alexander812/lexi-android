package dev.alexander812.lexi.speech

import android.content.Context
import java.io.File

class VoiceStorage(context: Context) {

    private val root = File(context.filesDir, "tts")

    fun voiceDir(id: String): File = File(root, id)

    fun modelFile(entry: VoiceEntry): File = File(voiceDir(entry.id), "${entry.id}.onnx")

    fun tokensFile(entry: VoiceEntry): File = File(voiceDir(entry.id), "tokens.txt")

    fun dataDir(entry: VoiceEntry): File = File(voiceDir(entry.id), "espeak-ng-data")

    fun isInstalled(entry: VoiceEntry): Boolean =
        modelFile(entry).length() > 0L && tokensFile(entry).length() > 0L && dataDir(entry).isDirectory

    fun delete(entry: VoiceEntry) {
        voiceDir(entry.id).deleteRecursively()
    }
}
