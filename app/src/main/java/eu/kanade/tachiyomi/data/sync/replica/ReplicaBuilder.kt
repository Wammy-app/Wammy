package eu.kanade.tachiyomi.data.sync.replica

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import tachiyomi.data.Database
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.GetFavorites
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Builds a versioned [Replica] snapshot from Wammy's local database.
 */
class ReplicaBuilder(
    private val database: Database = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
    private val syncSettings: SyncSettings = Injekt.get(),
) {

    suspend fun build(enabledContent: Set<SyncContent>): Replica {
        val categories = if (SyncContent.FAVOURITES in enabledContent) {
            getCategories.await().map { cat ->
                RCategory(
                    key = cat.name,
                    v = System.currentTimeMillis(),
                    name = cat.name,
                    order = cat.order,
                    flags = cat.flags,
                )
            }
        } else emptyList()

        val favourites = if (SyncContent.FAVOURITES in enabledContent) {
            val allFavorites = getFavorites.await()
            allFavorites.map { manga ->
                val mangaCategories = getCategories.await(manga.id).map { it.name }
                val v = maxOf(manga.lastModifiedAt, manga.favoriteModifiedAt ?: 0L, 1L)
                RFavourite(
                    key = "${manga.source}:${manga.url}",
                    v = v,
                    url = manga.url,
                    title = manga.title,
                    source = manga.source,
                    favorite = manga.favorite,
                    categoryNames = mangaCategories,
                    isNovel = manga.isNovel,
                    thumbnailUrl = manga.thumbnailUrl,
                    artist = manga.artist,
                    author = manga.author,
                    description = manga.description,
                    genre = manga.genre.orEmpty(),
                    status = manga.status,
                )
            }
        } else emptyList()

        val history = if (SyncContent.HISTORY in enabledContent) {
            val allFavorites = getFavorites.await()
            val historyList = ArrayList<RHistory>()
            for (manga in allFavorites) {
                val mangaHistories = getHistory.await(manga.id)
                for (h in mangaHistories) {
                    val chapter = try {
                        database.chaptersQueries.getChapterById(h.chapterId).awaitAsOne()
                    } catch (_: Exception) {
                        null
                    } ?: continue

                    val readAt = h.readAt?.time ?: 0L
                    if (readAt > 0L) {
                        historyList.add(
                            RHistory(
                                key = "${manga.source}:${manga.url}:${chapter.url}",
                                v = readAt,
                                mangaUrl = manga.url,
                                source = manga.source,
                                chapterUrl = chapter.url,
                                lastRead = readAt,
                                timeRead = h.readDuration,
                                isNovel = manga.isNovel,
                            ),
                        )
                    }
                }
            }
            historyList
        } else emptyList()

        val tracking = if (SyncContent.TRACKING in enabledContent) {
            val allFavorites = getFavorites.await()
            val trackList = ArrayList<RTracking>()
            for (manga in allFavorites) {
                val dbTracks = database.manga_syncQueries.getTracksByMangaId(manga.id).awaitAsList()
                for (t in dbTracks) {
                    trackList.add(
                        RTracking(
                            key = "${manga.source}:${manga.url}:${t.sync_id}",
                            v = System.currentTimeMillis(),
                            mangaUrl = manga.url,
                            source = manga.source,
                            trackerId = t.sync_id,
                            remoteId = t.remote_id,
                            lastChapterRead = t.last_chapter_read,
                            totalChapters = t.total_chapters,
                            score = t.score,
                            status = t.status,
                            trackingUrl = t.remote_url,
                        ),
                    )
                }
            }
            trackList
        } else emptyList()

        return Replica(
            schema = 1,
            deviceId = syncSettings.deviceId,
            categories = categories,
            favourites = favourites,
            history = history,
            bookmarks = emptyList(),
            tracking = tracking,
            prefs = emptyList(),
            tombstones = emptyList(),
        )
    }
}
