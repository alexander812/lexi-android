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

class AsrManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val storage = AsrStorage(appContext)
    private val executor = Executors.newSingleThreadExecutor()
    private val listeners = mutableMapOf<String, MutableList<(Result<Unit>) -> Unit>>()
    private val states = mutableMapOf<String, AsrState>()

    var onDownloadStarted: (() -> Unit)? = null

    fun storage(): AsrStorage = storage

    fun state(entry: AsrEntry): AsrState = synchronized(this) { states[entry.id] ?: AsrState() }

    fun isInstalled(entry: AsrEntry): Boolean = storage.isInstalled(entry)

    fun isDownloading(): Boolean = synchronized(this) { states.values.any { it.downloading } }

    fun download(entry: AsrEntry, onDone: (Result<Unit>) -> Unit) {
        if (isInstalled(entry)) {
            onDone(Result.success(Unit))
            return
        }
        synchronized(this) {
            if (states[entry.id]?.downloading == true) {
                listeners.getOrPut(entry.id) { mutableListOf() } += onDone
                return
            }
            states[entry.id] = AsrState(downloading = true)
            listeners.getOrPut(entry.id) { mutableListOf() } += onDone
        }
        onDownloadStarted?.invoke()
        executor.execute {
            DownloadService.start(appContext)
            val result = runCatching { fetchArchive(entry) }
            val delivered = result.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = {
                    Log.w(TAG, "asr download failed: ${it.message}")
                    Result.failure(IOException(REASON_DOWNLOAD))
                },
            )
            val callbacks = synchronized(this) {
                states[entry.id] = if (result.isSuccess) AsrState() else AsrState(error = REASON_DOWNLOAD)
                listeners.remove(entry.id).orEmpty()
            }
            callbacks.forEach { it(delivered) }
        }
    }

    fun delete(entry: AsrEntry) {
        storage.delete(entry)
        storage.cleanup()
        synchronized(this) { states[entry.id] = AsrState() }
    }

    fun release() {
        if (isDownloading()) return

        executor.shutdownNow()
        synchronized(Companion) {
            if (instance === this) instance = null
        }
    }

    private fun fetchArchive(entry: AsrEntry) {
        val archive = File(appContext.cacheDir, "${entry.id}.tar.bz2")
        try {
            downloadFile(asrArchiveUrl(entry), archive) { progress ->
                synchronized(this) {
                    states[entry.id] = (states[entry.id] ?: AsrState()).copy(progress = progress)
                }
            }
            extract(entry, archive)
            if (!isInstalled(entry)) throw IOException("asr_incomplete")
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

    private fun extract(entry: AsrEntry, archive: File) {
        storage.cleanup()
        val target = storage.modelDir(entry)
        target.deleteRecursively()
        if (!target.mkdirs() && !target.isDirectory) throw IOException("storage_unavailable")
        val targetPath = target.canonicalPath + File.separator
        BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())).use { bzip ->
            TarArchiveInputStream(bzip).use { tar ->
                while (true) {
                    val item = tar.nextEntry ?: break
                    if (!tar.canReadEntryData(item) || item.isDirectory) continue
                    val name = item.name.substringAfterLast('/')
                    if (name !in entry.files) continue
                    val file = File(target, name)
                    if (!file.canonicalPath.startsWith(targetPath)) throw IOException("bad_archive")
                    FileOutputStream(file).use { output -> tar.copyTo(output) }
                }
            }
        }
    }

    companion object {
        @Volatile
        private var instance: AsrManager? = null

        fun get(context: Context): AsrManager =
            instance ?: synchronized(this) {
                instance ?: AsrManager(context.applicationContext).also { instance = it }
            }

        private const val TAG = "LexiAsr"
        private const val REASON_DOWNLOAD = "asr_download_failed"
        private const val USER_AGENT = "Lexi/1.0 (Android)"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val BUFFER_SIZE = 64 * 1024
    }
}
