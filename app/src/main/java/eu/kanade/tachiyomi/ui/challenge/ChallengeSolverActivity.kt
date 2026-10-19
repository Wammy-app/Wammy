package eu.kanade.tachiyomi.ui.challenge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.util.system.tap
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.setUserAgent
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.tachiyomi.network.challenge.BossChallengeCoordinator
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

class ChallengeSolverActivity : BaseActivity() {

    private val targetUrl: String by lazy { intent.getStringExtra(EXTRA_URL).orEmpty() }
    private val targetHost: String by lazy { intent.getStringExtra(EXTRA_HOST).orEmpty() }
    private val targetUserAgent: String by lazy { intent.getStringExtra(EXTRA_USER_AGENT).orEmpty() }
    private val targetOldClearance: String? by lazy { intent.getStringExtra(EXTRA_OLD_CLEARANCE) }

    private var resultReported = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (targetUrl.isBlank()) {
            finishWithResult(false)
            return
        }

        setComposeContent {
            ChallengeSolverDialog(
                url = targetUrl,
                host = targetHost,
                userAgent = targetUserAgent,
                oldClearance = targetOldClearance,
                onDismiss = { finishWithResult(false) },
                onSuccess = { finishWithResult(true) },
            )
        }
    }

    private fun finishWithResult(success: Boolean) {
        if (!resultReported.getAndSet(true)) {
            BossChallengeCoordinator.instance?.notifyInteractiveResult(targetHost, success)
        }
        finish()
    }

    override fun onDestroy() {
        if (!resultReported.getAndSet(true)) {
            BossChallengeCoordinator.instance?.notifyInteractiveResult(targetHost, false)
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_challenge_url"
        const val EXTRA_HOST = "extra_challenge_host"
        const val EXTRA_USER_AGENT = "extra_challenge_user_agent"
        const val EXTRA_OLD_CLEARANCE = "extra_old_clearance"

        fun newIntent(context: Context, url: String, host: String, userAgent: String, oldClearance: String? = null): Intent {
            return Intent(context, ChallengeSolverActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_USER_AGENT, userAgent)
                putExtra(EXTRA_OLD_CLEARANCE, oldClearance)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChallengeSolverDialog(
    url: String,
    host: String,
    userAgent: String,
    oldClearance: String? = null,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    var isVerified by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun isChallengeTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return true
        val lower = title.lowercase().trim()
        return lower == "about:blank" ||
            lower.contains("just a moment") ||
            lower.contains("attention required") ||
            lower.contains("cloudflare") ||
            lower.contains("security check")
    }

    fun checkPassed(view: WebView? = webViewRef): Boolean {
        if (isLoading) return false
        val current = BossChallengeCoordinator.getClearanceCookie(url) ?: return false
        val title = view?.title.orEmpty().trim()
        if (isChallengeTitle(title)) return false
        return true
    }

    BackHandler {
        onDismiss()
    }

    // Active polling for clearance cookie
    LaunchedEffect(url, isVerified) {
        if (isVerified) {
            delay(350)
            onSuccess()
            return@LaunchedEffect
        }
        while (!isVerified) {
            try {
                CookieManager.getInstance().flush()
            } catch (_: Throwable) {}

            if (checkPassed(webViewRef)) {
                isVerified = true
                delay(350)
                onSuccess()
                break
            }

            // Auto-detect and tap Turnstile "Verify you are human" checkbox
            val view = webViewRef
            if (view != null) {
                view.evaluateJavascript(
                    """
                    (function() {
                        const iframe = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                        if (iframe) {
                            try { iframe.scrollIntoView({ block: 'center', inline: 'center' }); } catch (e) {}
                            const rect = iframe.getBoundingClientRect();
                            if (rect.width > 0 && rect.height > 0) {
                                return (rect.left + 30) + ',' + (rect.top + 32);
                            }
                        }
                        const wrapper = document.querySelector('#turnstile-wrapper, .cf-turnstile');
                        if (wrapper) {
                            try { wrapper.scrollIntoView({ block: 'center', inline: 'center' }); } catch (e) {}
                            const rect = wrapper.getBoundingClientRect();
                            if (rect.width > 0 && rect.height > 0) {
                                return (rect.left + 30) + ',' + (rect.top + rect.height / 2);
                            }
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
                            logcat(LogPriority.INFO) { "ChallengeSolverActivity: Interactive auto-tapping Turnstile at ($x, $y)" }
                            view.tap(x, y)
                        }
                    }
                }
            }

            delay(350)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            } catch (_: Throwable) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cloudflare Security Check",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = host,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Row {
                        IconButton(onClick = {
                            BossChallengeCoordinator.clearClearance(url)
                            webViewRef?.loadUrl("https://$host/")
                        }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isLoading && !isVerified) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Interactive WebView Container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(440.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                setDefaultSettings()
                                if (userAgent.isNotBlank()) {
                                    setUserAgent(userAgent)
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                                        isLoading = true
                                        if (checkPassed(view)) {
                                            isVerified = true
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                        isLoading = false
                                        if (checkPassed(view)) {
                                            isVerified = true
                                        }
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView?, pageUrl: String?, isReload: Boolean) {
                                        if (checkPassed(view)) {
                                            isVerified = true
                                        }
                                    }
                                }

                                webViewRef = this
                                loadUrl(url)
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Footer Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (isVerified) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Verified! Resuming...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50),
                        )
                    } else {
                        Text(
                            text = "Tap the verification checkbox above to continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
