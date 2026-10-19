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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import eu.kanade.tachiyomi.network.challenge.ChallengeCoordinator

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    override fun shouldIntercept(response: Response): Boolean {
        // Only 403 and 503 can be Cloudflare challenge responses
        if (response.code !in ERROR_CODES) return false

        // Do not intercept or trigger full challenge solving for static image/cover requests
        val path = response.request.url.encodedPath.lowercase()
        val accept = response.request.header("Accept").orEmpty()
        val isStaticAsset = path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".png") || path.endsWith(".webp") || path.endsWith(".gif") ||
            path.endsWith(".avif") || path.endsWith(".svg") ||
            accept.startsWith("image/")
        if (isStaticAsset) {
            return false
        }

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
            bodySample.contains("challenges.cloudflare.com") ||
            bodySample.contains("Verify you are human") ||
            bodySample.contains("needs to review the security of your connection")
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
            val isManaged = response.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true
            response.close()

            if (chain.call().isCanceled()) {
                throw IOException("Canceled")
            }

            val resolver = ChallengeCoordinator.instance
            val bypassed = if (resolver != null) {
                val headers = parseHeaders(request.headers)
                resolver.resolve(
                    url = request.url.toString(),
                    headers = headers,
                    isManaged = isManaged,
                    isCanceled = { chain.call().isCanceled() },
                )
            } else {
                val oldCookie = cookieManager.get(request.url)
                    .firstOrNull { it.name == "cf_clearance" }
                cookieManager.remove(request.url, COOKIE_NAMES, 0)
                resolveWithWebView(request, oldCookie)
                true
            }

            if (chain.call().isCanceled()) {
                throw IOException("Canceled")
            }

            if (!bypassed) {
                throw CloudflareBypassException()
            }

            // Build fresh request with cookies and full Chromium client hints matching WebView
            val userAgent = defaultUserAgentProvider()
            val match = CHROME_VERSION_REGEX.find(userAgent)
            val major = match?.groupValues?.getOrNull(1) ?: "130"
            val full = major + (match?.groupValues?.getOrNull(2)?.ifEmpty { ".0.0.0" } ?: ".0.6723.102")

            val retryBuilder = request.newBuilder()
                .header("User-Agent", userAgent)
                .header("Sec-CH-UA", "\"Chromium\";v=\"$major\", \"Not?A_Brand\";v=\"24\", \"Google Chrome\";v=\"$major\"")
                .header("Sec-CH-UA-Mobile", "?1")
                .header("Sec-CH-UA-Platform", "\"Android\"")
                .header("Sec-CH-UA-Platform-Version", "\"10.0.0\"")
                .header("Sec-CH-UA-Arch", "\"\"")
                .header("Sec-CH-UA-Bitness", "\"64\"")
                .header("Sec-CH-UA-Model", "\"K\"")
                .header("Sec-CH-UA-Full-Version", "\"$full\"")
                .header("Sec-CH-UA-Full-Version-List", "\"Chromium\";v=\"$full\", \"Not?A_Brand\";v=\"24.0.0.0\", \"Google Chrome\";v=\"$full\"")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")

            if (request.header("Accept").isNullOrBlank()) {
                retryBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            }
            if (request.header("Accept-Language").isNullOrBlank()) {
                retryBuilder.header("Accept-Language", "en-US,en;q=0.9")
            }

            // Explicitly sync and attach cookies directly from CookieManager
            val rawCookies = try {
                android.webkit.CookieManager.getInstance().getCookie(request.url.toString())
            } catch (_: Throwable) {
                null
            }

            val clearance = getClearanceFromCookieManager(request.url.toString())
            if (!rawCookies.isNullOrBlank()) {
                val fullCookies = if (clearance != null && !rawCookies.contains("cf_clearance")) {
                    "$rawCookies; cf_clearance=$clearance"
                } else {
                    rawCookies
                }
                retryBuilder.header("Cookie", fullCookies)
            } else if (clearance != null) {
                retryBuilder.header("Cookie", "cf_clearance=$clearance")
            }

            val retryResponse = chain.proceed(retryBuilder.build())
            if (shouldIntercept(retryResponse)) {
                retryResponse.close()
                throw CloudflareBypassException()
            }
            return retryResponse
        }
        // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
        // we don't crash the entire app
        catch (e: CloudflareBypassException) {
            throw IOException(context.stringResource(MR.strings.information_cloudflare_bypass_failure), e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    private fun getClearanceFromCookieManager(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull()
        val urlsToCheck = buildList {
            add(url)
            if (httpUrl != null) {
                val host = httpUrl.host
                val nonWww = host.removePrefix("www.")
                val www = if (host.startsWith("www.")) host else "www.$host"
                add("${httpUrl.scheme}://$host/")
                add("${httpUrl.scheme}://$nonWww/")
                add("${httpUrl.scheme}://$www/")
                add("https://$host/")
                add("https://$nonWww/")
                add("https://$www/")
            }
        }
        val cm = try { android.webkit.CookieManager.getInstance() } catch (_: Throwable) { return null }
        for (u in urlsToCheck) {
            val cookies = try { cm.getCookie(u) } catch (_: Throwable) { null } ?: continue
            for (c in cookies.split(";")) {
                val trimmed = c.trim()
                if (trimmed.startsWith("cf_clearance=")) {
                    val value = trimmed.substringAfter("=").trim()
                    if (value.isNotEmpty()) return value
                }
            }
        }
        return null
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
                    val dm = context.resources.displayMetrics
                    layout(0, 0, dm.widthPixels, dm.heightPixels)
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
            try {
                android.webkit.CookieManager.getInstance().flush()
            } catch (_: Exception) {}

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
private val CHROME_VERSION_REGEX = """Chrome/(\d+)(\.[\d.]+)?""".toRegex()

private class CloudflareBypassException : Exception()
