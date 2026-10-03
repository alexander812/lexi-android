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
import java.util.concurrent.Executors

data class AsrState(
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
)

class AsrManager(context: Context) {

    private val appContext = context.applicationContext
    private val storage = AsrStorage(appContext)
    private val executor = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<(Result<Unit>) -> Unit>()
    private var state = AsrState()

    fun storage(): AsrStorage = storage

    fun state(): AsrState = synchronized(this) { state }

    fun isInstalled(): Boolean = storage.isInstalled(ASR_ENTRY)

    fun download(onDone: (Result<Unit>) -> Unit) {
        if (isInstalled()) {
            onDone(Result.success(Unit))
            return
        }
        synchronized(this) {
            if (state.downloading) {
                listeners += onDone
                return
            }
            state = AsrState(downloading = true)
            listeners += onDone
        }
        executor.execute {
            val result = runCatching { fetchArchive() }
            val delivered = result.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = {
                    Log.w(TAG, "asr download failed: ${it.message}")
                    Result.failure(IOException(REASON_DOWNLOAD))
                },
            )
            val callbacks = synchronized(this) {
                state = if (result.isSuccess) AsrState() else AsrState(error = REASON_DOWNLOAD)
                val pending = listeners.toList()
                listeners.clear()
                pending
            }
            callbacks.forEach { it(delivered) }
        }
    }

    fun delete() {
        storage.delete(ASR_ENTRY)
        synchronized(this) { state = AsrState() }
    }

    fun release() {
        executor.shutdownNow()
    }

    private fun fetchArchive() {
        val archive = File(appContext.cacheDir, "${ASR_ENTRY.id}.tar.bz2")
        try {
            downloadFile(asrArchiveUrl(ASR_ENTRY), archive) { progress ->
                synchronized(this) { state = state.copy(progress = progress) }
            }
            extract(archive)
            if (!isInstalled()) throw IOException("asr_incomplete")
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

    private fun extract(archive: File) {
        val target = storage.modelDir(ASR_ENTRY)
        target.deleteRecursively()
        if (!target.mkdirs() && !target.isDirectory) throw IOException("storage_unavailable")
        val targetPath = target.canonicalPath + File.separator
        BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())).use { bzip ->
            TarArchiveInputStream(bzip).use { tar ->
                while (true) {
                    val item = tar.nextEntry ?: break
                    if (!tar.canReadEntryData(item) || item.isDirectory) continue
                    val name = item.name.substringAfterLast('/')
                    if (name != ASR_MODEL_FILE && name != ASR_TOKENS_FILE) continue
                    val file = File(target, name)
                    if (!file.canonicalPath.startsWith(targetPath)) throw IOException("bad_archive")
                    FileOutputStream(file).use { output -> tar.copyTo(output) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "LexiAsr"
        private const val REASON_DOWNLOAD = "asr_download_failed"
        private const val USER_AGENT = "Lexi/1.0 (Android)"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val BUFFER_SIZE = 64 * 1024
    }
}
