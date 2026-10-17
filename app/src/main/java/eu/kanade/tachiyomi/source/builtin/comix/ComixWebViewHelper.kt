package eu.kanade.tachiyomi.source.builtin.comix

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.challenge.BossChallengeCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import logcat.LogPriority
import logcat.logcat

class ComixWebViewHelper(
    private val context: Context,
    private val onMaterialCaptured: ((CipherMaterial) -> Unit)? = null,
) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun runWebViewCapture(
        url: String,
        userAgent: String,
        initialHtml: String? = null,
        timeout: Duration = 25.seconds,
        buildScript: (passPayloadName: String, rejectName: String) -> String,
    ): WebViewCapture = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<WebViewCapture>()
        val mainHandler = Handler(Looper.getMainLooper())
        val destroyed = AtomicBoolean(false)

        val webView = WebView(context)
        val metrics = context.resources.displayMetrics
        webView.layoutParams = ViewGroup.LayoutParams(metrics.widthPixels, metrics.heightPixels)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, metrics.widthPixels, metrics.heightPixels)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            blockNetworkImage = false
            userAgentString = userAgent
        }

        val bridgeName = "wammyBridge" + (1000..9999).random()
        val errorBridgeName = "wammyError" + (1000..9999).random()
        val materialBridgeName = "wammyMaterial" + (1000..9999).random()
        val debugBridgeName = "wammyDebug" + (1000..9999).random()
        val passPayloadName = "passPayload" + (1000..9999).random()
        val rejectName = "rejectPayload" + (1000..9999).random()

        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun post(message: String) {
                    if (destroyed.get()) return
                    try {
                        val capture = message.parseAs<WebViewCapture>()
                        capture.material?.takeIf { it.isValid() }?.let { onMaterialCaptured?.invoke(it) }
                        deferred.complete(capture)
                    } catch (e: Exception) {
                        deferred.complete(WebViewCapture(payload = message))
                    }
                }
            },
            bridgeName,
        )

        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun post(error: String) {
                    if (destroyed.get()) return
                    deferred.completeExceptionally(Exception("WebView script error: $error"))
                }
            },
            errorBridgeName,
        )

        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun post(json: String) {
                    if (destroyed.get()) return
                    try {
                        val material = json.parseAs<CipherMaterial>()
                        if (material.isValid()) {
                            onMaterialCaptured?.invoke(material)
                        }
                    } catch (_: Exception) {}
                }
            },
            materialBridgeName,
        )

        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun log(msg: String) {
                    logcat(LogPriority.INFO) { "ComixJS: $msg" }
                }
            },
            debugBridgeName,
        )

        val captureScript = buildScript(passPayloadName, rejectName)

        val bootstrapScript = """
            (function () {
                window.__wammyLog = function(msg) {
                    try {
                        if (window.$debugBridgeName) window.$debugBridgeName.log(String(msg));
                    } catch (e) {}
                };
                window.__wammyLog('bootstrapScript initialized on ' + window.location.href);

                const captures = window.__comixCipherCaptures = (window.__comixCipherCaptures || []);
                const originalAtob = window.atob.bind(window);
                window.atob = function (value) {
                    const decoded = originalAtob(value);
                    try {
                        const bytes = Array.from(decoded, char => char.charCodeAt(0) & 255);
                        if (bytes.length === 256 || bytes.length === 24 || bytes.length === 32) {
                            captures.push(bytes);
                            window.__wammyLog('atob captured bytes len=' + bytes.length + ', total=' + captures.length);
                            const sboxes = captures.filter(item => item.length === 256).slice(0, 3);
                            const keys = captures.filter(item => item.length === 24 || item.length === 32).slice(0, 3);
                            if (sboxes.length === 3 && keys.length === 3) {
                                window.__wammyLog('all 3 sboxes and 3 keys captured!');
                                try {
                                    window.$materialBridgeName && window.$materialBridgeName.post(JSON.stringify({ sboxes, keys }));
                                } catch (e) {}
                            }
                        }
                    } catch (e) {}
                    return decoded;
                };
                window.$passPayloadName = function (payload) {
                    window.__wammyLog('passPayloadName called with payload length=' + (payload ? payload.length : 0));
                    const sboxes = captures.filter(item => item.length === 256).slice(0, 3);
                    const keys = captures.filter(item => item.length === 24 || item.length === 32).slice(0, 3);
                    const material = sboxes.length === 3 && keys.length === 3
                        ? { sboxes, keys }
                        : null;
                    window.$bridgeName.post(JSON.stringify({ payload, material }));
                };
                window.$rejectName = function (error) {
                    window.__wammyLog('rejectName called: ' + error);
                    window.$errorBridgeName.post(String(error?.message || error));
                };
            })();
        """.trimIndent()

        val emptyResponse = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                if (destroyed.get()) return
                view?.evaluateJavascript(bootstrapScript, null)
                view?.evaluateJavascript(captureScript, null)
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                if (destroyed.get()) return
                val title = view?.title.orEmpty()
                logcat(LogPriority.INFO) { "ComixWebViewHelper: onPageFinished url=$pageUrl title=$title" }
                if (title.contains("Just a moment", ignoreCase = true) || title.contains("Attention Required", ignoreCase = true)) {
                    val coordinator = BossChallengeCoordinator.instance
                    if (coordinator != null) {
                        mainHandler.post {
                            CoroutineScope(Dispatchers.Main).launch {
                                val solved = coordinator.resolveAsync(url, force = true)
                                if (solved && !destroyed.get()) {
                                    view?.reload()
                                }
                            }
                        }
                        return
                    }
                }
                view?.evaluateJavascript(bootstrapScript, null)
                view?.evaluateJavascript(captureScript, null)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val reqUrl = request?.url?.toString()?.toHttpUrlOrNull() ?: return emptyResponse
                val host = reqUrl.host
                val allowed = host.contains("comix") ||
                    host.contains("cloudflare") ||
                    host.contains("challenges") ||
                    host.contains("google") ||
                    host.contains("gstatic")
                return if (allowed) null else emptyResponse
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                destroyed.set(true)
                deferred.completeExceptionally(Exception("WebView render process gone"))
                return true
            }
        }

        // Periodic poller every 500ms
        val pollRunnable = object : Runnable {
            override fun run() {
                if (destroyed.get() || deferred.isCompleted) return
                webView.evaluateJavascript(captureScript, null)
                mainHandler.postDelayed(this, 500L)
            }
        }
        mainHandler.postDelayed(pollRunnable, 500L)

        try {
            if (!initialHtml.isNullOrBlank()) {
                webView.loadDataWithBaseURL(url, initialHtml, "text/html", "UTF-8", null)
            } else {
                webView.loadUrl(url)
            }

            withTimeout(timeout) {
                deferred.await()
            }
        } finally {
            destroyed.set(true)
            mainHandler.removeCallbacks(pollRunnable)
            webView.stopLoading()
            webView.destroy()
        }
    }
}
