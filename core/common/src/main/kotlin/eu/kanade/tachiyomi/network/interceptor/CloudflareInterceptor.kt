package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toast
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    override fun shouldIntercept(response: Response): Boolean {
        // Only 403 and 503 can be Cloudflare challenge responses
        if (response.code !in ERROR_CODES) return false

        val serverHeader = response.header("Server").orEmpty().lowercase()
        val isCloudflareServer = SERVER_CHECK.any { serverHeader.contains(it) }
        val hasCfHeaders = response.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true ||
            response.header("cf-ray") != null ||
            response.header("cf-chl-bypass") != null

        if (!isCloudflareServer && !hasCfHeaders) {
            return false
        }

        if (response.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true) {
            return true
        }

        val bodySample = try {
            response.peekBody(256 * 1024L).string()
        } catch (_: Exception) {
            ""
        }

        if (bodySample.contains("_cf_chl_opt") ||
            bodySample.contains("/challenge-platform/") ||
            bodySample.contains("challenges.cloudflare.com")
        ) {
            return true
        }

        val document = Jsoup.parse(bodySample, response.request.url.toString())
        val title = document.title()
        if (title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Attention Required", ignoreCase = true)
        ) {
            return true
        }

        return document.getElementById("challenge-error-title") != null ||
            document.getElementById("challenge-error-text") != null ||
            document.getElementById("challenge-form") != null ||
            document.getElementById("challenge-stage") != null ||
            document.getElementById("turnstile-wrapper") != null ||
            document.getElementsByClass("cf-turnstile").isNotEmpty()
    }

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response {
        try {
            response.close()
            val oldCookie = cookieManager.get(request.url)
                .firstOrNull { it.name == "cf_clearance" }
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            resolveWithWebView(request, oldCookie)

            return chain.proceed(request)
        }
        // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
        // we don't crash the entire app
        catch (e: CloudflareBypassException) {
            throw IOException(context.stringResource(MR.strings.information_cloudflare_bypass_failure), e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?) {
        // We need to lock this thread until the WebView finds the challenge solution url, because
        // OkHttp doesn't support asynchronous interceptors.
        val latch = CountDownLatch(1)

        var webview: WebView? = null
        var cloudflareBypassed = false
        var isWebViewOutdated = false

        val origRequestUrl = originalRequest.url.toString()
        val httpUrl = origRequestUrl.toHttpUrl()
        val headers = parseHeaders(originalRequest.headers)

        fun isClearancePassed(): Boolean {
            val clearance = cookieManager.get(httpUrl)
                .firstOrNull { it.name == "cf_clearance" && it.value.isNotBlank() }
            return clearance != null && (oldCookie == null || clearance.value != oldCookie.value)
        }

        executor.execute {
            try {
                webview = createWebView(originalRequest).apply {
                    onResume()
                    resumeTimers()
                }

                webview?.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (isClearancePassed()) {
                            cloudflareBypassed = true
                            latch.countDown()
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        if (isClearancePassed()) {
                            cloudflareBypassed = true
                            latch.countDown()
                        }
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        if (isClearancePassed()) {
                            cloudflareBypassed = true
                            latch.countDown()
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (isClearancePassed()) {
                            cloudflareBypassed = true
                            latch.countDown()
                        }
                    }
                }

                webview?.loadUrl(origRequestUrl, headers)
            } catch (_: Exception) {
                latch.countDown()
            }
        }

        // Active polling loop: Checks CookieManager every 250ms for up to 25 seconds
        val startTime = System.currentTimeMillis()
        val timeoutMs = 25_000L
        val pollIntervalMs = 250L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isClearancePassed()) {
                cloudflareBypassed = true
                latch.countDown()
                break
            }
            if (latch.await(pollIntervalMs, TimeUnit.MILLISECONDS)) {
                break
            }
        }

        // Flush cookies so OkHttp sees them immediately
        try {
            android.webkit.CookieManager.getInstance().flush()
        } catch (_: Exception) {}

        executor.execute {
            if (!cloudflareBypassed) {
                isWebViewOutdated = webview?.isOutdated() == true
            }

            webview?.run {
                stopLoading()
                pauseTimers()
                destroy()
            }
        }

        // Throw exception if we failed to bypass Cloudflare
        if (!cloudflareBypassed) {
            // Prompt user to update WebView if it seems too outdated
            if (isWebViewOutdated) {
                context.toast(MR.strings.information_webview_outdated, Toast.LENGTH_LONG)
            }

            throw CloudflareBypassException()
        }
    }
}

private val ERROR_CODES = listOf(403, 503)
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")

private class CloudflareBypassException : Exception()
