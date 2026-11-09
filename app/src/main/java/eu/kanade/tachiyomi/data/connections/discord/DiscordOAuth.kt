package eu.kanade.tachiyomi.data.connections.discord

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.net.toUri
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.tachiyomi.util.system.openInBrowser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

object DiscordOAuth {
    const val CLIENT_ID = "1551895517397516318"
    const val REDIRECT_URI = "discord-1551895517397516318:/authorize/callback"
    const val SCHEME = "discord-1551895517397516318"
    const val TOKEN_URL = "https://discord.com/api/v10/oauth2/token"
    const val USER_URL = "https://discord.com/api/v10/users/@me"

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun buildAuthUri(): Uri {
        val verifier = generateCodeVerifier()
        Injekt.get<ConnectionsPreferences>().discordOAuthCodeVerifier().set(verifier)
        val challenge = generateCodeChallenge(verifier)

        return "https://discord.com/oauth2/authorize".toUri().buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "identify")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
    }

    private val DISCORD_PACKAGES = listOf(
        "com.discord",
        "com.discord.canary",
        "com.discord.ptb",
    )

    fun launchAuthorization(context: Context) {
        val uri = buildAuthUri()
        for (pkg in DISCORD_PACKAGES) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(pkg)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        context.openInBrowser(uri, forceDefaultBrowser = true)
    }
}
