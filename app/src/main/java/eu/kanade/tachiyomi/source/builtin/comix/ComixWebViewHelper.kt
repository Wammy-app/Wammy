package eu.kanade.tachiyomi.source.builtin.comix

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.challenge.BossChallengeCoordinator
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.setUserAgent
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ComixWebViewHelper(
    private val context: Context,
    private val onMaterialCaptured: ((CipherMaterial) -> Unit)? = null,
) {
    private val chapterListPacer = RequestPacer(minGapMs = 2500, maxGapMs = 5000)

    private class RequestPacer(private val minGapMs: Long, private val maxGapMs: Long) {
        private var nextNanos = 0L

        fun waitForSlot() {
            val waitNanos = synchronized(this) {
                val now = System.nanoTime()
                val start = maxOf(now, nextNanos)
                nextNanos = start + kotlin.random.Random.nextLong(minGapMs, maxGapMs) * 1_000_000
                start - now
            }
            if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000)
        }
    }

    companion object {
        private val captureMutex = Mutex()

        fun randomName(): String = (1..(10..20).random())
            .map { (('a'..'z') + ('A'..'Z')).random() }
            .joinToString("")
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun runWebViewCapture(
        url: String,
        userAgent: String,
        initialHtml: String? = null,
        timeout: Duration = 120.seconds,
        initializationScript: String? = null,
        pollScript: ((passPayloadName: String) -> String)? = null,
        pageScript: ((passPayloadName: String, rejectName: String, touchName: String) -> String)? = null,
    ): WebViewCapture = captureMutex.withLock {
        delay(250)
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<WebViewCapture>()
            val mainHandler = Handler(Looper.getMainLooper())
            val destroyed = AtomicBoolean(false)

        logcat(LogPriority.INFO) { "ComixWebViewHelper: Starting capture for $url" }
        CookieManager.getInstance().flush()

        val webView = WebView(context)
        val metrics = context.resources.displayMetrics
        webView.layoutParams = ViewGroup.LayoutParams(metrics.widthPixels, metrics.heightPixels)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, metrics.widthPixels, metrics.heightPixels)

        webView.setDefaultSettings()
        webView.settings.setSupportMultipleWindows(true)
        webView.setUserAgent(userAgent)

        val bridgeName = randomName()
        val errorBridgeName = randomName()
        val materialBridgeName = randomName()
        val debugBridgeName = randomName()
        val touchBridgeName = randomName()
        val touchName = randomName()
        val passPayloadName = randomName()
        val rejectName = randomName()

        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun post(message: String) {
                    if (destroyed.get()) return
                    try {
                        val capture = message.parseAs<WebViewCapture>()
                        capture.material?.takeIf { it.isValid() }?.let { onMaterialCaptured?.invoke(it) }
                        deferred.complete(capture)
                    } catch (_: Exception) {
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
                    logcat(LogPriority.WARN) { "ComixWebView: Received error from page: $error" }
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
                            logcat(LogPriority.INFO) { "ComixWebView: Cipher material captured directly via bridge" }
                            onMaterialCaptured?.invoke(material)
                            if (pollScript == null && pageScript == null) {
                                deferred.complete(WebViewCapture(payload = "", material = material))
                            }
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

        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun postMessage(message: String) {
                    if (destroyed.get()) return
                    val points = message.split(',').mapNotNull { it.toFloatOrNull() }
                    mainHandler.post {
                        if (destroyed.get()) return@post
                        when (points.size) {
                            2 -> webView.tap(points[0], points[1])
                            4 -> webView.swipe(points[0], points[1], points[2], points[3])
                            else -> logcat(LogPriority.WARN) { "ComixWebView: Bad touch coordinates: $message" }
                        }
                    }
                }
            },
            touchBridgeName,
        )

        val capturePollScript = pollScript?.invoke(passPayloadName)
        val singlePageScript = pageScript?.invoke(passPayloadName, rejectName, touchName)
        val startFlag = randomName()

        val bootstrapScript = """
            (() => {
                if (window.top !== window || window['$startFlag']) return;
                window['$startFlag'] = true;

                window['$touchName'] = function (points) {
                    try {
                        if (window['$touchBridgeName']) window['$touchBridgeName'].postMessage(points.join(","));
                    } catch (e) {}
                };

                window['$passPayloadName'] = function (payload) {
                    try {
                        window['$bridgeName'].post(JSON.stringify({
                            payload: typeof payload === 'string' ? payload : JSON.stringify(payload),
                            material: null
                        }));
                    } catch (e) {
                        window['$bridgeName'].post(JSON.stringify({
                            payload: String(payload),
                            material: null
                        }));
                    }
                };

                window['$rejectName'] = function (error) {
                    try {
                        window['$errorBridgeName'].post(String(error?.message || error));
                    } catch (e) {}
                };

                ${singlePageScript.orEmpty()}
            })();
            ${initializationScript.orEmpty()}
        """.trimIndent()

        val pageScriptInjected = AtomicBoolean(false)

        val navHeaders = mapOf(
            "Referer" to "https://comix.to/",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "document",
        )

        val allowedOrigins = setOf("https://comix.to", "https://*.comix.to", "https://comix.ws", "https://*.comix.ws")
        val supportsDocStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (supportsDocStart) {
            WebViewCompat.addDocumentStartJavaScript(webView, bootstrapScript, allowedOrigins)
            if (capturePollScript != null) {
                WebViewCompat.addDocumentStartJavaScript(webView, capturePollScript, allowedOrigins)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val msg = consoleMessage?.message().orEmpty()
                val line = consoleMessage?.lineNumber() ?: 0
                val src = consoleMessage?.sourceId().orEmpty()
                logcat(LogPriority.INFO) { "ComixWebView Console: [$line] $msg (src=$src)" }
                return true
            }
        }

        val emptyResponse = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                if (destroyed.get()) return
                if (!supportsDocStart) {
                    view?.evaluateJavascript(bootstrapScript, null)
                    if (capturePollScript != null) {
                        view?.evaluateJavascript(capturePollScript, null)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                if (destroyed.get()) return
                val title = view?.title.orEmpty()
                logcat(LogPriority.INFO) { "ComixWebViewHelper: onPageFinished url=$pageUrl title=$title" }
                val isChallenge = title.contains("Just a moment", ignoreCase = true) ||
                    title.contains("Attention Required", ignoreCase = true) ||
                    title.contains("Cloudflare", ignoreCase = true)
                if (isChallenge) {
                    logcat(LogPriority.INFO) { "ComixWebViewHelper: Challenge detected on $pageUrl ($title), awaiting in-page solve_captcha" }
                    return
                }
                if (title.contains("Third-party app", ignoreCase = true) || pageUrl?.contains("third-party", ignoreCase = true) == true) {
                    logcat(LogPriority.ERROR) { "ComixWebViewHelper: Site returned third-party app block page ($title)" }
                    deferred.completeExceptionally(Exception("Comix blocked: Third-party apps are not supported"))
                    return
                }
                if (pageUrl?.startsWith("data:", ignoreCase = true) == true) {
                    logcat(LogPriority.INFO) { "ComixWebViewHelper: Skipping interim data: URL in onPageFinished" }
                    return
                }

                CookieManager.getInstance().flush()

                if (!supportsDocStart) {
                    view?.evaluateJavascript(bootstrapScript, null)
                }
                if (capturePollScript != null) {
                    view?.evaluateJavascript(capturePollScript, null)
                }

                if (singlePageScript == null && capturePollScript == null) {
                    view?.evaluateJavascript(
                        """
                        (function() {
                            try {
                                const raw = document.querySelector('script#initial-data')?.textContent;
                                if (raw) return raw;
                            } catch(e) {}
                            return null;
                        })()
                        """.trimIndent(),
                    ) { rawJson ->
                        if (!deferred.isCompleted && !rawJson.isNullOrBlank() && rawJson != "null") {
                            val clean = try {
                                comixJson.parseToJsonElement(rawJson).jsonPrimitive.content
                            } catch (_: Exception) {
                                if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                                    rawJson.substring(1, rawJson.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                                } else {
                                    rawJson
                                }
                            }
                            logcat(LogPriority.INFO) { "ComixWebViewHelper: Extracted initial-data (${clean.length} chars) on page finished" }
                            deferred.complete(WebViewCapture(payload = clean))
                        }
                    }
                }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val reqUrl = request?.url?.toString()?.toHttpUrlOrNull() ?: return null
                val path = reqUrl.encodedPath.lowercase()
                logcat(LogPriority.INFO) { "ComixWebView req: ${reqUrl.encodedPath}" }
                if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
                    path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".mp4") ||
                    path.endsWith(".svg") || path.endsWith(".woff") || path.endsWith(".woff2") ||
                    path.endsWith(".ttf")
                ) {
                    return emptyResponse
                }

                if (path.startsWith("/api/v1/client-errors") ||
                    path.startsWith("/api/v1/threads/") ||
                    path.startsWith("/assets/uploads/")
                ) {
                    return emptyResponse
                }

                if (path.contains("/manga/") && path.endsWith("/chapters")) {
                    chapterListPacer.waitForSlot()
                }

                val isFrameLoad = request?.requestHeaders?.get("Accept").orEmpty().contains("text/html")
                if (reqUrl.encodedPath == "/@waf/challenge" && isFrameLoad) {
                    val solverJs = ComixScripts.getSolveCaptcha(context)
                    val page = "<script>($solverJs)(parent.$rejectName)</script>"
                    return WebResourceResponse("text/html", "utf-8", page.byteInputStream())
                }

                val host = reqUrl.host.lowercase()
                val allowed = host.isEmpty() ||
                    host == "comix.to" ||
                    host.endsWith(".comix.to") ||
                    host == "comix.ws" ||
                    host.endsWith(".comix.ws") ||
                    host == "challenges.cloudflare.com" ||
                    host.endsWith(".cloudflare.com")
                if (!allowed) {
                    return emptyResponse
                }

                return null
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                destroyed.set(true)
                deferred.completeExceptionally(Exception("WebView render process gone"))
                return true
            }
        }

        // Periodic poller ONLY when capturePollScript is set
        val pollRunnable = object : Runnable {
            override fun run() {
                if (destroyed.get() || deferred.isCompleted) return

                if (capturePollScript != null) {
                    webView.evaluateJavascript(capturePollScript) { result ->
                        logcat(LogPriority.DEBUG) { "ComixWebView Poll eval: ${result?.take(100)}" }
                        if (!deferred.isCompleted && !result.isNullOrBlank() && result != "null" && result != "\"\"") {
                            logcat(LogPriority.INFO) { "ComixWebView: Poll returned result length=${result.length}" }
                            val clean = try {
                                comixJson.parseToJsonElement(result).jsonPrimitive.content
                            } catch (_: Exception) {
                                if (result.startsWith("\"") && result.endsWith("\"")) {
                                    result.substring(1, result.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                                } else {
                                    result
                                }
                            }
                            try {
                                val capture = clean.parseAs<WebViewCapture>()
                                capture.material?.takeIf { it.isValid() }?.let { onMaterialCaptured?.invoke(it) }
                                deferred.complete(capture)
                            } catch (_: Exception) {
                                try {
                                    deferred.complete(WebViewCapture(payload = clean))
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
                mainHandler.postDelayed(this, 300L)
            }
        }
        if (capturePollScript != null) {
            mainHandler.postDelayed(pollRunnable, 300L)
        }

        try {
            if (!initialHtml.isNullOrBlank()) {
                logcat(LogPriority.INFO) { "ComixWebViewHelper: Loading initialHtml with base $url (len=${initialHtml.length})" }
                webView.loadDataWithBaseURL(url, initialHtml, "text/html", "UTF-8", url)
            } else {
                logcat(LogPriority.INFO) { "ComixWebViewHelper: Loading url $url" }
                webView.loadUrl(url, navHeaders)
            }

            withTimeout(timeout) {
                deferred.await()
            }
        } finally {
            destroyed.set(true)
            if (capturePollScript != null) {
                mainHandler.removeCallbacks(pollRunnable)
            }
            webView.stopLoading()
            webView.destroy()
        }
    }
}
}
