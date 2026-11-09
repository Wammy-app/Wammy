package eu.kanade.tachiyomi.ui.setting.connections

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.connections.discord.DiscordAccount
import eu.kanade.tachiyomi.data.connections.discord.DiscordOAuth
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import okhttp3.FormBody
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.injectLazy

class DiscordOAuthLoginActivity : BaseActivity() {

    private val connectionsManager: ConnectionsManager by injectLazy()
    private val connectionsPreferences: ConnectionsPreferences by injectLazy()
    private val networkHelper: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setComposeContent {
            LoadingScreen()
        }

        val data: Uri? = intent.data
        if (data == null) {
            returnToSettings()
            return
        }

        val code = data.getQueryParameter("code")
        val error = data.getQueryParameter("error")

        if (error != null) {
            logcat(LogPriority.ERROR) { "Discord OAuth error: $error" }
            toast(error)
            returnToSettings()
            return
        }

        if (code.isNullOrBlank()) {
            returnToSettings()
            return
        }

        lifecycleScope.launch {
            handleAuthCode(code)
            returnToSettings()
        }
    }

    private suspend fun handleAuthCode(code: String) {
        val verifier = connectionsPreferences.discordOAuthCodeVerifier().get()
        try {
            val token = exchangeCodeForToken(code, verifier)
            if (token != null) {
                fetchAndSaveAccount(token)
            } else {
                withContext(Dispatchers.Main) {
                    toast("Failed to obtain Discord token")
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to complete Discord OAuth: ${e.message}" }
            withContext(Dispatchers.Main) {
                toast("Discord login failed: ${e.message}")
            }
        }
    }

    private suspend fun exchangeCodeForToken(code: String, verifier: String): String? = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client_id", DiscordOAuth.CLIENT_ID)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", DiscordOAuth.REDIRECT_URI)
            .apply {
                if (verifier.isNotBlank()) {
                    add("code_verifier", verifier)
                }
            }
            .build()

        val request = Request.Builder()
            .url(DiscordOAuth.TOKEN_URL)
            .post(formBody)
            .build()

        val response = networkHelper.client.newCall(request).execute()
        val responseBody = response.body.string()

        if (!response.isSuccessful) {
            logcat(LogPriority.ERROR) { "Token exchange failed: HTTP ${response.code} $responseBody" }
            return@withContext null
        }

        val jsonObj = json.parseToJsonElement(responseBody).jsonObject
        val accessToken = jsonObj["access_token"]?.jsonPrimitive?.content ?: return@withContext null
        val tokenType = jsonObj["token_type"]?.jsonPrimitive?.content ?: "Bearer"
        "$tokenType $accessToken"
    }

    private suspend fun fetchAndSaveAccount(token: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .header("Authorization", token)
            .url(DiscordOAuth.USER_URL)
            .build()

        val response = networkHelper.client.newCall(request).execute()
        val responseBody = response.body.string()

        if (!response.isSuccessful) {
            logcat(LogPriority.ERROR) { "Fetch Discord user failed: HTTP ${response.code} $responseBody" }
            return@withContext
        }

        val account = json.decodeFromString<DiscordAccount>(responseBody)
        connectionsManager.discord.addAccount(account)
        connectionsPreferences.connectionsToken(connectionsManager.discord).set(token)
        connectionsPreferences.setConnectionsCredentials(
            connectionsManager.discord,
            account.username,
            "Discord",
        )

        withContext(Dispatchers.Main) {
            toast(MR.strings.login_success)
        }
    }

    private fun returnToSettings() {
        finish()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }
}
