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
import java.util.zip.ZipInputStream

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
                ensureSharedData()
                fetchVoice(entry) { progress ->
                    states[lang] = VoiceState(downloading = true, progress = progress)
                }
                if (!storage.isInstalled(entry)) throw IOException("voice_incomplete")
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

    private fun fetchVoice(entry: VoiceEntry, onProgress: (Float) -> Unit) {
        val errors = mutableListOf<String>()

        try {
            fetchFromHuggingFace(entry, onProgress)
            if (storage.isInstalled(entry)) return
            errors += "huggingface:voice_incomplete"
        } catch (error: Exception) {
            Log.w(TAG, "huggingface source failed: ${error.message}")
            errors += "huggingface:${error.message ?: "failed"}"
        }

        try {
            fetchFromGithub(entry, onProgress)
            if (storage.isInstalled(entry)) return
            errors += "github:voice_incomplete"
        } catch (error: Exception) {
            Log.w(TAG, "github source failed: ${error.message}")
            errors += "github:${error.message ?: "failed"}"
        }

        throw IOException(errors.joinToString("; ").ifEmpty { "download_failed" })
    }

    private fun fetchFromHuggingFace(entry: VoiceEntry, onProgress: (Float) -> Unit) {
        val dir = storage.voiceDir(entry.id)
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("storage_unavailable")

        downloadFile(
            huggingFaceUrl(entry.id, "${entry.id}.onnx"),
            storage.modelFile(entry),
        ) { progress -> onProgress(progress * MODEL_PROGRESS_SHARE) }

        downloadFile(
            huggingFaceUrl(entry.id, "tokens.txt"),
            storage.tokensFile(entry),
        ) { progress -> onProgress(MODEL_PROGRESS_SHARE + progress * (1f - MODEL_PROGRESS_SHARE)) }
    }

    private fun fetchFromGithub(entry: VoiceEntry, onProgress: (Float) -> Unit) {
        val archive = File(appContext.cacheDir, "${entry.id}.tar.bz2")
        try {
            downloadFile(githubTarUrl(entry.id), archive, onProgress)
            extractTar(entry, archive)
        } finally {
            archive.delete()
        }
    }

    private fun downloadFile(url: String, target: File, onProgress: (Float) -> Unit) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.delete()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("http_$code")
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: -1L
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var received = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        if (total > 0L) onProgress((received.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (temp.length() == 0L) throw IOException("empty_response")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (error: Exception) {
            temp.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureSharedData() {
        val target = storage.sharedDataDir()
        if (File(target, "phontab").length() > 0L) return
        target.deleteRecursively()
        if (!target.mkdirs()) throw IOException("storage_unavailable")
        val rootPath = storage.rootDir().canonicalPath + File.separator
        appContext.assets.open(ESPEAK_ASSET).use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val item = zip.nextEntry ?: break
                    val file = File(storage.rootDir(), item.name)
                    if (!file.canonicalPath.startsWith(rootPath)) throw IOException("bad_asset")
                    if (item.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { output -> zip.copyTo(output) }
                    }
                }
            }
        }
        if (File(target, "phontab").length() == 0L) throw IOException("asset_extract_failed")
    }

    private fun extractTar(entry: VoiceEntry, archive: File) {
        val target = storage.voiceDir(entry.id)
        if (!target.mkdirs() && !target.isDirectory) throw IOException("storage_unavailable")
        val targetPath = target.canonicalPath + File.separator
        BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())).use { bzip ->
            TarArchiveInputStream(bzip).use { tar ->
                while (true) {
                    val item = tar.nextEntry ?: break
                    if (!tar.canReadEntryData(item)) continue
                    val name = item.name.substringAfter('/', "")
                    if (name.isEmpty() || name.startsWith(ESPEAK_DIR)) continue
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
    }

    companion object {
        private const val TAG = "LexiVoice"
        private const val USER_AGENT = "Lexi/1.0 (Android)"
        private const val ESPEAK_ASSET = "tts/espeak-ng-data.zip"
        private const val ESPEAK_DIR = "espeak-ng-data/"
        private const val MODEL_PROGRESS_SHARE = 0.97f
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val BUFFER_SIZE = 64 * 1024
    }
}
