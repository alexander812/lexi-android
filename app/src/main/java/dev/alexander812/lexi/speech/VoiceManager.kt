package dev.alexander812.lexi.speech

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class VoiceState(
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
)

class VoiceManager(context: Context) {

    private val appContext = context.applicationContext
    private val storage = VoiceStorage(appContext)
    private val executor = Executors.newSingleThreadExecutor()
    private val states = ConcurrentHashMap<String, VoiceState>()
    private val listeners = ConcurrentHashMap<String, MutableList<(Result<Unit>) -> Unit>>()

    fun state(lang: String): VoiceState = states[lang] ?: VoiceState()

    fun isInstalled(lang: String): Boolean = voiceFor(lang)?.let(storage::isInstalled) ?: false

    fun storage(): VoiceStorage = storage

    fun download(lang: String, onDone: (Result<Unit>) -> Unit) {
        val entry = voiceFor(lang)
        if (entry == null) {
            onDone(Result.failure(IllegalArgumentException("unsupported_language")))
            return
        }
        if (storage.isInstalled(entry)) {
            states[lang] = VoiceState()
            onDone(Result.success(Unit))
            return
        }
        if (states[lang]?.downloading == true) {
            addListener(lang, onDone)
            return
        }
        states[lang] = VoiceState(downloading = true)
        addListener(lang, onDone)
        executor.execute {
            val result = runCatching {
                fetch(entry) { progress -> states[lang] = VoiceState(downloading = true, progress = progress) }
                extract(entry)
            }
            states[lang] = if (result.isSuccess) {
                VoiceState()
            } else {
                val reason = result.exceptionOrNull()?.message ?: "download_failed"
                Log.w(TAG, "voice download failed for $lang: $reason")
                VoiceState(error = reason)
            }
            notifyListeners(lang, result)
        }
    }

    fun delete(lang: String): Boolean {
        val entry = voiceFor(lang) ?: return false
        storage.delete(entry)
        states[lang] = VoiceState()
        return true
    }

    fun release() {
        executor.shutdownNow()
    }

    private fun addListener(lang: String, onDone: (Result<Unit>) -> Unit) {
        listeners.computeIfAbsent(lang) { mutableListOf() }.add(onDone)
    }

    private fun notifyListeners(lang: String, result: Result<Unit>) {
        val callbacks = listeners.remove(lang) ?: return
        callbacks.forEach { it(result) }
    }

    private fun fetch(entry: VoiceEntry, onProgress: (Float) -> Unit) {
        val archive = File(appContext.cacheDir, "${entry.id}.tar.bz2")
        try {
            if (archive.length() != entry.sizeBytes) {
                archive.delete()
                val connection = URL(voiceUrl(entry.id)).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException("download_failed")
                    }
                    val total = connection.contentLengthLong.takeIf { it > 0L } ?: entry.sizeBytes
                    connection.inputStream.use { input ->
                        FileOutputStream(archive).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var received = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                received += count
                                onProgress((received.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            if (archive.length() != entry.sizeBytes) throw IOException("download_incomplete")
        } catch (error: Exception) {
            archive.delete()
            throw error
        }
    }

    private fun extract(entry: VoiceEntry) {
        val archive = File(appContext.cacheDir, "${entry.id}.tar.bz2")
        try {
            if (!archive.exists()) throw IOException("download_missing")
            val target = storage.voiceDir(entry.id)
            target.deleteRecursively()
            if (!target.mkdirs()) throw IOException("storage_unavailable")
            val targetPath = target.canonicalPath + File.separator
            BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())).use { bzip ->
                TarArchiveInputStream(bzip).use { tar ->
                    while (true) {
                        val item = tar.nextEntry ?: break
                        if (!tar.canReadEntryData(item)) continue
                        val name = item.name.substringAfter('/', "")
                        if (name.isEmpty()) continue
                        val file = File(target, name)
                        if (!file.canonicalPath.startsWith(targetPath)) throw IOException("bad_archive")
                        if (item.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { output -> tar.copyTo(output) }
                        }
                    }
                }
            }
            if (!storage.isInstalled(entry)) throw IOException("extract_failed")
        } finally {
            archive.delete()
        }
    }

    companion object {
        private const val TAG = "LexiVoice"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val BUFFER_SIZE = 64 * 1024
    }
}
