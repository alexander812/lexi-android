package dev.alexander812.lexi

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import dev.alexander812.lexi.bridge.NativeBridge
import dev.alexander812.lexi.ocr.TextScanner
import dev.alexander812.lexi.speech.AsrManager
import dev.alexander812.lexi.speech.LocalTts
import dev.alexander812.lexi.speech.OfflineAsr
import dev.alexander812.lexi.speech.RecognitionService
import dev.alexander812.lexi.speech.SpeechService
import dev.alexander812.lexi.speech.SpeechSynthesizer
import dev.alexander812.lexi.speech.VoiceManager

private const val ASSET_DOMAIN = "appassets.androidplatform.net"
private const val LOCAL_START_URL = "https://$ASSET_DOMAIN/assets/www/index.html"
private const val BACK_HANDLER_SCRIPT = "(window.__lexiBack && window.__lexiBack()) === true"

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var handlingBack = false
    private lateinit var scanner: TextScanner
    private lateinit var speech: SpeechService
    private lateinit var recognition: RecognitionService
    private lateinit var asr: AsrManager
    private lateinit var voices: VoiceManager

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#050505"))
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        setContentView(webView)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        scanner = TextScanner(this)
        voices = VoiceManager(this)
        speech = SpeechService(SpeechSynthesizer(this), voices, LocalTts(voices.storage()))
        asr = AsrManager(this)
        recognition = RecognitionService(this, OfflineAsr(asr.storage()), asr)
        webView.addJavascriptInterface(
            NativeBridge(this, webView, scanner, speech, recognition, asr, voices),
            NativeBridge.NAME,
        )

        val startUrl = BuildConfig.WEB_START_URL.ifBlank { LOCAL_START_URL }
        val trustedHost = Uri.parse(startUrl).host

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url
                if (url.host == ASSET_DOMAIN || url.host == trustedHost) return false
                startActivity(Intent(Intent.ACTION_VIEW, url))
                return true
            }
        }

        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (handlingBack) return

                handlingBack = true
                webView.evaluateJavascript(BACK_HANDLER_SCRIPT) { result ->
                    handlingBack = false

                    if (result == "true") return@evaluateJavascript

                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        webView.loadUrl(startUrl)
    }

    override fun onDestroy() {
        scanner.release()
        speech.release()
        recognition.release()
        asr.release()
        voices.release()
        webView.destroy()
        super.onDestroy()
    }
}
