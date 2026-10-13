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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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

    @Volatile
    var isRestoring: Boolean = false
        private set

    /**
     * Create a backup and upload it to Google Drive.
     */
    suspend fun backup(): SyncResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (isRestoring) {
                    logcat(LogPriority.WARN) { "DriveSync: Restore is currently in progress, skipping backup" }
                    return@withContext SyncResult.Error("Restore in progress, skipping backup.")
                }

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

                if (tempFile.length() <= 0L) {
                    tempFile.delete()
                    return@withContext SyncResult.Error("Created backup file is empty (0 bytes)")
                }

                val mangaCount = countMangaInBackup(tempFile)
                logcat(LogPriority.INFO) { "DriveSync: Backup created with $mangaCount books (${tempFile.length()} bytes)" }

                // NEVER overwrite cloud data with an empty library (prevents wiping cloud backup after data clear)
                if (mangaCount == 0) {
                    logcat(LogPriority.WARN) { "DriveSync: Local library has 0 books. Aborting backup to protect cloud data." }
                    tempFile.delete()
                    return@withContext SyncResult.Error("Cannot backup: local library has 0 books. Add books to your library first.")
                }

                // Always save an accessible local copy so the user can verify their backup
                try {
                    val localBackupDir = context.getExternalFilesDir(null) ?: context.filesDir
                    val localCopy = File(localBackupDir, "wammy_backup_latest.tachibk")
                    tempFile.copyTo(localCopy, overwrite = true)
                    logcat(LogPriority.INFO) { "DriveSync: Local backup copy saved to ${localCopy.absolutePath} ($mangaCount books)" }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "DriveSync: Failed to save local backup copy" }
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
                    val bookLabel = if (mangaCount == 1) "1 book" else "$mangaCount books"
                    return@withContext SyncResult.Success("Already up to date ($bookLabel)")
                }

                // 3. Get a FRESH token right before upload (the old one may have expired during backup creation)
                val uploadToken = driveApiHelper.getAccessToken()
                    ?: return@withContext SyncResult.Error("Drive authorization expired. Please try again.")

                val existingFileId = driveApiHelper.findSyncFile(uploadToken)

                var uploadedFileId = driveApiHelper.uploadSyncFile(
                    accessToken = uploadToken,
                    file = tempFile,
                    existingFileId = existingFileId,
                )

                // If 401 unauthorized, invalidate token and retry once
                if (uploadedFileId == null && driveApiHelper.lastErrorDetail?.contains("401") == true) {
                    logcat(LogPriority.WARN) { "DriveSync: Upload returned 401, refreshing token and retrying..." }
                    driveApiHelper.invalidateToken(uploadToken)
                    val retryToken = driveApiHelper.getAccessToken()
                    if (retryToken != null) {
                        uploadedFileId = driveApiHelper.uploadSyncFile(
                            accessToken = retryToken,
                            file = tempFile,
                            existingFileId = existingFileId,
                        )
                    }
                }

                // 4. Clean up temp file
                val uploadedSize = tempFile.length()
                tempFile.delete()

                if (uploadedFileId != null) {
                    logcat(LogPriority.INFO) { "DriveSync: Upload complete ($uploadedSize bytes, $mangaCount books)" }
                    
                    // Save hash so we can skip identical uploads next time
                    if (newHash != null) {
                        prefs.edit().putString("last_backup_hash", newHash).apply()
                    }
                    
                    // Clean up legacy sync file if it still exists (safely never deleting our uploaded file)
                    cleanupLegacyFile(uploadToken, uploadedFileId)
                    
                    val bookLabel = if (mangaCount == 1) "1 book" else "$mangaCount books"
                    SyncResult.Success("Backup uploaded to Google Drive ($bookLabel)")
                } else {
                    val detail = driveApiHelper.lastErrorDetail ?: "Failed to upload backup to Google Drive"
                    SyncResult.Error(detail)
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
        isRestoring = true
        try {
            withContext(Dispatchers.IO) {
                try {
                    logcat(LogPriority.INFO) { "DriveSync: Starting restore..." }

                val accessToken = driveApiHelper.getAccessToken()
                    ?: return@withContext SyncResult.Error("Not authorized for Google Drive.")

                // 1. Find the backup file on Drive
                val fileId = driveApiHelper.findSyncFile(accessToken)
                if (fileId == null) {
                    val detail = driveApiHelper.lastErrorDetail
                    if (detail != null) {
                        return@withContext SyncResult.Error(detail)
                    }
                    return@withContext SyncResult.Error("No backup found on Google Drive. Sync your library first!")
                }

                // 2. Download it (streams directly to a temp file — no RAM overload)
                val tempFile = driveApiHelper.downloadSyncFileToFile(accessToken, fileId)
                if (tempFile == null || tempFile.length() <= 0L) {
                    tempFile?.delete()
                    val detail = driveApiHelper.lastErrorDetail ?: "Downloaded backup file is empty or missing"
                    return@withContext SyncResult.Error(detail)
                }

                val mangaCount = countMangaInBackup(tempFile)
                logcat(LogPriority.INFO) { "DriveSync: Downloaded backup contains $mangaCount books (${tempFile.length()} bytes)" }

                if (mangaCount == 0) {
                    tempFile.delete()
                    return@withContext SyncResult.Error("Cloud backup has 0 books. Please add manga to your library and sync first!")
                }

                // Save an accessible local copy of what was downloaded from the cloud
                try {
                    val localBackupDir = context.getExternalFilesDir(null) ?: context.filesDir
                    val localCopy = File(localBackupDir, "wammy_backup_cloud_download.tachibk")
                    tempFile.copyTo(localCopy, overwrite = true)
                } catch (_: Exception) {}

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
                    logcat(LogPriority.INFO) { "DriveSync: Restore complete for $mangaCount books" }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "DriveSync: BackupRestorer failed" }
                    return@withContext SyncResult.Error("Failed to restore backup: ${e.message}")
                } finally {
                    tempFile.delete()
                }

                // Force refresh the library cache so UI updates immediately without needing an app restart
                try {
                    Injekt.get<tachiyomi.domain.manga.interactor.GetLibraryManga>().refreshForced()
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "DriveSync: Failed to force refresh library after restore" }
                }

                // 4. Clear the backup hash so the next backup doesn't incorrectly skip
                val prefs = context.getSharedPreferences("wammy_sync_prefs", Context.MODE_PRIVATE)
                prefs.edit().remove("last_backup_hash").apply()

                val bookLabel = if (mangaCount == 1) "1 book" else "$mangaCount books"
                SyncResult.Success("Library restored from Google Drive! ($bookLabel)")
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "DriveSync: Fatal error during restore" }
                SyncResult.Error(e.message ?: "Unknown restore error")
            }
        }
    } finally {
        isRestoring = false
    }
}

    private suspend fun countMangaInBackup(file: File): Int {
        var count = 0
        try {
            val reader = eu.kanade.tachiyomi.data.backup.BackupProtoReader(context)
            reader.read(Uri.fromFile(file)) { fieldNumber, _ ->
                if (fieldNumber == 1) count++
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "DriveSync: Failed to count manga in backup file" }
        }
        return count
    }
    /**
     * Delete the old legacy sync file (wammy_sync.json.gz) if it still exists on Drive.
     * This is a one-time cleanup — once deleted, the query will never find it again.
     */
    private suspend fun cleanupLegacyFile(accessToken: String, uploadedFileId: String?) {
        try {
            val legacyFileId = driveApiHelper.findLegacySyncFile(accessToken)
            if (legacyFileId != null && legacyFileId != uploadedFileId) {
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
