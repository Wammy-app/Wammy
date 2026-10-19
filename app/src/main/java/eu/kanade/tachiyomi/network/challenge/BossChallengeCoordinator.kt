package eu.kanade.tachiyomi.network.challenge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.util.system.tap
import eu.kanade.tachiyomi.ui.base.activity.ForegroundActivityHolder
import eu.kanade.tachiyomi.ui.challenge.ChallengeSolverActivity
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.setUserAgent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Boss Challenge Coordinator & Auto-Solver.
 * Unified autonomous Cloudflare Turnstile & anti-bot challenge resolver.
 * Executes silent background bypass first; smoothly falls back to an interactive modal if user touch is needed.
 */
class BossChallengeCoordinator(
    private val context: Context,
    private val foregroundActivityHolder: ForegroundActivityHolder,
    private val cookieJar: AndroidCookieJar,
    private val defaultUserAgentProvider: () -> String,
) : ChallengeResolver {

    private val mutex = Mutex()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingInteractiveResults = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val recentSuccessAt = ConcurrentHashMap<String, Long>()
    private val lastDismissedAt = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun hasClearance(url: String): Boolean {
        return Companion.hasClearance(url)
    }

    override fun resolve(url: String, headers: Map<String, String>?): Boolean = runBlocking {
        resolveAsync(url, headers, force = true)
    }

    suspend fun resolveAsync(
        url: String,
        headers: Map<String, String>? = null,
        force: Boolean = false,
    ): Boolean {
        val host = url.toHttpUrlOrNull()?.host.orEmpty()
        if (host.isEmpty()) return false

        // Check if existing clearance is still active and valid (unless forced)
        if (!force && hasClearance(url)) {
            logcat(LogPriority.INFO) { "BossChallengeCoordinator: $host already has active clearance" }
            return true
        }

        // Await in-flight resolution for this host if another request is already solving it
        inFlight[host]?.let {
            logcat(LogPriority.INFO) { "BossChallengeCoordinator: Awaiting in-flight solver for $host" }
            return it.await()
        }

        val deferred = mutex.withLock {
            inFlight[host]?.let { return@withLock it }
            CompletableDeferred<Boolean>().also { fresh ->
                inFlight[host] = fresh
                scope.launch {
                    val result = runResolution(url, host, headers, force)
                    if (result) {
                        recentSuccessAt[host] = System.currentTimeMillis()
                    }
                    fresh.complete(result)
                    inFlight.remove(host)
                }
            }
        }

        return deferred.await()
    }

    fun notifyInteractiveResult(host: String, success: Boolean) {
        if (!success) {
            lastDismissedAt[host] = System.currentTimeMillis()
        }
        pendingInteractiveResults.remove(host)?.complete(success)
    }

    private suspend fun runResolution(
        url: String,
        host: String,
        headers: Map<String, String>?,
        force: Boolean = false,
    ): Boolean {
        val challengeUrl = url
        val userAgent = headers?.get("User-Agent")?.takeIf { it.isNotBlank() } ?: defaultUserAgentProvider()

        val oldClearance = getClearanceCookie(challengeUrl)

        if (!force) {
            val lastSuccess = recentSuccessAt[host] ?: 0L
            val isRecent = System.currentTimeMillis() - lastSuccess < RECENT_COOLDOWN_MS
            if (isRecent && oldClearance != null) {
                logcat(LogPriority.INFO) { "BossChallengeCoordinator: $host had recent clearance within cooldown, reusing without clear" }
                return true
            }

            if (oldClearance != null) {
                logcat(LogPriority.INFO) { "BossChallengeCoordinator: $host already has valid clearance, skipping clear" }
                return true
            }
        } else {
            recentSuccessAt.remove(host)
            clearClearance(challengeUrl)
        }

        // Stage 1: Silent Background Pass
        Handler(Looper.getMainLooper()).post {
            try {
                android.widget.Toast.makeText(context, "Solving captcha automatically...", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
        logcat(LogPriority.INFO) { "BossChallengeCoordinator: Starting silent background solve for $host ($challengeUrl), oldClearance=${oldClearance?.take(8)}..." }
        val silentSuccess = runSilentPass(challengeUrl, userAgent, headers, oldClearance)
        if (silentSuccess) {
            recentSuccessAt[host] = System.currentTimeMillis()
            logcat(LogPriority.INFO) { "BossChallengeCoordinator: Silent solve SUCCEEDED for $host" }
            flushCookies()
            return true
        }

        // Stage 2: Interactive Modal Prompt
        val lastDismissed = lastDismissedAt[host] ?: 0L
        if (System.currentTimeMillis() - lastDismissed < DISMISS_COOLDOWN_MS) {
            logcat(LogPriority.INFO) { "BossChallengeCoordinator: Solver was recently dismissed for $host, skipping interactive prompt" }
            return false
        }
        logcat(LogPriority.INFO) { "BossChallengeCoordinator: Silent solve did not clear, launching interactive solver for $host" }
        val activity = foregroundActivityHolder.current
        val interactiveDeferred = CompletableDeferred<Boolean>()
        pendingInteractiveResults[host] = interactiveDeferred

        val intent = Intent(activity ?: context, ChallengeSolverActivity::class.java).apply {
            putExtra(ChallengeSolverActivity.EXTRA_URL, challengeUrl)
            putExtra(ChallengeSolverActivity.EXTRA_HOST, host)
            putExtra(ChallengeSolverActivity.EXTRA_USER_AGENT, userAgent)
            putExtra(ChallengeSolverActivity.EXTRA_OLD_CLEARANCE, oldClearance)
            if (activity == null) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        if (activity != null) {
            activity.startActivity(intent)
        } else {
            context.startActivity(intent)
        }

        val success = withTimeoutOrNull(INTERACTIVE_TIMEOUT_MS) {
            interactiveDeferred.await()
        } ?: false

        pendingInteractiveResults.remove(host)
        if (success) {
            recentSuccessAt[host] = System.currentTimeMillis()
            flushCookies()
            logcat(LogPriority.INFO) { "BossChallengeCoordinator: Interactive solve SUCCEEDED for $host" }
        } else {
            logcat(LogPriority.WARN) { "BossChallengeCoordinator: Interactive solve failed or cancelled for $host" }
        }
        return success
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runSilentPass(
        url: String,
        userAgent: String,
        headers: Map<String, String>?,
        oldClearance: String? = null,
    ): Boolean = withContext(Dispatchers.Main) {
        val host = url.toHttpUrlOrNull()?.host.orEmpty()
        val resolved = AtomicBoolean(false)
        val destroyed = AtomicBoolean(false)
        var webView: WebView? = null

        fun isChallengeTitle(title: String?): Boolean {
            if (title.isNullOrBlank()) return true
            val lower = title.lowercase().trim()
            return lower == "about:blank" ||
                lower.contains("just a moment") ||
                lower.contains("attention required") ||
                lower.contains("cloudflare") ||
                lower.contains("security check")
        }

        fun checkPassed(view: WebView? = webView): Boolean {
            val current = getClearanceCookie(url) ?: return false
            val title = view?.title.orEmpty().trim()
            if (isChallengeTitle(title)) return false
            return true
        }

        try {
            webView = WebView(context).apply {
                val dm = context.resources.displayMetrics
                layoutParams = ViewGroup.LayoutParams(dm.widthPixels, dm.heightPixels)
                layout(0, 0, dm.widthPixels, dm.heightPixels)

                setDefaultSettings()
                setUserAgent(userAgent)

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                        flushCookies()
                        if (checkPassed(view)) {
                            resolved.set(true)
                        }
                    }

                    override fun onPageCommitVisible(view: WebView?, pageUrl: String?) {
                        flushCookies()
                        if (checkPassed(view)) {
                            resolved.set(true)
                        }
                    }

                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        flushCookies()
                        if (checkPassed(view)) {
                            resolved.set(true)
                        }
                    }


                    override fun doUpdateVisitedHistory(view: WebView?, pageUrl: String?, isReload: Boolean) {
                        flushCookies()
                        if (checkPassed(view)) {
                            resolved.set(true)
                        }
                    }
                }
            }

            if (headers != null && headers.isNotEmpty()) {
                webView.loadUrl(url, headers)
            } else {
                webView.loadUrl(url)
            }

            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < SILENT_TIMEOUT_MS) {
                flushCookies()
                if (checkPassed(webView) || resolved.get()) {
                    return@withContext true
                }

                // Attempt auto-tap on Turnstile in headless WebView
                webView.evaluateJavascript(
                    """
                    (function() {
                        const iframe = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                        if (iframe) {
                            const rect = iframe.getBoundingClientRect();
                            if (rect.width > 0 && rect.height > 0) return (rect.left + 30) + ',' + (rect.top + 32);
                        }
                        const wrapper = document.querySelector('#turnstile-wrapper, .cf-turnstile');
                        if (wrapper) {
                            const rect = wrapper.getBoundingClientRect();
                            if (rect.width > 0 && rect.height > 0) return (rect.left + 30) + ',' + (rect.top + rect.height / 2);
                        }
                        return '';
                    })();
                    """.trimIndent(),
                ) { result ->
                    val raw = result?.trim('"', '\'')
                    val coords = raw?.split(',')
                    if (coords != null && coords.size == 2) {
                        val x = coords[0].toFloatOrNull()
                        val y = coords[1].toFloatOrNull()
                        if (x != null && y != null && x > 0 && y > 0) {
                            logcat(LogPriority.INFO) { "BossChallengeCoordinator: Silent auto-tapping Turnstile at ($x, $y)" }
                            webView.tap(x, y)
                        }
                    }
                }

                delay(300)
            }
            return@withContext checkPassed(webView) || resolved.get()
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "BossChallengeCoordinator: Silent pass error" }
            return@withContext false
        } finally {
            if (!destroyed.getAndSet(true)) {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (_: Throwable) {}
            }
        }
    }

    private fun flushCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {}
    }

    companion object {
        const val SILENT_TIMEOUT_MS = 25_000L
        const val INTERACTIVE_TIMEOUT_MS = 60_000L
        const val RECENT_COOLDOWN_MS = 60_000L
        const val DISMISS_COOLDOWN_MS = 30_000L

        @Volatile
        var instance: BossChallengeCoordinator? = null
            private set

        fun initialize(
            context: Context,
            foregroundActivityHolder: ForegroundActivityHolder,
            cookieJar: AndroidCookieJar,
            defaultUserAgentProvider: () -> String,
        ): BossChallengeCoordinator {
            val coordinator = BossChallengeCoordinator(context, foregroundActivityHolder, cookieJar, defaultUserAgentProvider)
            instance = coordinator
            ChallengeCoordinator.instance = coordinator
            return coordinator
        }

        fun getClearanceCookie(url: String): String? {
            val httpUrl = url.toHttpUrlOrNull()
            val urlsToCheck = buildList {
                add(url)
                if (httpUrl != null) {
                    add("${httpUrl.scheme}://${httpUrl.host}/")
                    add("https://${httpUrl.host}/")
                    add(httpUrl.host)
                }
            }
            for (u in urlsToCheck) {
                val cookies = try {
                    CookieManager.getInstance().getCookie(u)
                } catch (_: Throwable) {
                    null
                } ?: continue
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

        fun hasClearance(url: String): Boolean = getClearanceCookie(url) != null

        fun clearClearance(url: String) {
            val httpUrl = url.toHttpUrlOrNull() ?: return
            val cookieManager = try { CookieManager.getInstance() } catch (_: Throwable) { return }
            try {
                cookieManager.setCookie(url, "cf_clearance=; Max-Age=0")
                cookieManager.setCookie("https://${httpUrl.host}/", "cf_clearance=; Max-Age=0")
                cookieManager.setCookie(".${httpUrl.host}", "cf_clearance=; Max-Age=0")
                cookieManager.flush()
            } catch (_: Throwable) {}
        }
    }
}
