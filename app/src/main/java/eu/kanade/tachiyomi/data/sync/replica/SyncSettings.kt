package eu.kanade.tachiyomi.data.sync.replica

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.util.UUID

/**
 * Persistent settings for Google Drive replica sync.
 * Stored in a dedicated preferences file so they never conflict with general app settings.
 * The unique [deviceId] is persisted in [context.noBackupFilesDir] to guarantee that
 * Android cloud backup will never duplicate this device's identifier to another phone/tablet.
 */
class SyncSettings(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Stored in noBackupFilesDir: Android auto-backup restored on another device
    // will NOT carry this file over, ensuring each physical device has a unique replica.
    private val deviceIdFile = File(context.noBackupFilesDir, "sync_device_id")

    val deviceId: String by lazy {
        deviceIdFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().replace("-", "").also { id ->
                try {
                    deviceIdFile.parentFile?.mkdirs()
                    deviceIdFile.writeText(id)
                } catch (_: Exception) {
                    // Fallback to volatile or log
                }
            }
    }

    var accountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        set(value) = prefs.edit { putString(KEY_ACCOUNT_EMAIL, value) }

    var accountName: String?
        get() = prefs.getString(KEY_ACCOUNT_NAME, null)
        set(value) = prefs.edit { putString(KEY_ACCOUNT_NAME, value) }

    var accountPhotoUrl: String?
        get() = prefs.getString(KEY_ACCOUNT_PHOTO, null)
        set(value) = prefs.edit { putString(KEY_ACCOUNT_PHOTO, value) }

    /** When true, the user's name and email are blurred in the settings screen. */
    var isEmailHidden: Boolean
        get() = prefs.getBoolean(KEY_EMAIL_HIDDEN, false)
        set(value) = prefs.edit { putBoolean(KEY_EMAIL_HIDDEN, value) }

    val isSignedIn: Boolean
        get() = !accountEmail.isNullOrBlank()

    /** Background sync interval in minutes. 0 turns background sync off. Default: 360 (6 hours). */
    var intervalMinutes: Int
        get() = prefs.getString(KEY_INTERVAL, "360")?.toIntOrNull() ?: 360
        set(value) = prefs.edit { putString(KEY_INTERVAL, value.toString()) }

    var isWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit { putBoolean(KEY_WIFI_ONLY, value) }

    var enabledContent: Set<String>
        get() = prefs.getStringSet(KEY_CONTENT, null) ?: SyncContent.DEFAULT_KEYS
        set(value) = prefs.edit { putStringSet(KEY_CONTENT, value) }

    var lastSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SYNC, value) }

    var lastSyncError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit { putString(KEY_LAST_ERROR, value) }

    /** SHA-1 of the replica content last uploaded, used to skip uploading identical data. */
    var lastUploadHash: String?
        get() = prefs.getString(KEY_UPLOAD_HASH, null)
        set(value) = prefs.edit { putString(KEY_UPLOAD_HASH, value) }

    /** Change-tracking fingerprint at last build. Equal fingerprint means nothing changed locally. */
    var lastBuildFingerprint: String?
        get() = prefs.getString(KEY_BUILD_FINGERPRINT, null)
        set(value) = prefs.edit { putString(KEY_BUILD_FINGERPRINT, value) }

    var lastSyncedContent: Set<String>?
        get() = prefs.getStringSet(KEY_SYNCED_CONTENT, null)
        set(value) = prefs.edit { putStringSet(KEY_SYNCED_CONTENT, value) }

    /** Timestamp when this device first joined the replica cloud. 0 means initial join. */
    var replicaSince: Long
        get() = prefs.getLong(KEY_REPLICA_SINCE, 0L)
        set(value) = prefs.edit { putLong(KEY_REPLICA_SINCE, value) }

    /** Clear Google account info on sign-out. */
    fun clearAccount() {
        prefs.edit {
            remove(KEY_ACCOUNT_EMAIL)
            remove(KEY_ACCOUNT_NAME)
            remove(KEY_ACCOUNT_PHOTO)
            remove(KEY_LAST_ERROR)
        }
        clearRemoteState()
    }

    /** Clear remote sync hashes to force a full fresh re-read and upload on next sync. */
    fun clearRemoteState() {
        prefs.edit {
            remove(KEY_LAST_SYNC)
            remove(KEY_UPLOAD_HASH)
            remove(KEY_BUILD_FINGERPRINT)
            remove(KEY_SYNCED_CONTENT)
            remove(KEY_REPLICA_SINCE)
        }
    }

    companion object {
        private const val PREFS_NAME = "replica_sync_settings"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_ACCOUNT_PHOTO = "account_photo"
        private const val KEY_EMAIL_HIDDEN = "email_hidden"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_CONTENT = "content"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_UPLOAD_HASH = "upload_hash"
        private const val KEY_BUILD_FINGERPRINT = "build_fingerprint"
        private const val KEY_SYNCED_CONTENT = "synced_content"
        private const val KEY_REPLICA_SINCE = "replica_since"
    }
}
