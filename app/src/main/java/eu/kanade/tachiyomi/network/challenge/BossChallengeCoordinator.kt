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

    override fun resolve(
        url: String,
        headers: Map<String, String>?,
        isManaged: Boolean,
        isCanceled: () -> Boolean,
    ): Boolean = runBlocking {
        resolveAsync(url, headers, force = true, isManaged = isManaged, isCanceled = isCanceled)
    }

    suspend fun resolveAsync(
        url: String,
        headers: Map<String, String>? = null,
        force: Boolean = false,
        isManaged: Boolean = false,
        isCanceled: () -> Boolean = { false },
    ): Boolean {
        if (isCanceled()) return false
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
                    val result = runResolution(url, host, headers, force, isManaged, isCanceled)
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
        isManaged: Boolean = false,
        isCanceled: () -> Boolean = { false },
    ): Boolean {
        if (isCanceled()) return false
        val challengeUrl = url
        val userAgent = headers?.get("User-Agent")?.takeIf { it.isNotBlank() } ?: defaultUserAgentProvider()

        val oldClearance = getClearanceCookie(challengeUrl)

        val lastSuccess = recentSuccessAt[host] ?: 0L
        val isRecent = System.currentTimeMillis() - lastSuccess < RECENT_COOLDOWN_MS
        if (isRecent && oldClearance != null) {
            logcat(LogPriority.INFO) { "BossChallengeCoordinator: $host had recent clearance within cooldown (${System.currentTimeMillis() - lastSuccess}ms ago), reusing without clear" }
            return true
        }

        if (force) {
            recentSuccessAt.remove(host)
            clearClearance(challengeUrl)
        } else if (oldClearance != null) {
            logcat(LogPriority.INFO) { "BossChallengeCoordinator: $host already has valid clearance, skipping clear" }
            return true
        }

        // Stage 1: Silent Background Pass (skip if explicitly a managed Turnstile challenge)
        var silentSuccess = false
        if (!isManaged) {
            logcat(LogPriority.INFO) { "BossChallengeCoordinator: Starting silent background solve for $host ($challengeUrl), oldClearance=${oldClearance?.take(8)}..." }
            silentSuccess = runSilentPass(challengeUrl, userAgent, headers, oldClearance, isCanceled)
            if (silentSuccess) {
                recentSuccessAt[host] = System.currentTimeMillis()
                logcat(LogPriority.INFO) { "BossChallengeCoordinator: Silent solve SUCCEEDED for $host" }
                flushCookies()
                return true
            }
        } else {
            logcat(LogPriority.INFO) { "BossChallengeCoordinator: Managed challenge detected (cf-mitigated: challenge) for $host, skipping silent pass" }
        }

        // Stage 2: Interactive Modal Prompt
        if (isCanceled()) {
            logcat(LogPriority.INFO) { "BossChallengeCoordinator: Request was cancelled, skipping interactive prompt for $host" }
            return false
        }

        val lastDismissed = lastDismissedAt[host] ?: 0L
        if (System.currentTimeMillis() - lastDismissed < DISMISS_COOLDOWN_MS) {
            logcat(LogPriority.INFO) { "BossChallengeCoordinator: Solver was recently dismissed for $host, skipping interactive prompt" }
            return false
        }

        val activity = foregroundActivityHolder.current
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            logcat(LogPriority.INFO) { "BossChallengeCoordinator: Activity is unavailable or finishing, skipping interactive solver for $host" }
            return false
        }

        logcat(LogPriority.INFO) { "BossChallengeCoordinator: Launching interactive solver for $host" }
        val interactiveDeferred = CompletableDeferred<Boolean>()
        pendingInteractiveResults[host] = interactiveDeferred

        val intent = Intent(activity, ChallengeSolverActivity::class.java).apply {
            putExtra(ChallengeSolverActivity.EXTRA_URL, challengeUrl)
            putExtra(ChallengeSolverActivity.EXTRA_HOST, host)
            putExtra(ChallengeSolverActivity.EXTRA_USER_AGENT, userAgent)
            putExtra(ChallengeSolverActivity.EXTRA_OLD_CLEARANCE, oldClearance)
        }
        activity.startActivity(intent)

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
        isCanceled: () -> Boolean = { false },
    ): Boolean = withContext(Dispatchers.Main) {
        val host = url.toHttpUrlOrNull()?.host.orEmpty()
        val resolved = AtomicBoolean(false)
        val destroyed = AtomicBoolean(false)
        var hasInteractiveTurnstile = false
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
            if (oldClearance != null && current == oldClearance) return false
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
                if (isCanceled()) return@withContext false
                flushCookies()
                if (checkPassed(webView) || resolved.get()) {
                    return@withContext true
                }
                if (hasInteractiveTurnstile) {
                    // Turnstile detected; headless cannot solve it, switch to interactive immediately
                    break
                }

                // Check if Turnstile is present or attempt auto-tap on Turnstile in headless WebView
                webView.evaluateJavascript(
                    """
                    (function() {
                        const iframe = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                        if (iframe) return 'turnstile';
                        const wrapper = document.querySelector('#turnstile-wrapper, .cf-turnstile');
                        if (wrapper) return 'turnstile';
                        return '';
                    })();
                    """.trimIndent(),
                ) { result ->
                    val raw = result?.trim('"', '\'')
                    if (raw == "turnstile") {
                        hasInteractiveTurnstile = true
                    }
                }

                delay(250)
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
        const val SILENT_TIMEOUT_MS = 1_000L
        const val INTERACTIVE_TIMEOUT_MS = 60_000L
        const val RECENT_COOLDOWN_MS = 60_000L
        const val DISMISS_COOLDOWN_MS = 10_000L

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
            val cookieManager = try { CookieManager.getInstance() } catch (_: Throwable) { return null }
            for (u in urlsToCheck) {
                val cookies = try {
                    cookieManager.getCookie(u)
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
            val host = httpUrl.host
            val nonWww = host.removePrefix("www.")
            val deleteCookieVal = "cf_clearance=; Domain=.$host; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"
            val deleteCookieNonWww = "cf_clearance=; Domain=.$nonWww; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"
            val deleteCookieSimple = "cf_clearance=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"

            try {
                cookieManager.setCookie(url, deleteCookieVal)
                cookieManager.setCookie(url, deleteCookieNonWww)
                cookieManager.setCookie(url, deleteCookieSimple)
                cookieManager.setCookie("https://$host/", deleteCookieVal)
                cookieManager.setCookie("https://$host/", deleteCookieSimple)
                cookieManager.setCookie("https://$nonWww/", deleteCookieNonWww)
                cookieManager.setCookie("https://$nonWww/", deleteCookieSimple)
                cookieManager.flush()
            } catch (_: Throwable) {}
        }
    }
}
