package eu.kanade.tachiyomi.data.sync.replica

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class SyncApiException(val code: Int, message: String) : IOException(message)

/**
 * Minimal Google Drive v3 REST client communicating with the hidden `appDataFolder`.
 * Direct OkHttp calls without bulky SDK dependencies.
 * Transient failures (network drops, HTTP 429, 5xx) are retried with exponential backoff.
 * 401 is surfaced so caller can automatically refresh OAuth tokens.
 */
class GoogleDriveApi(baseClient: OkHttpClient) {

    private val httpClient = baseClient.newBuilder().apply {
        connectTimeout(20, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        writeTimeout(60, TimeUnit.SECONDS)
        callTimeout(0, TimeUnit.SECONDS)
    }.build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class DriveFile(
        @SerialName("id") val id: String,
        @SerialName("name") val name: String = "",
        @SerialName("modifiedTime") val modifiedTime: String? = null,
        @SerialName("md5Checksum") val md5: String? = null,
    ) {
        val modifiedAt: Long
            get() = modifiedTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
    }

    @Serializable
    private data class FileList(
        @SerialName("files") val files: List<DriveFile> = emptyList(),
        @SerialName("nextPageToken") val nextPageToken: String? = null,
    )

    /** Returns all files stored in the hidden appDataFolder. */
    suspend fun listAppData(token: String): List<DriveFile> {
        val result = ArrayList<DriveFile>()
        var pageToken: String? = null
        do {
            val url = "$DRIVE_BASE/files".toHttpUrl().newBuilder()
                .addQueryParameter("spaces", APP_DATA)
                .addQueryParameter("q", "trashed = false")
                .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
                .addQueryParameter("pageSize", "1000")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()

            val page = execute(Request.Builder().url(url).get().authorize(token).build()) { it.parse<FileList>() }
            result += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return result
    }

    /** Downloads raw file bytes. */
    suspend fun download(token: String, fileId: String): ByteArray {
        val request = Request.Builder().url(mediaUrl(fileId)).get().authorize(token).build()
        return execute(request) { it.body.bytes() }
    }

    /** Creates a new file inside appDataFolder. */
    suspend fun create(token: String, name: String, content: RequestBody): DriveFile {
        val metadata = buildJsonObject {
            put("name", name)
            put("parents", buildJsonArray { add(JsonPrimitive(APP_DATA)) })
        }.toString()
        return resumableUpload(token, "$UPLOAD_BASE/files".toHttpUrl(), "POST", metadata, content)
    }

    /** Replaces the content of an existing file. */
    suspend fun update(token: String, fileId: String, content: RequestBody): DriveFile {
        val length = content.contentLength()
        if (length in 0..SIMPLE_UPLOAD_LIMIT) {
            val url = "$UPLOAD_BASE/files/$fileId".toHttpUrl().newBuilder()
                .addQueryParameter("uploadType", "media")
                .addQueryParameter("fields", FILE_FIELDS)
                .build()
            val request = Request.Builder().url(url).patch(content).authorize(token).build()
            return execute(request) { it.parse<DriveFile>() }
        }
        return resumableUpload(token, "$UPLOAD_BASE/files/$fileId".toHttpUrl(), "PATCH", "{}", content)
    }

    /** Deletes a file. Silently succeeds if already removed (404). */
    suspend fun delete(token: String, fileId: String) {
        val request = Request.Builder().url("$DRIVE_BASE/files/$fileId").delete().authorize(token).build()
        try {
            execute(request) { }
        } catch (e: SyncApiException) {
            if (e.code != 404) throw e
        }
    }

    private suspend fun resumableUpload(
        token: String,
        baseUrl: HttpUrl,
        method: String,
        metadata: String,
        content: RequestBody,
    ): DriveFile {
        val initUrl = baseUrl.newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        val init = Request.Builder()
            .url(initUrl)
            .method(method, metadata.toRequestBody(JSON_MEDIA_TYPE))
            .header("X-Upload-Content-Type", OCTET_STREAM.toString())
            .authorize(token)
            .build()
        val session = execute(init) { response ->
            response.header("Location") ?: throw SyncApiException(response.code, "Drive returned no upload session URL")
        }
        val upload = Request.Builder().url(session).put(content).build()
        return execute(upload) { it.parse<DriveFile>() }
    }

    private fun mediaUrl(fileId: String) = "$DRIVE_BASE/files/$fileId".toHttpUrl().newBuilder()
        .addQueryParameter("alt", "media")
        .build()

    private suspend fun <T> execute(request: Request, block: (Response) -> T): T {
        var attempt = 0
        while (true) {
            val failure: IOException = try {
                return withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw response.toError()
                        block(response)
                    }
                }
            } catch (e: SyncApiException) {
                if (e.code != 429 && e.code < 500) throw e
                e
            } catch (e: IOException) {
                e
            }
            if (++attempt >= MAX_ATTEMPTS) throw failure
            delay(RETRY_DELAY_MS shl (attempt - 1))
        }
    }

    private fun Request.Builder.authorize(token: String) = header("Authorization", "Bearer $token")

    private inline fun <reified T> Response.parse(): T = json.decodeFromString(body.string())

    private fun Response.toError(): SyncApiException {
        val details = runCatching { body.string() }.getOrNull()?.takeIf { it.isNotBlank() } ?: message
        return SyncApiException(code, "Drive API error $code: $details")
    }

    companion object {
        private const val APP_DATA = "appDataFolder"
        private const val FILE_FIELDS = "id,name,modifiedTime,md5Checksum"
        private const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val SIMPLE_UPLOAD_LIMIT = 5L * 1024 * 1024
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1500L
        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        fun body(bytes: ByteArray): RequestBody = bytes.toRequestBody(OCTET_STREAM)
    }
}
