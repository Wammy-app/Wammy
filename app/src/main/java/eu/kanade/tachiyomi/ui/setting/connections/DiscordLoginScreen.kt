package eu.kanade.tachiyomi.ui.setting.connections

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.kevinnzou.web.AccompanistWebChromeClient
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.LoadingState
import com.kevinnzou.web.WebView
import com.kevinnzou.web.rememberWebViewNavigator
import com.kevinnzou.web.rememberWebViewState
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.connections.discord.DiscordAccount
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val DISCORD_DOMAIN = "https://discord.com"
private const val DISCORD_LOGIN_URL = "$DISCORD_DOMAIN/login"
private const val DISCORD_USER_API_URL = "$DISCORD_DOMAIN/api/v10/users/@me"
private const val JS_TOKEN_NULL = "null"
private const val JS_TOKEN_ERROR = "error"

@SuppressLint("SetJavaScriptEnabled")
class DiscordLoginScreen : Screen() {

    @Composable
    override fun Content() {
        val state = rememberWebViewState(url = DISCORD_LOGIN_URL)
        val navigator = LocalNavigator.currentOrThrow
        val webViewNavigator = rememberWebViewNavigator()
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        var showTokenDialog by remember { mutableStateOf(false) }
        var hasRetrievedToken by remember { mutableStateOf(false) }

        val webViewClient = remember {
            object : AccompanistWebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val auth = request?.requestHeaders?.get("Authorization")
                        ?: request?.requestHeaders?.get("authorization")
                    if (!auth.isNullOrBlank() && !auth.startsWith("Bearer ") && !auth.startsWith("Bot ") && auth.length > 30) {
                        if (!hasRetrievedToken) {
                            hasRetrievedToken = true
                            login(auth, context)
                            scope.launch(Dispatchers.Main) {
                                view?.loadUrl("about:blank")
                                navigator.pop()
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    url?.let {
                        if (url.contains("/channels/@me") || url.contains("/app")) {
                            view.evaluateJavascript(
                                """
                                (function() {
                                    function fallbackTokenAlert() {
                                        try {
                                            var i = document.createElement('iframe');
                                            document.body.appendChild(i);
                                            setTimeout(function() {
                                                try {
                                                    var alt = i.contentWindow.localStorage.token;
                                                    if (alt) {
                                                        alert(alt.slice(1, -1));
                                                    } else {
                                                        Android.onRetrieveToken('$JS_TOKEN_NULL');
                                                    }
                                                } catch (e) {
                                                    Android.onRetrieveToken('$JS_TOKEN_ERROR');
                                                }
                                            }, 1000);
                                        } catch (e) {
                                            Android.onRetrieveToken('$JS_TOKEN_ERROR');
                                        }
                                    }

                                    try {
                                        window.dispatchEvent(new Event('beforeunload'));
                                        var webpackChunk = window.webpackChunkdiscord_app;
                                        if (!webpackChunk) {
                                            fallbackTokenAlert();
                                            return;
                                        }

                                        webpackChunk.push([[Math.random()], {}, function(req) {
                                            for (const m of Object.keys(req.c).map(x => req.c[x].exports).filter(x => x)) {
                                                if (m.default && m.default.getToken !== undefined) {
                                                    var token = m.default.getToken();
                                                    if (token) {
                                                        Android.onRetrieveToken(token);
                                                        return;
                                                    }
                                                }
                                                if (m.getToken !== undefined) {
                                                    var token = m.getToken();
                                                    if (token) {
                                                        Android.onRetrieveToken(token);
                                                        return;
                                                    }
                                                }
                                            }
                                            fallbackTokenAlert();
                                        }]);
                                    } catch (e) {
                                        fallbackTokenAlert();
                                    }
                                })();
                                """.trimIndent(),
                                null,
                            )
                        }
                    }
                }
            }
        }

        val webChromeClient = remember {
            object : AccompanistWebChromeClient() {
                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: android.webkit.JsResult?,
                ): Boolean {
                    message?.let { token ->
                        if (token != JS_TOKEN_NULL && token != JS_TOKEN_ERROR) {
                            if (!hasRetrievedToken) {
                                hasRetrievedToken = true
                                login(token, context)
                                scope.launch(Dispatchers.Main) {
                                    view?.loadUrl("about:blank")
                                    navigator.pop()
                                }
                            }
                            result?.confirm()
                            return true
                        }
                    }
                    result?.confirm()
                    return false
                }
            }
        }

        if (showTokenDialog) {
            var tokenInput by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showTokenDialog = false },
                title = { Text(text = "Enter Discord Token") },
                text = {
                    Column {
                        Text(
                            text = "Paste your Discord user token below to connect Rich Presence directly:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            placeholder = { Text(text = "User token...") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (tokenInput.isNotBlank()) {
                                showTokenDialog = false
                                login(tokenInput.trim(), context)
                                scope.launch(Dispatchers.Main) {
                                    navigator.pop()
                                }
                            }
                        },
                    ) {
                        Text(text = "Connect")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTokenDialog = false }) {
                        Text(text = "Cancel")
                    }
                },
            )
        }

        Scaffold(
            topBar = {
                Box {
                    AppBar(
                        title = stringResource(MR.strings.connections_discord),
                        navigateUp = navigator::pop,
                        actions = {
                            AppBarActions(
                                persistentListOf(
                                    AppBar.Action(
                                        title = "Enter Token",
                                        icon = Icons.Outlined.Edit,
                                        onClick = { showTokenDialog = true },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_webview_refresh),
                                        onClick = { webViewNavigator.reload() },
                                    ),
                                ),
                            )
                        },
                    )

                    when (val loadingState = state.loadingState) {
                        is LoadingState.Initializing -> LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                        )
                        is LoadingState.Loading -> LinearProgressIndicator(
                            progress = { loadingState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                        )
                        else -> {}
                    }
                }
            },
        ) { contentPadding ->
            WebView(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                navigator = webViewNavigator,
                onCreated = { webView ->
                    webView.setDefaultSettings()

                    if (isDebugBuildType &&
                        0 != webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE
                    ) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }

                    clearCookies(DISCORD_LOGIN_URL)
                    WebStorage.getInstance().deleteOrigin(DISCORD_DOMAIN)

                    webView.addJavascriptInterface(
                        object {
                            @Suppress("unused")
                            @JavascriptInterface
                            fun onRetrieveToken(token: String) {
                                if (token != JS_TOKEN_NULL && token != JS_TOKEN_ERROR) {
                                    if (!hasRetrievedToken) {
                                        hasRetrievedToken = true
                                        login(token, context)
                                        scope.launch(Dispatchers.Main) {
                                            webView.loadUrl("about:blank")
                                            navigator.pop()
                                        }
                                    }
                                }
                            }
                        },
                        "Android",
                    )
                },
                client = webViewClient,
                chromeClient = webChromeClient,
            )
        }
    }

    private fun login(token: String, context: Context) {
        val connectionsManager: ConnectionsManager by lazy { Injekt.get() }
        val connectionsPreferences: ConnectionsPreferences by lazy { Injekt.get() }
        val networkHelper: NetworkHelper by lazy { Injekt.get() }
        val json: Json by lazy { Injekt.get<Json>() }

        launchIO {
            try {
                val cleanToken = token.trim().removeSurrounding("\"")
                networkHelper.client.newCall(
                    okhttp3.Request.Builder()
                        .url(DISCORD_USER_API_URL)
                        .addHeader("Authorization", cleanToken)
                        .build(),
                ).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body.string()
                        val account = json.decodeFromString<DiscordAccount>(body)
                            .copy(
                                token = cleanToken,
                                isActive = true,
                            )
                        connectionsManager.discord.addAccount(account)

                        connectionsPreferences.connectionsToken(connectionsManager.discord).set(cleanToken)
                        connectionsPreferences.setConnectionsCredentials(
                            connectionsManager.discord,
                            account.username,
                            "Discord",
                        )
                        connectionsPreferences.enableDiscordRPC().set(true)
                        DiscordRPCService.start(context)

                        withContext(Dispatchers.Main) {
                            context.toast(MR.strings.login_success)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            context.toast("Discord login failed: HTTP ${response.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Discord login error: ${e.message}" }
                withContext(Dispatchers.Main) {
                    context.toast(MR.strings.login_failed)
                }
            }
        }
    }

    private fun clearCookies(url: String) {
        val networkHelper: NetworkHelper by lazy { Injekt.get() }
        url.toHttpUrlOrNull()?.let {
            val cleared = networkHelper.cookieJar.remove(it)
            logcat { "Cleared $cleared cookies for: $url" }
        }
    }
}
