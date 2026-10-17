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
import eu.kanade.tachiyomi.ui.base.activity.ForegroundActivityHolder
import eu.kanade.tachiyomi.ui.challenge.ChallengeSolverActivity
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

        // Check if existing clearance is still active and recently verified (unless forced)
        if (!force) {
            val lastSuccess = recentSuccessAt[host] ?: 0L
            if (System.currentTimeMillis() - lastSuccess < RECENT_COOLDOWN_MS && hasClearance(url)) {
                logcat(LogPriority.INFO) { "BossChallengeCoordinator: $host already has active clearance" }
                return true
            }
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
                    val result = runResolution(url, host, headers)
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
        pendingInteractiveResults.remove(host)?.complete(success)
    }

    private suspend fun runResolution(
        url: String,
        host: String,
        headers: Map<String, String>?,
    ): Boolean {
        val httpUrl = url.toHttpUrlOrNull()
        val challengeUrl = if (httpUrl != null && (httpUrl.encodedPath.contains("/api/") || httpUrl.encodedPath.endsWith(".json"))) {
            "${httpUrl.scheme}://${httpUrl.host}/"
        } else {
            url
        }

        val userAgent = headers?.get("User-Agent")?.takeIf { it.isNotBlank() } ?: defaultUserAgentProvider()

        // Invalidate stale clearance before starting solver so we strictly verify a fresh challenge pass
        recentSuccessAt.remove(host)
        Companion.clearClearance(challengeUrl)

        // Stage 1: Silent Background Pass
        logcat(LogPriority.INFO) { "BossChallengeCoordinator: Starting silent background solve for $host ($challengeUrl)" }
        val silentSuccess = runSilentPass(challengeUrl, userAgent, headers)
        if (silentSuccess) {
            logcat(LogPriority.INFO) { "BossChallengeCoordinator: Silent solve SUCCEEDED for $host" }
            flushCookies()
            return true
        }

        // Stage 2: Interactive Modal Prompt
        logcat(LogPriority.INFO) { "BossChallengeCoordinator: Silent solve did not clear, launching interactive solver for $host" }
        val foregroundActivity = foregroundActivityHolder.current
        if (foregroundActivity == null) {
            logcat(LogPriority.WARN) { "BossChallengeCoordinator: No foreground activity available for $host" }
            return false
        }

        val interactiveDeferred = CompletableDeferred<Boolean>()
        pendingInteractiveResults[host] = interactiveDeferred

        val intent = Intent(foregroundActivity, ChallengeSolverActivity::class.java).apply {
            putExtra(ChallengeSolverActivity.EXTRA_URL, challengeUrl)
            putExtra(ChallengeSolverActivity.EXTRA_HOST, host)
            putExtra(ChallengeSolverActivity.EXTRA_USER_AGENT, userAgent)
        }
        foregroundActivity.startActivity(intent)

        val success = withTimeoutOrNull(INTERACTIVE_TIMEOUT_MS) {
            interactiveDeferred.await()
        } ?: false

        pendingInteractiveResults.remove(host)
        if (success) {
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
    ): Boolean = withContext(Dispatchers.Main) {
        val resolved = AtomicBoolean(false)
        val destroyed = AtomicBoolean(false)
        var webView: WebView? = null

        try {
            webView = WebView(context).apply {
                val dm = context.resources.displayMetrics
                layoutParams = ViewGroup.LayoutParams(dm.widthPixels, dm.heightPixels)
                layout(0, 0, dm.widthPixels, dm.heightPixels)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    blockNetworkImage = false
                    userAgentString = userAgent
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        if (hasClearance(url)) {
                            resolved.set(true)
                        }
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, pageUrl: String?, isReload: Boolean) {
                        if (hasClearance(url)) {
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
                if (hasClearance(url) || resolved.get()) {
                    return@withContext true
                }
                delay(250)
            }
            return@withContext hasClearance(url)
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
        const val SILENT_TIMEOUT_MS = 6_000L
        const val INTERACTIVE_TIMEOUT_MS = 60_000L
        const val RECENT_COOLDOWN_MS = 20_000L

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

        fun hasClearance(url: String): Boolean {
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
                val hasClearance = cookies.split(";")
                    .map { it.trim() }
                    .any { it.startsWith("cf_clearance=") && it.length > "cf_clearance=".length }
                if (hasClearance) return true
            }
            return false
        }

        fun clearClearance(url: String) {
            val httpUrl = url.toHttpUrlOrNull() ?: return
            val cookieManager = try { CookieManager.getInstance() } catch (_: Throwable) { return }
            val host = httpUrl.host
            val urls = listOf(
                url,
                "${httpUrl.scheme}://$host/",
                "https://$host/",
                host,
                ".$host",
            )
            for (u in urls) {
                try {
                    cookieManager.setCookie(u, "cf_clearance=; Max-Age=0; path=/")
                    cookieManager.setCookie(u, "cf_clearance=; Max-Age=0; domain=$host; path=/")
                    cookieManager.setCookie(u, "cf_clearance=; Max-Age=0; domain=.$host; path=/")
                } catch (_: Throwable) {}
            }
            try {
                cookieManager.flush()
            } catch (_: Throwable) {}
        }
    }
}
