package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.security.MessageDigest

/**
 * Orchestrates Google Drive cloud sync using the existing tachibk backup system.
 *
 * Upload: BackupCreator → temp .tachibk file → upload to Drive appdata folder
 * Restore: Download from Drive → temp .tachibk file → BackupRestorer
 *
 * Only 1 file ever exists on Drive — it gets overwritten each sync.
 */
class DriveSyncManager(
    private val context: Context,
    private val driveApiHelper: DriveApiHelper,
) {
    private val syncMutex = Mutex()

    /**
     * Create a backup and upload it to Google Drive.
     */
    suspend fun backup(): SyncResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                logcat(LogPriority.INFO) { "DriveSync: Starting backup..." }

                val accessToken = driveApiHelper.getAccessToken()
                    ?: return@withContext SyncResult.Error("Not authorized for Google Drive. Please enable sync first.")

                // 1. Create tachibk to a temp file using the existing BackupCreator
                val tempFile = File(context.cacheDir, "wammy_drive_backup.tachibk")
                if (tempFile.exists()) tempFile.delete()
                tempFile.createNewFile()
                try {
                    val creator = BackupCreator(context, isAutoBackup = false)
                    val options = BackupOptions(
                        libraryEntries = true,
                        includeManga = true,
                        includeNovels = true,
                        categories = true,
                        chapters = true,
                        tracking = true,
                        history = true,
                        readEntries = true,
                        appSettings = true,
                        extensionStores = true,
                        sourceSettings = true,
                        privateSettings = true, // Includes tracker logins
                    )
                    // Write backup directly to the temp file
                    val fileUri = Uri.fromFile(tempFile)
                    creator.backup(fileUri, options)
                    logcat(LogPriority.INFO) { "DriveSync: Backup created (${tempFile.length()} bytes)" }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "DriveSync: BackupCreator failed" }
                    return@withContext SyncResult.Error("Failed to create backup: ${e.message}")
                }

                // 2. Hash check: skip upload if backup is identical to the last one
                //    Stream the file through the digest to avoid loading it all into RAM
                val newHash = try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    tempFile.inputStream().use { inputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            digest.update(buffer, 0, bytesRead)
                        }
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                } catch (e: Exception) {
                    null // If hashing fails, just upload anyway
                }
                
                val prefs = context.getSharedPreferences("wammy_sync_prefs", Context.MODE_PRIVATE)
                val lastHash = prefs.getString("last_backup_hash", null)
                
                if (newHash != null && newHash == lastHash) {
                    logcat(LogPriority.INFO) { "DriveSync: Backup unchanged (hash match), skipping upload" }
                    tempFile.delete()
                    return@withContext SyncResult.Success("Already up to date")
                }

                // 3. Get a FRESH token right before upload (the old one may have expired during backup creation)
                val uploadToken = driveApiHelper.getAccessToken()
                    ?: return@withContext SyncResult.Error("Drive authorization expired. Please try again.")

                val existingFileId = driveApiHelper.findSyncFile(uploadToken)

                val uploadedFileId = driveApiHelper.uploadSyncFile(
                    accessToken = uploadToken,
                    file = tempFile,
                    existingFileId = existingFileId,
                )

                // 4. Clean up temp file
                val uploadedSize = tempFile.length()
                tempFile.delete()

                if (uploadedFileId != null) {
                    logcat(LogPriority.INFO) { "DriveSync: Upload complete ($uploadedSize bytes)" }
                    
                    // Save hash so we can skip identical uploads next time
                    if (newHash != null) {
                        prefs.edit().putString("last_backup_hash", newHash).apply()
                    }
                    
                    // Clean up legacy sync file if it still exists
                    cleanupLegacyFile(uploadToken)
                    
                    SyncResult.Success("Backup uploaded to Google Drive")
                } else {
                    SyncResult.Error("Failed to upload backup to Google Drive")
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "DriveSync: Fatal error during backup" }
                SyncResult.Error(e.message ?: "Unknown backup error")
            }
        }
    }

    /**
     * Download backup from Google Drive and restore it.
     */
    suspend fun restore(): SyncResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                logcat(LogPriority.INFO) { "DriveSync: Starting restore..." }

                val accessToken = driveApiHelper.getAccessToken()
                    ?: return@withContext SyncResult.Error("Not authorized for Google Drive.")

                // 1. Find the backup file on Drive
                val fileId = driveApiHelper.findSyncFile(accessToken)
                    ?: return@withContext SyncResult.Error("No backup found on Google Drive. Sync your library first!")

                // 2. Download it (streams directly to a temp file — no RAM overload)
                val tempFile = driveApiHelper.downloadSyncFileToFile(accessToken, fileId)
                    ?: return@withContext SyncResult.Error("Failed to download backup from Google Drive")

                logcat(LogPriority.INFO) { "DriveSync: Downloaded ${tempFile.length()} bytes" }

                // 3. Restore using the existing BackupRestorer
                try {
                    val notifier = BackupNotifier(context)
                    val restorer = BackupRestorer(
                        context = context,
                        notifier = notifier,
                        isSync = true,
                    )
                    val options = RestoreOptions(
                        libraryEntries = true,
                        includeManga = true,
                        includeNovels = true,
                        categories = true,
                        appSettings = true,
                        extensionStores = true,
                        sourceSettings = true,
                    )
                    restorer.restore(Uri.fromFile(tempFile), options)
                    logcat(LogPriority.INFO) { "DriveSync: Restore complete" }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "DriveSync: BackupRestorer failed" }
                    return@withContext SyncResult.Error("Failed to restore backup: ${e.message}")
                } finally {
                    tempFile.delete()
                }

                // 4. Clear the backup hash so the next backup doesn't incorrectly skip
                val prefs = context.getSharedPreferences("wammy_sync_prefs", Context.MODE_PRIVATE)
                prefs.edit().remove("last_backup_hash").apply()

                SyncResult.Success("Library restored from Google Drive!")
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "DriveSync: Fatal error during restore" }
                SyncResult.Error(e.message ?: "Unknown restore error")
            }
        }
    }
    /**
     * Delete the old legacy sync file (wammy_sync.json.gz) if it still exists on Drive.
     * This is a one-time cleanup — once deleted, the query will never find it again.
     */
    private suspend fun cleanupLegacyFile(accessToken: String) {
        try {
            val legacyFileId = driveApiHelper.findLegacySyncFile(accessToken)
            if (legacyFileId != null) {
                val deleted = driveApiHelper.deleteSyncFile(accessToken, legacyFileId)
                if (deleted) {
                    logcat(LogPriority.INFO) { "DriveSync: Cleaned up legacy wammy_sync.json.gz" }
                }
            }
        } catch (e: Exception) {
            // Non-critical — just log and move on
            logcat(LogPriority.WARN, e) { "DriveSync: Failed to cleanup legacy file (non-critical)" }
        }
    }

    /**
     * Delete all backup files from Google Drive appdata folder and reset local sync state.
     */
    suspend fun deleteCloudBackup(): SyncResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val accessToken = driveApiHelper.getAccessToken()
                    ?: return@withContext SyncResult.Error("Not authorized for Google Drive.")

                var deletedAny = false
                val fileId = driveApiHelper.findSyncFile(accessToken)
                if (fileId != null) {
                    val deleted = driveApiHelper.deleteSyncFile(accessToken, fileId)
                    if (deleted) deletedAny = true
                }

                val legacyFileId = driveApiHelper.findLegacySyncFile(accessToken)
                if (legacyFileId != null) {
                    val deleted = driveApiHelper.deleteSyncFile(accessToken, legacyFileId)
                    if (deleted) deletedAny = true
                }

                // Clear sync timestamp & hash
                val prefs = context.getSharedPreferences(DriveSyncWorker.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .remove("last_backup_hash")
                    .remove(DriveSyncWorker.KEY_LAST_SYNC_TIME)
                    .putBoolean(DriveSyncWorker.KEY_DATA_DIRTY, false)
                    .apply()

                if (deletedAny) {
                    SyncResult.Success("Cloud sync data removed from Google Drive")
                } else {
                    SyncResult.Success("No cloud backups found to delete")
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "DriveSync: Failed to delete cloud backup" }
                SyncResult.Error(e.message ?: "Failed to delete sync data")
            }
        }
    }
}

sealed class SyncResult {
    data class Success(val message: String) : SyncResult()
    data class Error(val message: String) : SyncResult()
}
