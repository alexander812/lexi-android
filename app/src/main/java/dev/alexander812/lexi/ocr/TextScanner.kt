package dev.alexander812.lexi.ocr

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

sealed interface ScanOutcome {
    data class Success(val text: String, val confidence: Int) : ScanOutcome
    data object Cancelled : ScanOutcome
    data class Failure(val reason: String) : ScanOutcome
}

class TextScanner(private val activity: ComponentActivity) {

    private val executor = Executors.newSingleThreadExecutor()
    private var pending: PendingScan? = null

    private val takePicture = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> handleCameraResult(result) }

    fun scan(lang: String, onOutcome: (ScanOutcome) -> Unit) {
        activity.runOnUiThread {
            if (pending != null) {
                onOutcome(ScanOutcome.Failure("busy"))
                return@runOnUiThread
            }
            val modelLang = MODEL_ALIASES[lang.lowercase()]
            if (modelLang == null) {
                onOutcome(ScanOutcome.Failure("unsupported_language"))
                return@runOnUiThread
            }
            val photo = createPhotoFile()
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", photo)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            pending = PendingScan(modelLang, photo, onOutcome)
            try {
                takePicture.launch(intent)
            } catch (error: ActivityNotFoundException) {
                pending = null
                photo.delete()
                onOutcome(ScanOutcome.Failure("no_camera_app"))
            }
        }
    }

    fun release() {
        executor.shutdownNow()
    }

    private fun handleCameraResult(result: ActivityResult) {
        val scan = pending ?: return
        pending = null
        if (result.resultCode != Activity.RESULT_OK || !scan.photo.exists() || scan.photo.length() == 0L) {
            scan.photo.delete()
            scan.onOutcome(ScanOutcome.Cancelled)
            return
        }
        executor.execute {
            val outcome = try {
                val (text, confidence) = recognize(scan)
                ScanOutcome.Success(text.trim(), confidence)
            } catch (error: Exception) {
                ScanOutcome.Failure(error.message ?: "recognition_failed")
            }
            scan.photo.delete()
            scan.onOutcome(outcome)
        }
    }

    private fun recognize(scan: PendingScan): Pair<String, Int> {
        ensureModel(scan.lang)
        val bitmap = decodeBitmap(scan.photo)
        val oriented = applyExifOrientation(scan.photo, bitmap)
        val tess = TessBaseAPI()
        try {
            val dataPath = File(activity.filesDir, TESSDATA_PARENT).absolutePath
            if (!tess.init(dataPath, scan.lang)) throw IOException("tesseract_init_failed")
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            tess.setImage(oriented)
            val text = tess.getUTF8Text()
            return text to tess.meanConfidence()
        } finally {
            tess.recycle()
            oriented.recycle()
            if (oriented !== bitmap) bitmap.recycle()
        }
    }

    private fun ensureModel(lang: String): File {
        val target = File(tessdataDir(), "$lang.traineddata")
        if (target.length() > 0L) return target
        if (copyAssetModel(lang, target)) return target
        downloadModel(lang, target)
        return target
    }

    private fun tessdataDir(): File =
        File(activity.filesDir, "$TESSDATA_PARENT/$TESSDATA_DIR").apply { mkdirs() }

    private fun copyAssetModel(lang: String, target: File): Boolean {
        val fileName = "$lang.traineddata"
        val bundled = activity.assets.list(TESSDATA_ASSETS).orEmpty()
        if (!bundled.contains(fileName)) return false
        activity.assets.open("$TESSDATA_ASSETS/$fileName").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return true
    }

    private fun downloadModel(lang: String, target: File) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        val url = MODEL_URL_TEMPLATE.replace(MODEL_URL_PLACEHOLDER, lang)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("model_download_failed")
            connection.inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() == 0L) throw IOException("model_download_failed")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (error: Exception) {
            temp.delete()
            throw if (error is IOException) error else IOException("model_download_failed", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeBitmap(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options) ?: throw IOException("decode_failed")
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var max = maxOf(width, height)
        while (max / 2 >= maxDimension) {
            sample *= 2
            max /= 2
        }
        return sample
    }

    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = ExifInterface(file).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun createPhotoFile(): File {
        val dir = File(activity.cacheDir, "scans").apply { mkdirs() }
        return File(dir, "scan_${System.currentTimeMillis()}.jpg")
    }

    private data class PendingScan(
        val lang: String,
        val photo: File,
        val onOutcome: (ScanOutcome) -> Unit,
    )

    companion object {
        private const val TESSDATA_ASSETS = "tessdata"
        private const val TESSDATA_DIR = "tessdata"
        private const val TESSDATA_PARENT = "tesseract"
        private const val MAX_DIMENSION = 2000
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MODEL_URL_PLACEHOLDER = "{lang}"
        private const val MODEL_URL_TEMPLATE =
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.0.0/{lang}.traineddata"

        private val MODEL_ALIASES = mapOf(
            "ru" to "rus",
            "en" to "eng",
            "es" to "spa",
            "fr" to "fra",
            "it" to "ita",
            "de" to "deu",
            "zh" to "chi_sim",
        )
    }
}
