package eu.kanade.tachiyomi.data.sync.replica

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import tachiyomi.data.Database
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

/**
 * Applies merged [SyncChanges] into the local SQLite database.
 */
class ReplicaApplier(
    private val database: Database = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
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
            val existingManga = mangaRepository.getMangaByUrlAndSourceId(fav.url, fav.source)

            val mangaId = if (existingManga != null) {
                mangaRepository.update(
                    MangaUpdate(
                        id = existingManga.id,
                        favorite = fav.favorite,
                        title = fav.title,
                        artist = fav.artist,
                        author = fav.author,
                        description = fav.description,
                        genre = fav.genre.takeIf { it.isNotEmpty() },
                        thumbnailUrl = fav.thumbnailUrl,
                        status = fav.status.takeIf { it > 0 },
                    ),
                )
                existingManga.id
            } else {
                val newManga = Manga.create().copy(
                    source = fav.source,
                    url = fav.url,
                    title = fav.title,
                    artist = fav.artist,
                    author = fav.author,
                    description = fav.description,
                    genre = fav.genre,
                    thumbnailUrl = fav.thumbnailUrl,
                    status = fav.status,
                    favorite = fav.favorite,
                    isNovel = fav.isNovel,
                    dateAdded = System.currentTimeMillis(),
                )
                val inserted = mangaRepository.insertNetworkManga(listOf(newManga))
                inserted.firstOrNull()?.id ?: 0L
            }

            // Assign categories if provided
            if (mangaId > 0L && fav.categoryNames.isNotEmpty()) {
                val catIds = fav.categoryNames.mapNotNull { categoriesByName[it]?.id }
                if (catIds.isNotEmpty()) {
                    setMangaCategories.await(mangaId, catIds)
                }
            }
        }

        // 3. History
        for (h in changes.historyToUpsert) {
            val manga = mangaRepository.getMangaByUrlAndSourceId(h.mangaUrl, h.source) ?: continue

            val chapter = database.chaptersQueries
                .getChapterByUrlAndMangaId(h.chapterUrl, manga.id)
                .awaitAsOneOrNull() ?: continue

            database.historyQueries.upsert(
                chapterId = chapter._id,
                readAt = Date(h.lastRead),
                time_read = h.timeRead,
            )
        }

        // 4. Tracking
        for (tr in changes.trackingToUpsert) {
            val manga = mangaRepository.getMangaByUrlAndSourceId(tr.mangaUrl, tr.source) ?: continue

            try {
                database.manga_syncQueries.insert(
                    mangaId = manga.id,
                    syncId = tr.trackerId,
                    remoteId = tr.remoteId,
                    libraryId = null,
                    title = "",
                    lastChapterRead = tr.lastChapterRead,
                    totalChapters = tr.totalChapters,
                    status = tr.status,
                    score = tr.score,
                    remoteUrl = tr.trackingUrl,
                    startDate = 0L,
                    finishDate = 0L,
                    `private` = false,
                )
            } catch (_: Exception) {
                // Ignore conflict or failure
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
                    val existing = mangaRepository.getMangaByUrlAndSourceId(url, sourceId)
                    if (existing != null && existing.favorite) {
                        mangaRepository.update(MangaUpdate(id = existing.id, favorite = false))
                    }
                }
            }
        }
    }
}
