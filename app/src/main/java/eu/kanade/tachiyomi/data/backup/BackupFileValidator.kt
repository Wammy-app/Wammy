package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.serialization.protobuf.ProtoBuf
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BackupFileValidator(
    private val context: Context,

    private val sourceManager: SourceManager = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val parser: ProtoBuf = Injekt.get(),
) {

    /**
     * Checks for critical backup file data.
     *
     * @return List of missing sources or missing trackers.
     */
    suspend fun validate(uri: Uri): Results {
        val backupSources = mutableListOf<BackupSource>()
        val trackerIds = mutableSetOf<Long>()
        val mangaSourceIds = mutableSetOf<Long>()
        val reader = BackupProtoReader(context)
        try {
            reader.read(uri) { fieldNumber, data ->
                when (fieldNumber) {
                    1 -> {
                        val migrated = BackupProtoMigration.migrateManga(data)
                        val manga = parser.decodeFromByteArray(BackupManga.serializer(), migrated)
                        manga.tracking.forEach { trackerIds.add(it.syncId.toLong()) }
                        mangaSourceIds.add(manga.source)
                    }
                    101 -> {
                        val source = parser.decodeFromByteArray(BackupSource.serializer(), data)
                        backupSources.add(source)
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }

        val sourceNames = backupSources.associate { it.sourceId to it.name }
        val allSourceIds = (backupSources.map { it.sourceId } + mangaSourceIds).toSet()

        val missingSourceDetails = allSourceIds
            .filter { sourceId ->
                if (sourceId == 0L || sourceId == 1L) return@filter false
                sourceManager.get(sourceId) == null
            }
            .map { id ->
                val rawName = sourceNames[id]
                val displayName = if (!rawName.isNullOrBlank()) {
                    rawName
                } else {
                    sourceManager.getOrStub(id).toString()
                }
                MissingSource(id = id, name = displayName)
            }
            .distinctBy { it.id }
            .sortedBy { it.name }

        val missingSources = missingSourceDetails.map { it.name }.distinct().sorted()

        val missingTrackers = trackerIds
            .mapNotNull { trackerManager.get(it) }
            .filter { !it.isLoggedIn }
            .map { it.name }
            .sorted()

        return Results(
            missingSources = missingSources,
            missingSourceDetails = missingSourceDetails,
            missingTrackers = missingTrackers,
        )
    }

    data class MissingSource(
        val id: Long,
        val name: String,
    )

    data class Results(
        val missingSources: List<String>,
        val missingSourceDetails: List<MissingSource> = emptyList(),
        val missingTrackers: List<String>,
    )
}
