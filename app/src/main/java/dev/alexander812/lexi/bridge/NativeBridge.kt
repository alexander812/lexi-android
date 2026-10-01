package dev.alexander812.lexi.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import dev.alexander812.lexi.ocr.ScanOutcome
import dev.alexander812.lexi.ocr.TextScanner
import dev.alexander812.lexi.speech.RecognitionService
import dev.alexander812.lexi.speech.RecognizeOutcome
import dev.alexander812.lexi.speech.SpeakOutcome
import dev.alexander812.lexi.speech.SpeechService
import dev.alexander812.lexi.speech.VOICE_CATALOG
import dev.alexander812.lexi.speech.VoiceManager
import org.json.JSONArray
import org.json.JSONObject

class NativeBridge(
    private val context: Context,
    private val webView: WebView,
    private val scanner: TextScanner,
    private val speech: SpeechService,
    private val recognition: RecognitionService,
    private val voices: VoiceManager,
) {

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
            "scanText" -> requestScan(requestId, params)
            "speak" -> requestSpeak(requestId, params)
            "recognizeSpeech" -> requestRecognize(requestId, params)
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
            respond(requestId, response)
        }
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
        private const val DEFAULT_VIBRATION_MS = 50L
        private const val MAX_VIBRATION_MS = 10_000L
    }
}
