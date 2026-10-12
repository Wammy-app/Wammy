package eu.kanade.tachiyomi.data.sync.replica

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

sealed interface AuthResult {
    data class Success(val token: String) : AuthResult
    data class NeedsConsent(val pendingIntent: PendingIntent) : AuthResult
    data class Error(val message: String) : AuthResult
}

class SyncSignInRequiredException(message: String = "Google Drive authorization required") : Exception(message)

/**
 * Manages Google OAuth2 authorization for the drive.appdata scope.
 */
class GoogleDriveAuth(private val context: Context) {

    private val authClient: AuthorizationClient = Identity.getAuthorizationClient(context)

    companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }

    /**
     * Request authorization for Drive appdata. If user consent is needed, returns [AuthResult.NeedsConsent].
     */
    suspend fun requestAuthorization(): AuthResult = withContext(Dispatchers.IO) {
        try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
                .build()
            val result = authClient.authorize(request).await()
            if (result.hasResolution()) {
                val pendingIntent = result.pendingIntent
                if (pendingIntent != null) {
                    AuthResult.NeedsConsent(pendingIntent)
                } else {
                    AuthResult.Error("Resolution required but pending intent is null")
                }
            } else {
                val token = result.accessToken
                if (token != null) {
                    AuthResult.Success(token)
                } else {
                    AuthResult.Error("No access token returned")
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to authorize Google Drive" }
            AuthResult.Error(e.message ?: "Authorization failed")
        }
    }

    /**
     * Obtains a valid access token or throws [SyncSignInRequiredException].
     */
    suspend fun requireAccessToken(): String = withContext(Dispatchers.IO) {
        when (val res = requestAuthorization()) {
            is AuthResult.Success -> res.token
            is AuthResult.NeedsConsent -> throw SyncSignInRequiredException("Consent required")
            is AuthResult.Error -> throw SyncSignInRequiredException(res.message)
        }
    }

    /**
     * Runs [block] with a fresh token. If server rejects with 401, re-authorizes and tries once more.
     */
    suspend fun <T> withToken(block: suspend (token: String) -> T): T {
        val token = requireAccessToken()
        return try {
            block(token)
        } catch (e: SyncApiException) {
            if (e.code != 401) throw e
            logcat(LogPriority.WARN) { "Access token rejected (401), refreshing..." }
            val freshToken = requireAccessToken()
            block(freshToken)
        }
    }
}
