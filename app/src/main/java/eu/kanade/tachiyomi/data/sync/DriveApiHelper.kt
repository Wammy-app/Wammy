package eu.kanade.tachiyomi.data.sync

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.IOException
import okio.source
import tachiyomi.core.common.util.system.logcat

/**
 * Handles all Google Drive REST API interactions for the hidden appdata folder.
 *
 * Uses OkHttp (already in the project) instead of the heavy Google API Client library.
 * The appdata folder is invisible to the user and other apps — only Wammy can access it.
 */
class DriveApiHelper(
    private val context: Context,
    baseClient: OkHttpClient,
) {
    // Dedicated client with generous timeouts for large backup uploads/downloads
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val authClient: AuthorizationClient = Identity.getAuthorizationClient(context)

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    var lastErrorDetail: String? = null
        private set

    companion object {
        private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private const val SYNC_FILE_NAME = "wammy_backup.tachibk"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val GZIP_MEDIA_TYPE = "application/gzip".toMediaType()
    }

    private fun setCachedToken(token: String?) {
        cachedAccessToken = token
        try {
            val prefs = context.getSharedPreferences("wammy_drive_auth", Context.MODE_PRIVATE)
            prefs.edit().putString("cached_token", token).apply()
        } catch (_: Exception) {}
    }

    private fun getCachedToken(): String? {
        if (!cachedAccessToken.isNullOrBlank()) return cachedAccessToken
        return try {
            val prefs = context.getSharedPreferences("wammy_drive_auth", Context.MODE_PRIVATE)
            val saved = prefs.getString("cached_token", null)
            cachedAccessToken = saved
            saved
        } catch (_: Exception) {
            null
        }
    }

    private fun getCurrentAccount(): Account? {
        val email = FirebaseAuth.getInstance().currentUser?.email
            ?: GoogleSignIn.getLastSignedInAccount(context)?.email
        return email?.let { Account(it, "com.google") }
    }

    /**
     * Extracts and caches the granted access token when returning from the Google consent dialog.
     */
    fun handleAuthorizationResult(data: Intent?): String? {
        if (data == null) return null
        return try {
            val result = authClient.getAuthorizationResultFromIntent(data)
            val token = result.accessToken
            if (!token.isNullOrBlank()) {
                setCachedToken(token)
                lastErrorDetail = null
                logcat(LogPriority.INFO) { "DriveApiHelper: Token successfully extracted from consent intent" }
            }
            token
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "DriveApiHelper: Failed to extract auth result from intent" }
            lastErrorDetail = "Failed to parse Google consent result: ${e.message}"
            null
        }
    }

    /**
     * Request the drive.appdata scope from the user.
     * Returns the access token on success, or NeedsConsent if the user must grant permissions.
     */
    suspend fun requestDriveAuthorization(): AuthorizationResult = withContext(Dispatchers.IO) {
        try {
            // First try default authorization without synthetic account binding (most compatible with GIS)
            val defaultRequest = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
                .build()

            val result = authClient.authorize(defaultRequest).await()

            if (result.hasResolution()) {
                val pendingIntent = result.pendingIntent
                if (pendingIntent != null) {
                    AuthorizationResult.NeedsConsent(pendingIntent)
                } else {
                    AuthorizationResult.Error("Resolution required but PendingIntent was null")
                }
            } else {
                val token = result.accessToken
                if (!token.isNullOrBlank()) {
                    setCachedToken(token)
                    lastErrorDetail = null
                    AuthorizationResult.Success(token)
                } else {
                    val account = getCurrentAccount()
                    val fallbackToken = tryGetGoogleAuthUtilToken(account)
                    if (fallbackToken != null) {
                        setCachedToken(fallbackToken)
                        lastErrorDetail = null
                        AuthorizationResult.Success(fallbackToken)
                    } else {
                        AuthorizationResult.Error("No access token returned by Google Identity Services")
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "DriveApiHelper: Drive authorization failed" }
            lastErrorDetail = e.message ?: "Authorization failed"
            val account = getCurrentAccount()
            val fallbackToken = tryGetGoogleAuthUtilToken(account)
            if (fallbackToken != null) {
                setCachedToken(fallbackToken)
                lastErrorDetail = null
                AuthorizationResult.Success(fallbackToken)
            } else {
                AuthorizationResult.Error(e.message ?: "Unknown authorization error")
            }
        }
    }

    /**
     * Get a fresh access token. Call this before every Drive API request.
     */
    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        // 1. Try Identity AuthorizationClient without synthetic account (standard GIS flow, same as master)
        try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
                .build()
            val result = authClient.authorize(request).await()
            val token = result.accessToken
            if (!token.isNullOrBlank()) {
                setCachedToken(token)
                return@withContext token
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "DriveApiHelper: Default Identity.authorize failed" }
        }

        // 2. Try with explicit Account binding if available
        val account = getCurrentAccount()
        if (account != null) {
            try {
                val request = AuthorizationRequest.builder()
                    .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
                    .setAccount(account)
                    .build()
                val result = authClient.authorize(request).await()
                val token = result.accessToken
                if (!token.isNullOrBlank()) {
                    setCachedToken(token)
                    return@withContext token
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "DriveApiHelper: Identity.authorize with account failed" }
            }

            // 3. Try GoogleAuthUtil fallback
            val authUtilToken = tryGetGoogleAuthUtilToken(account)
            if (authUtilToken != null) {
                setCachedToken(authUtilToken)
                return@withContext authUtilToken
            }
        }

        // 4. Return cached access token (RAM or disk) if available
        getCachedToken()
    }

    private fun tryGetGoogleAuthUtilToken(account: Account?): String? {
        if (account == null) return null
        return try {
            GoogleAuthUtil.getToken(context, account, "oauth2:$DRIVE_SCOPE")
        } catch (e: UserRecoverableAuthException) {
            logcat(LogPriority.INFO) { "DriveApiHelper: GoogleAuthUtil requires user consent" }
            null
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "DriveApiHelper: GoogleAuthUtil failed" }
            null
        }
    }

    /**
     * Invalidate token on HTTP 401 so the next request requests a fresh one.
     */
    fun invalidateToken(oldToken: String?) {
        if (cachedAccessToken == oldToken) {
            setCachedToken(null)
        }
        if (oldToken != null) {
            try {
                GoogleAuthUtil.clearToken(context, oldToken)
            } catch (_: Exception) {}
        }
    }

    private fun parseGoogleError(code: Int, responseBody: String?): String {
        val body = responseBody.orEmpty()
        logcat(LogPriority.ERROR) { "Drive API HTTP $code: $body" }

        val serverMessage = try {
            val json = org.json.JSONObject(body)
            val errorObj = json.optJSONObject("error")
            errorObj?.optString("message") ?: body
        } catch (_: Exception) {
            body
        }

        return when {
            code == 401 -> "Google Drive authorization expired (HTTP 401). Please re-authenticate."
            code == 403 && (serverMessage.contains("Google Drive API has not been used", ignoreCase = true) ||
                    serverMessage.contains("it is disabled", ignoreCase = true)) -> {
                "Google Drive API has not been enabled in your Google Cloud project.\n\nPlease enable it in Google Cloud Console:\nhttps://console.developers.google.com/apis/api/drive.googleapis.com/overview?project=997612260567"
            }
            code == 403 -> "Google Drive permission denied (HTTP 403):\n$serverMessage"
            code == 404 -> "Google Drive file not found (HTTP 404):\n$serverMessage"
            code in 500..599 -> "Google Drive temporary server error (HTTP $code). Please try again later."
            serverMessage.isNotBlank() -> "Drive HTTP $code: $serverMessage"
            else -> "Google Drive request failed with HTTP $code"
        }
    }

    /**
     * Gets the last modified time of the sync file in the cloud (ISO 8601 string)
     */
    suspend fun getSyncFileModifiedTime(accessToken: String): String? = withContext(Dispatchers.IO) {
        val query = java.net.URLEncoder.encode("name='$SYNC_FILE_NAME' and trashed=false", "UTF-8")
        val url = "$DRIVE_FILES_URL?spaces=appDataFolder&q=$query&orderBy=modifiedTime%20desc&fields=files(id,modifiedTime)"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastErrorDetail = parseGoogleError(response.code, response.body?.string())
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val files = org.json.JSONObject(body).optJSONArray("files")
                if (files != null && files.length() > 0) {
                    return@withContext files.getJSONObject(0).optString("modifiedTime")
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to fetch file modified time" }
            lastErrorDetail = e.message
        }
        null
    }

    /**
     * Find the sync file in the appdata folder.
     * @return The file ID, or null if the file doesn't exist yet.
     */
    suspend fun findSyncFile(accessToken: String): String? = withContext(Dispatchers.IO) {
        val query = java.net.URLEncoder.encode("name='$SYNC_FILE_NAME' and trashed=false", "UTF-8")
        val url = "$DRIVE_FILES_URL?spaces=appDataFolder&q=$query&orderBy=modifiedTime%20desc&fields=files(id,name,modifiedTime)"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        val body = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastErrorDetail = parseGoogleError(response.code, response.body?.string())
                    return@withContext null
                }
                response.body?.string() ?: run {
                    lastErrorDetail = "Empty response received when checking Google Drive sync file"
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Drive findSyncFile exception" }
            lastErrorDetail = "Network connection failed: ${e.message}"
            return@withContext null
        }

        try {
            val files = org.json.JSONObject(body).optJSONArray("files")
            if (files == null || files.length() == 0) return@withContext null

            // First file is the latest non-trashed backup due to orderBy=modifiedTime desc
            files.getJSONObject(0).optString("id")
        } catch (e: Exception) {
            val idRegex = """"id"\s*:\s*"([^"]+)"""".toRegex()
            idRegex.find(body)?.groupValues?.get(1)
        }
    }

    /**
     * Find ONLY the legacy sync file (wammy_sync.json.gz) for cleanup purposes.
     * @return The file ID of the legacy file, or null if it doesn't exist.
     */
    suspend fun findLegacySyncFile(accessToken: String): String? = withContext(Dispatchers.IO) {
        val query = java.net.URLEncoder.encode("name='wammy_sync.json.gz' and trashed=false", "UTF-8")
        val url = "$DRIVE_FILES_URL?spaces=appDataFolder&q=$query&fields=files(id)"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val files = org.json.JSONObject(body).optJSONArray("files")
                if (files != null && files.length() > 0) {
                    return@withContext files.getJSONObject(0).optString("id")
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to find legacy sync file" }
        }
        null
    }

    /**
     * Download the sync file from Drive directly to a temp file (streaming, avoids OOM).
     * @return The temp file containing the backup, or null on failure.
     */
    suspend fun downloadSyncFileToFile(accessToken: String, fileId: String): java.io.File? = withContext(Dispatchers.IO) {
        val url = "$DRIVE_FILES_URL/$fileId?alt=media"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastErrorDetail = parseGoogleError(response.code, response.body?.string())
                    return@withContext null
                }
                val tempFile = java.io.File(context.cacheDir, "wammy_drive_download.tachibk")
                response.body?.byteStream()?.use { inputStream ->
                    tempFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: run {
                    lastErrorDetail = "Failed to stream download data from Google Drive"
                    return@withContext null
                }
                if (tempFile.length() <= 0L) {
                    tempFile.delete()
                    lastErrorDetail = "Downloaded backup file is empty (0 bytes)"
                    return@withContext null
                }
                tempFile
            }
        } catch (e: IOException) {
            logcat(LogPriority.ERROR, e) { "Drive download IOException" }
            lastErrorDetail = "Network error downloading backup: ${e.message}"
            null
        }
    }

    /**
     * Upload (create or update) the sync file in the appdata folder using streaming.
     * @param file The backup file to upload.
     * @param existingFileId If non-null, updates the existing file. Otherwise creates a new one.
     * @return The file ID of the created/updated file, or null on failure.
     */
    suspend fun uploadSyncFile(
        accessToken: String,
        file: java.io.File,
        existingFileId: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        lastErrorDetail = null
        try {
            if (existingFileId != null) {
                // Update existing file (simple upload)
                updateFile(accessToken, existingFileId, file)
            } else {
                // Create new file (multipart upload with metadata)
                createFile(accessToken, file)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Drive upload failed" }
            lastErrorDetail = "Upload failed: ${e.message}"
            null
        }
    }

    private fun createFile(accessToken: String, file: java.io.File): String? {
        val boundary = "wammy_sync_boundary"
        val metadata = """{"name":"$SYNC_FILE_NAME","parents":["appDataFolder"]}"""

        val metadataPart = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata)
            append("\r\n--$boundary\r\n")
            append("Content-Type: application/gzip\r\n")
            append("Content-Transfer-Encoding: binary\r\n\r\n")
        }.toByteArray()
        val endBoundary = "\r\n--$boundary--\r\n".toByteArray()

        val multipartBody = object : RequestBody() {
            override fun contentType() = "multipart/related; boundary=$boundary".toMediaType()
            override fun contentLength() = metadataPart.size.toLong() + file.length() + endBoundary.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) {
                sink.write(metadataPart)
                file.source().use { source ->
                    sink.writeAll(source)
                }
                sink.write(endBoundary)
            }
        }

        val request = Request.Builder()
            .url("$DRIVE_UPLOAD_URL?uploadType=multipart")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(multipartBody)
            .build()

        val body = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastErrorDetail = parseGoogleError(response.code, response.body?.string())
                    return null
                }
                response.body?.string() ?: run {
                    lastErrorDetail = "Empty response received after upload"
                    return null
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "createFile network call failed" }
            lastErrorDetail = "Network call failed during backup upload: ${e.message}"
            return null
        }

        val idRegex = """"id"\s*:\s*"([^"]+)"""".toRegex()
        val fileId = idRegex.find(body)?.groupValues?.get(1)
        if (fileId == null) {
            lastErrorDetail = "Google Drive did not return a file ID in the response"
        }
        return fileId
    }

    private fun updateFile(accessToken: String, fileId: String, file: java.io.File): String? {
        val streamBody = object : RequestBody() {
            override fun contentType() = GZIP_MEDIA_TYPE
            override fun contentLength() = file.length()
            override fun writeTo(sink: okio.BufferedSink) {
                file.source().use { source ->
                    sink.writeAll(source)
                }
            }
        }

        val request = Request.Builder()
            .url("$DRIVE_UPLOAD_URL/$fileId?uploadType=media")
            .addHeader("Authorization", "Bearer $accessToken")
            .patch(streamBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastErrorDetail = parseGoogleError(response.code, response.body?.string())
                    return null
                }
                return fileId
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "updateFile network call failed" }
            lastErrorDetail = "Network call failed during backup update: ${e.message}"
            return null
        }
    }

    /**
     * Delete the sync file from Drive. Used for cleanup/reset.
     */
    suspend fun deleteSyncFile(accessToken: String, fileId: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$DRIVE_FILES_URL/$fileId")
            .addHeader("Authorization", "Bearer $accessToken")
            .delete()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastErrorDetail = parseGoogleError(response.code, response.body?.string())
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "deleteSyncFile failed" }
            lastErrorDetail = "Delete request failed: ${e.message}"
            false
        }
    }
}

/**
 * Result of requesting Drive authorization.
 */
sealed class AuthorizationResult {
    data class Success(val accessToken: String) : AuthorizationResult()
    data class NeedsConsent(val pendingIntent: android.app.PendingIntent) : AuthorizationResult()
    data class Error(val message: String) : AuthorizationResult()
}
