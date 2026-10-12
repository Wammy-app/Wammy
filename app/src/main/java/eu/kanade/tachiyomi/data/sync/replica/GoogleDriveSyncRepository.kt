package eu.kanade.tachiyomi.data.sync.replica

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

sealed interface SyncResult {
    data object Success : SyncResult
    data object SignInRequired : SyncResult
    data class Error(val message: String, val retryable: Boolean = true) : SyncResult
}

/**
 * Two-way Google Drive replica sync repository.
 * Each device publishes its own replica file and merges remote devices' replicas.
 * Only this device's file is ever written to, preventing file-overwrite conflicts.
 */
class GoogleDriveSyncRepository(
    private val context: Context,
    private val syncSettings: SyncSettings,
    private val auth: GoogleDriveAuth,
    private val api: GoogleDriveApi,
    private val builder: ReplicaBuilder,
    private val applier: ReplicaApplier,
) {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val syncMutex = Mutex()

    /**
     * Executes a sync run.
     * @param force If true, waits for an ongoing sync to finish and runs again.
     */
    suspend fun sync(force: Boolean = false): SyncResult {
        if (!syncSettings.isSignedIn) return SyncResult.SignInRequired

        if (force) {
            syncMutex.lock()
        } else if (!syncMutex.tryLock()) {
            return SyncResult.Success // Already syncing
        }

        _isSyncing.value = true
        return try {
            auth.withToken { token -> performSync(token) }
            syncSettings.lastSyncTimestamp = System.currentTimeMillis()
            syncSettings.lastSyncError = null
            SyncResult.Success
        } catch (e: SyncSignInRequiredException) {
            logcat(LogPriority.WARN, e) { "Sync sign-in required" }
            syncSettings.lastSyncError = "Google Drive authorization required"
            SyncResult.SignInRequired
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) { "Google Drive sync failed" }
            val msg = e.message ?: e.javaClass.simpleName
            syncSettings.lastSyncError = msg
            SyncResult.Error(msg)
        } finally {
            _isSyncing.value = false
            syncMutex.unlock()
        }
    }

    private suspend fun performSync(token: String) {
        val enabledContent = SyncContent.fromKeys(syncSettings.enabledContent)
        val ownFileName = REPLICA_PREFIX + syncSettings.deviceId + REPLICA_SUFFIX
        val isJoining = syncSettings.replicaSince == 0L

        // 1. List files in appDataFolder
        val files = api.listAppData(token)
        val ownFile = files.filter { it.name == ownFileName }.maxByOrNull { it.modifiedAt }

        // Filter other devices' replicas
        val remoteFiles = files.filter {
            it.name.startsWith(REPLICA_PREFIX) && it.name != ownFileName && it.name.endsWith(REPLICA_SUFFIX)
        }

        // 2. Download and decode remote replicas
        val remotes = ArrayList<Replica>()
        for (file in remoteFiles) {
            try {
                val bytes = api.download(token, file.id)
                val decompressed = gunzip(bytes)
                val replica = json.decodeFromString<Replica>(decompressed.decodeToString())
                remotes.add(replica)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to decode remote replica: ${file.name}" }
            }
        }

        // 3. Build local replica snapshot
        val local = builder.build(enabledContent)

        // 4. Merge if there are remote replicas
        if (remotes.isNotEmpty()) {
            val changes = ReplicaMerger.merge(
                local = local,
                remotes = remotes,
                enabledContent = enabledContent,
                isJoining = isJoining,
            )
            if (!changes.isEmpty) {
                applier.apply(changes)
                logcat(LogPriority.INFO) { "Applied sync changes from ${remotes.size} remote replica(s)" }
            }
        }

        // 5. Build fresh local replica to upload
        val localToUpload = builder.build(enabledContent)
        val payloadRaw = json.encodeToString(Replica.serializer(), localToUpload).encodeToByteArray()
        val payloadHash = sha1(payloadRaw)

        if (ownFile == null || payloadHash != syncSettings.lastUploadHash) {
            val compressedBytes = gzip(payloadRaw)
            val requestBody = GoogleDriveApi.body(compressedBytes)

            if (ownFile == null) {
                api.create(token, ownFileName, requestBody)
                logcat(LogPriority.INFO) { "Created initial replica file in Google Drive: $ownFileName" }
            } else {
                api.update(token, ownFile.id, requestBody)
                logcat(LogPriority.INFO) { "Updated replica file in Google Drive: ${ownFile.id}" }
            }
            syncSettings.lastUploadHash = payloadHash
        }

        if (syncSettings.replicaSince == 0L) {
            syncSettings.replicaSince = System.currentTimeMillis()
        }
    }

    /** Deletes all replica files belonging to this app from Google Drive. */
    suspend fun deleteRemoteData(): SyncResult {
        return try {
            auth.withToken { token ->
                val files = api.listAppData(token).filter {
                    it.name.startsWith(REPLICA_PREFIX) || it.name.contains("wammy")
                }
                for (file in files) {
                    api.delete(token, file.id)
                }
            }
            syncSettings.clearRemoteState()
            SyncResult.Success
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Failed to delete remote data")
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(bytes) }
        return baos.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        val bais = ByteArrayInputStream(bytes)
        val baos = ByteArrayOutputStream()
        GZIPInputStream(bais).use { it.copyTo(baos) }
        return baos.toByteArray()
    }

    private fun sha1(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val REPLICA_PREFIX = "replica_"
        private const val REPLICA_SUFFIX = ".json.gz"
    }
}
