package dev.alexander812.lexi

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import dev.alexander812.lexi.bridge.NativeBridge
import dev.alexander812.lexi.ocr.TextScanner
import dev.alexander812.lexi.speech.LocalTts
import dev.alexander812.lexi.speech.SpeechService
import dev.alexander812.lexi.speech.SpeechSynthesizer
import dev.alexander812.lexi.speech.VoiceManager

private const val ASSET_DOMAIN = "appassets.androidplatform.net"
private const val LOCAL_START_URL = "https://$ASSET_DOMAIN/assets/www/index.html"

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var scanner: TextScanner
    private lateinit var speech: SpeechService
    private lateinit var voices: VoiceManager

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webView = WebView(this)
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
        webView.addJavascriptInterface(NativeBridge(this, webView, scanner, speech, voices), NativeBridge.NAME)

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

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        webView.loadUrl(startUrl)
    }

    override fun onDestroy() {
        scanner.release()
        speech.release()
        voices.release()
        webView.destroy()
        super.onDestroy()
    }
}
