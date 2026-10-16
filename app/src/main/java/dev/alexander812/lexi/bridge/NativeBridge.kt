package dev.alexander812.lexi.bridge

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import dev.alexander812.lexi.ocr.ScanOutcome
import dev.alexander812.lexi.ocr.TextScanner
import dev.alexander812.lexi.speech.ASR_ENTRIES
import dev.alexander812.lexi.speech.AsrManager
import dev.alexander812.lexi.speech.RecognitionService
import dev.alexander812.lexi.speech.RecognizeOutcome
import dev.alexander812.lexi.speech.SpeakOutcome
import dev.alexander812.lexi.speech.SpeechService
import dev.alexander812.lexi.speech.VOICE_CATALOG
import dev.alexander812.lexi.speech.VoiceManager
import dev.alexander812.lexi.speech.findAsrEntryById
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class NativeBridge(
    private val context: Context,
    private val webView: WebView,
    private val scanner: TextScanner,
    private val speech: SpeechService,
    private val recognition: RecognitionService,
    private val asr: AsrManager,
    private val voices: VoiceManager,
) {

    @Volatile
    private var lastScanResult: JSONObject? = null

    @Volatile
    private var scanPending = false

    private val sessionToken = UUID.randomUUID().toString()

    @JavascriptInterface
    fun sessionToken(): String = sessionToken

    @JavascriptInterface
    fun isScanPending(): Boolean = scanPending

    @JavascriptInterface
    @Synchronized
    fun finishScan() {
        scanPending = false
        lastScanResult = null
    }

    @JavascriptInterface
    fun hasRecognition(): Boolean {
        val available = runCatching { recognition.isAvailable() }.getOrDefault(false)
        Log.i(TAG, "hasRecognition: $available")
        return available
    }

    @JavascriptInterface
    fun call(requestId: String, method: String, paramsJson: String) {
        val params = parseParams(paramsJson)
        when (method) {
            "vibrate" -> respond(
                requestId,
                runCatching { vibrate(params) }.getOrElse { errorResponse(it.message ?: "bridge_error") },
            )
            "deviceInfo" -> respond(
                requestId,
                runCatching { deviceInfo() }.getOrElse { errorResponse(it.message ?: "bridge_error") },
            )
            "keepScreenOn" -> respond(
                requestId,
                runCatching { keepScreenOn(params) }.getOrElse { errorResponse(it.message ?: "bridge_error") },
            )
            "scanText" -> requestScan(requestId, params)
            "speak" -> requestSpeak(requestId, params)
            "recognizeSpeech" -> requestRecognize(requestId, params)
            "cancelRecognizeSpeech" -> {
                recognition.cancel()
                respond(requestId, successResponse(JSONObject().put("cancelled", true)))
            }
            "asrStatus" -> respond(
                requestId,
                runCatching { asrStatus() }.getOrElse { errorResponse(it.message ?: "bridge_error") },
            )
            "downloadAsr" -> requestAsrDownload(requestId, params)
            "deleteAsr" -> requestAsrDelete(requestId, params)
            "ttsVoices" -> respond(
                requestId,
                runCatching { voicesInfo() }.getOrElse { errorResponse(it.message ?: "bridge_error") },
            )
            "downloadVoice" -> requestVoiceDownload(requestId, params)
            "deleteVoice" -> requestVoiceDelete(requestId, params)
            else -> respond(requestId, errorResponse("unknown_method: $method"))
        }
    }

    private fun requestScan(requestId: String, params: JSONObject) {
        val lang = params.optString("lang").lowercase()
        if (lang.isBlank()) {
            respond(requestId, errorResponse("language_required"))
            return
        }
        scanPending = true
        lastScanResult = null
        scanner.scan(lang) { outcome ->
            val response = when (outcome) {
                is ScanOutcome.Success -> successResponse(
                    JSONObject()
                        .put("text", outcome.text)
                        .put("confidence", outcome.confidence)
                        .put("cancelled", false),
                )
                ScanOutcome.Cancelled -> successResponse(
                    JSONObject().put("text", "").put("cancelled", true),
                )
                is ScanOutcome.Failure -> errorResponse(outcome.reason)
            }
            lastScanResult = response
            Log.i(TAG, "scan result stored")
            respond(requestId, response)
        }
    }

    @JavascriptInterface
    @Synchronized
    fun consumeScanResult(): String {
        val result = lastScanResult
        Log.i(TAG, "consumeScanResult: ${if (result == null) "miss" else "hit"}")

        if (result == null) return ""

        lastScanResult = null
        scanPending = false
        return result.toString()
    }

    private fun requestSpeak(requestId: String, params: JSONObject) {
        val text = params.optString("text")
        val lang = params.optString("lang")
        speech.speak(text, lang) { outcome ->
            val response = when (outcome) {
                is SpeakOutcome.Success -> successResponse(
                    JSONObject()
                        .put("spoken", true)
                        .put("utteranceId", outcome.utteranceId),
                )
                is SpeakOutcome.Failure -> errorResponse(outcome.reason)
            }
            respond(requestId, response)
        }
    }

    private fun requestRecognize(requestId: String, params: JSONObject) {
        val lang = params.optString("lang").lowercase()
        recognition.recognize(lang) { outcome ->
            val response = when (outcome) {
                is RecognizeOutcome.Success -> {
                    val alternatives = JSONArray()
                    outcome.alternatives.forEach { alternatives.put(it) }
                    successResponse(
                        JSONObject()
                            .put("transcript", outcome.transcript)
                            .put("confidence", outcome.confidence ?: JSONObject.NULL)
                            .put("alternatives", alternatives),
                    )
                }
                is RecognizeOutcome.Failure -> errorResponse(outcome.reason)
            }
            respond(requestId, response)
        }
    }

    private fun asrStatus(): JSONObject {
        val array = JSONArray()
        ASR_ENTRIES.forEach { entry ->
            val state = asr.state(entry)
            val languages = JSONArray()
            entry.languages.sorted().forEach { languages.put(it) }
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("title", entry.title)
                    .put("description", entry.description)
                    .put("sizeBytes", entry.sizeBytes)
                    .put("languages", languages)
                    .put("installed", asr.isInstalled(entry))
                    .put("downloading", state.downloading)
                    .put("progress", state.progress.toDouble())
                    .put("error", state.error ?: JSONObject.NULL),
            )
        }
        return successResponse(JSONObject().put("artifacts", array))
    }

    private fun requestAsrDownload(requestId: String, params: JSONObject) {
        val entry = findAsrEntryById(params.optString("id"))
        if (entry == null) {
            respond(requestId, errorResponse("unknown_artifact"))
            return
        }
        asr.download(entry) { result ->
            val response = result.fold(
                onSuccess = { successResponse(JSONObject().put("installed", true)) },
                onFailure = { errorResponse(it.message ?: "asr_download_failed") },
            )
            respond(requestId, response)
        }
    }

    private fun requestAsrDelete(requestId: String, params: JSONObject) {
        val entry = findAsrEntryById(params.optString("id"))
        if (entry == null) {
            respond(requestId, errorResponse("unknown_artifact"))
            return
        }
        asr.delete(entry)
        recognition.onAsrDeleted()
        respond(requestId, successResponse(JSONObject().put("deleted", true)))
    }

    private fun voicesInfo(): JSONObject {
        val array = JSONArray()
        VOICE_CATALOG.forEach { entry ->
            val state = voices.state(entry.lang)
            array.put(
                JSONObject()
                    .put("lang", entry.lang)
                    .put("id", entry.id)
                    .put("title", entry.title)
                    .put("sizeBytes", entry.sizeBytes)
                    .put("installed", voices.isInstalled(entry.lang))
                    .put("downloading", state.downloading)
                    .put("progress", state.progress.toDouble())
                    .put("error", state.error ?: JSONObject.NULL),
            )
        }
        return successResponse(JSONObject().put("voices", array))
    }

    private fun requestVoiceDownload(requestId: String, params: JSONObject) {
        val lang = params.optString("lang").lowercase()
        if (lang.isBlank()) {
            respond(requestId, errorResponse("language_required"))
            return
        }
        voices.download(lang) { result ->
            val response = result.fold(
                onSuccess = { successResponse(JSONObject().put("installed", true)) },
                onFailure = { errorResponse(it.message ?: "download_failed") },
            )
            respond(requestId, response)
        }
    }

    private fun requestVoiceDelete(requestId: String, params: JSONObject) {
        val lang = params.optString("lang").lowercase()
        if (lang.isBlank()) {
            respond(requestId, errorResponse("language_required"))
            return
        }
        if (!voices.delete(lang)) {
            respond(requestId, errorResponse("unsupported_language"))
            return
        }
        speech.onVoiceDeleted(lang)
        respond(requestId, successResponse(JSONObject().put("deleted", true)))
    }

    private fun parseParams(paramsJson: String): JSONObject =
        runCatching { JSONObject(paramsJson) }.getOrDefault(JSONObject())

    private fun vibrate(params: JSONObject): JSONObject {
        val durationMs = params.optLong("durationMs", DEFAULT_VIBRATION_MS)
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return errorResponse("no_vibrator")
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                durationMs.coerceIn(1L, MAX_VIBRATION_MS),
                VibrationEffect.DEFAULT_AMPLITUDE,
            ),
        )
        return successResponse(JSONObject().put("vibratedMs", durationMs))
    }

    private fun keepScreenOn(params: JSONObject): JSONObject {
        val enabled = params.optBoolean("enabled", false)
        val activity = context as? Activity ?: return errorResponse("activity_unavailable")
        activity.runOnUiThread {
            if (enabled) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        Log.i(TAG, "keepScreenOn: $enabled")
        return successResponse(JSONObject().put("enabled", enabled))
    }

    private fun deviceInfo(): JSONObject = successResponse(
        JSONObject()
            .put("platform", "android")
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("appVersion", appVersion()),
    )

    private fun appVersion(): String {
        val packageManager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, 0)
        }
        return info.versionName ?: ""
    }

    private fun respond(requestId: String, response: JSONObject) {
        val script = "window.__nativeBridgeResolve(${JSONObject.quote(requestId)}, ${JSONObject.quote(response.toString())})"
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun successResponse(data: JSONObject): JSONObject =
        JSONObject().put("ok", true).put("data", data)

    private fun errorResponse(error: String): JSONObject =
        JSONObject().put("ok", false).put("error", error)

    companion object {
        const val NAME = "AndroidBridge"
        private const val TAG = "NativeBridge"
        private const val DEFAULT_VIBRATION_MS = 50L
        private const val MAX_VIBRATION_MS = 10_000L
    }
}
