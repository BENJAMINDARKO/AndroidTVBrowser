package com.example.tvbrowser

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

class AdBlockingWebViewClient(
    private val engine: AdBlockEngine,
    private val onPageStartedCallback: (url: String) -> Unit,
    private val onPageFinishedCallback: (url: String) -> Unit,
    private val onMediaIntercepted: ((url: String) -> Unit)? = null
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url.toString()
        val host = request.url.host ?: ""

        // Check for media stream loads
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains(".m3u8") || lowerUrl.contains(".mp4") || lowerUrl.contains(".mp3") || lowerUrl.contains("/m3u8") || lowerUrl.contains("playlist.m3u8")) {
            onMediaIntercepted?.invoke(url)
        }

        if (engine.shouldBlock(url, host)) {
            engine.incrementBlocked(view.context)
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                ByteArrayInputStream("".toByteArray())
            )
        }

        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url != null) {
            onPageStartedCallback(url)
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (url != null) {
            onPageFinishedCallback(url)
        }
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        view?.evaluateJavascript("""
            (function() {
                function fixIframes() {
                    document.querySelectorAll('iframe').forEach(function(el) {
                        if (el.hasAttribute('sandbox')) {
                            el.removeAttribute('sandbox');
                            el.removeAttribute('referrerpolicy');
                            el.setAttribute('allow',
                                'autoplay; fullscreen; encrypted-media; picture-in-picture; ' +
                                'accelerometer; clipboard-write; gyroscope');
                            el.setAttribute('allowfullscreen', '');
                            el.setAttribute('allowpaymentrequest', '');
                            var src = el.src;
                            el.src = '';
                            el.src = src;
                        }
                    });
                }
                fixIframes();
                var obs = new MutationObserver(function() { fixIframes(); });
                if (document.documentElement) obs.observe(document.documentElement, { childList: true, subtree: true });
            })();
        """.trimIndent(), null)
    }
}
