package eu.kanade.tachiyomi.data.sync.replica

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * High-level content categories that users can choose to sync or skip.
 */
enum class SyncContent(val key: String) {
    FAVOURITES("favourites"),
    HISTORY("history"),
    BOOKMARKS("bookmarks"),
    TRACKING("tracking"),
    SETTINGS("settings");

    companion object {
        val DEFAULT_KEYS: Set<String> = entries.map { it.key }.toSet()

        fun fromKeys(keys: Set<String>): Set<SyncContent> =
            entries.filterTo(mutableSetOf()) { it.key in keys }
    }
}

/**
 * Base interface for versioned, cross-device replica records.
 * [key] is a stable cross-device identifier (e.g. source ID + url).
 * [v] is a monotonic timestamp version (higher number = more recent write).
 */
interface SyncRecord {
    val key: String
    val v: Long
}

@Serializable
data class RCategory(
    override val key: String,
    override val v: Long,
    val name: String,
    val order: Long = 0,
    val flags: Long = 0,
) : SyncRecord

@Serializable
data class RFavourite(
    override val key: String,
    override val v: Long,
    val url: String,
    val title: String,
    val source: Long,
    val favorite: Boolean = true,
    val categoryNames: List<String> = emptyList(),
    val isNovel: Boolean = false,
    val thumbnailUrl: String? = null,
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: List<String> = emptyList(),
    val status: Long = 0,
) : SyncRecord

@Serializable
data class RHistory(
    override val key: String,
    override val v: Long,
    val mangaUrl: String,
    val source: Long,
    val chapterUrl: String,
    val lastRead: Long = 0,
    val timeRead: Long = 0,
    val isNovel: Boolean = false,
) : SyncRecord

@Serializable
data class RBookmark(
    override val key: String,
    override val v: Long,
    val mangaUrl: String,
    val source: Long,
    val chapterUrl: String,
    val page: Long = 0,
) : SyncRecord

@Serializable
data class RTracking(
    override val key: String,
    override val v: Long,
    val mangaUrl: String,
    val source: Long,
    val trackerId: Long,
    val remoteId: Long,
    val lastChapterRead: Double = 0.0,
    val totalChapters: Long = 0,
    val score: Double = 0.0,
    val status: Long = 0,
    val trackingUrl: String = "",
) : SyncRecord

@Serializable
data class RPref(
    override val key: String,
    override val v: Long,
    val value: String,
) : SyncRecord

/**
 * Record representing a deletion. Prevents deleted entries from reappearing
 * when syncing with other devices.
 */
@Serializable
data class RTombstone(
    val table: String,
    val key: String,
    val v: Long,
)

/**
 * Entire replica snapshot published by a single device to Google Drive.
 */
@Serializable
data class Replica(
    @SerialName("schema") val schema: Int = 1,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("categories") val categories: List<RCategory> = emptyList(),
    @SerialName("favourites") val favourites: List<RFavourite> = emptyList(),
    @SerialName("history") val history: List<RHistory> = emptyList(),
    @SerialName("bookmarks") val bookmarks: List<RBookmark> = emptyList(),
    @SerialName("tracking") val tracking: List<RTracking> = emptyList(),
    @SerialName("prefs") val prefs: List<RPref> = emptyList(),
    @SerialName("tombstones") val tombstones: List<RTombstone> = emptyList(),
)
