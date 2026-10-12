package eu.kanade.tachiyomi.data.sync.replica

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import tachiyomi.data.Database
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

/**
 * Applies merged [SyncChanges] into the local SQLite database.
 */
class ReplicaApplier(
    private val database: Database = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
) {

    suspend fun apply(changes: SyncChanges) {
        if (changes.isEmpty) return

        // 1. Categories
        if (changes.categoriesToUpsert.isNotEmpty()) {
            val existingCategories = getCategories.await().associateBy { it.name }
            var nextOrder = (existingCategories.values.maxOfOrNull { it.order } ?: 0L) + 1L

            for (cat in changes.categoriesToUpsert) {
                if (cat.name !in existingCategories) {
                    database.categoriesQueries.insert(
                        name = cat.name,
                        order = if (cat.order > 0) cat.order else nextOrder++,
                        flags = cat.flags,
                        contentType = 0L,
                    )
                }
            }
        }

        // Cache categories map after potential inserts
        val categoriesByName = getCategories.await().associateBy { it.name }

        // 2. Favourites (Manga & Novels)
        for (fav in changes.favouritesToUpsert) {
            val existingManga = database.mangasQueries
                .getMangaByUrlAndSource(fav.url, fav.source)
                .awaitAsOneOrNull()

            val mangaId = if (existingManga != null) {
                database.mangasQueries.update(
                    mangaId = existingManga._id,
                    source = fav.source,
                    url = fav.url,
                    artist = fav.artist ?: existingManga.artist,
                    author = fav.author ?: existingManga.author,
                    description = fav.description ?: existingManga.description,
                    genre = if (fav.genre.isNotEmpty()) fav.genre else existingManga.genre,
                    title = fav.title,
                    status = if (fav.status > 0) fav.status else existingManga.status,
                    thumbnailUrl = fav.thumbnailUrl ?: existingManga.thumbnail_url,
                    favorite = fav.favorite,
                    lastUpdate = existingManga.last_update,
                    nextUpdate = existingManga.next_update,
                    calculateInterval = existingManga.calculate_interval,
                    initialized = existingManga.initialized,
                    viewerFlags = existingManga.viewer,
                    chapterFlags = existingManga.chapter_flags,
                    coverLastModified = existingManga.cover_last_modified,
                    dateAdded = existingManga.date_added,
                    updateStrategy = existingManga.update_strategy,
                    version = existingManga.version,
                    notes = existingManga.notes,
                )
                existingManga._id
            } else {
                database.mangasQueries.insert(
                    source = fav.source,
                    url = fav.url,
                    artist = fav.artist,
                    author = fav.author,
                    description = fav.description,
                    genre = fav.genre,
                    title = fav.title,
                    status = fav.status,
                    thumbnailUrl = fav.thumbnailUrl,
                    favorite = fav.favorite,
                    lastUpdate = null,
                    nextUpdate = null,
                    calculateInterval = 0L,
                    initialized = false,
                    viewerFlags = 0L,
                    chapterFlags = 0L,
                    coverLastModified = 0L,
                    dateAdded = System.currentTimeMillis(),
                    updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                    version = 0L,
                    isNovel = fav.isNovel,
                )
                database.mangasQueries.selectLastInsertedRowId().awaitAsOne()
            }

            // Assign categories if provided
            if (fav.categoryNames.isNotEmpty()) {
                val catIds = fav.categoryNames.mapNotNull { categoriesByName[it]?.id }
                if (catIds.isNotEmpty()) {
                    setMangaCategories.await(mangaId, catIds)
                }
            }
        }

        // 3. History
        for (h in changes.historyToUpsert) {
            val manga = database.mangasQueries
                .getMangaByUrlAndSource(h.mangaUrl, h.source)
                .awaitAsOneOrNull() ?: continue

            val chapter = database.chaptersQueries
                .getChapterByUrlAndMangaId(h.chapterUrl, manga._id)
                .awaitAsOneOrNull() ?: continue

            database.historyQueries.upsert(
                chapterId = chapter._id,
                readAt = Date(h.lastRead),
                readDuration = h.timeRead,
            )
        }

        // 4. Tracking
        for (tr in changes.trackingToUpsert) {
            val manga = database.mangasQueries
                .getMangaByUrlAndSource(tr.mangaUrl, tr.source)
                .awaitAsOneOrNull() ?: continue

            val existingTrack = database.manga_syncQueries
                .getTrackById(manga._id, tr.trackerId)
                .awaitAsOneOrNull()

            if (existingTrack != null) {
                database.manga_syncQueries.update(
                    id = existingTrack._id,
                    mangaId = manga._id,
                    syncId = tr.trackerId,
                    remoteId = tr.remoteId,
                    libraryId = existingTrack.library_id,
                    title = existingTrack.title,
                    lastChapterRead = tr.lastChapterRead,
                    totalChapters = tr.totalChapters,
                    status = tr.status,
                    score = tr.score,
                    trackingUrl = tr.trackingUrl,
                    startDate = existingTrack.start_date,
                    finishDate = existingTrack.finish_date,
                )
            } else {
                database.manga_syncQueries.insert(
                    mangaId = manga._id,
                    syncId = tr.trackerId,
                    remoteId = tr.remoteId,
                    libraryId = null,
                    title = "",
                    lastChapterRead = tr.lastChapterRead,
                    totalChapters = tr.totalChapters,
                    status = tr.status,
                    score = tr.score,
                    trackingUrl = tr.trackingUrl,
                    startDate = 0L,
                    finishDate = 0L,
                )
            }
        }

        // 5. Deletions (Tombstones)
        for (tomb in changes.itemsToDelete) {
            if (tomb.table == "favourites") {
                // key is "$source:$url"
                val parts = tomb.key.split(":", limit = 2)
                if (parts.size == 2) {
                    val sourceId = parts[0].toLongOrNull() ?: continue
                    val url = parts[1]
                    val existing = database.mangasQueries
                        .getMangaByUrlAndSource(url, sourceId)
                        .awaitAsOneOrNull()
                    if (existing != null && existing.favorite) {
                        database.mangasQueries.resetFavoriteById(existing._id)
                    }
                }
            }
        }
    }
}
