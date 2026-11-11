package eu.kanade.tachiyomi.ui.discover

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.isNovelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import mihon.domain.manga.model.toDomainManga
import eu.kanade.domain.manga.model.copyFrom
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.ui.manga.FavoriteCategoryAction
import eu.kanade.tachiyomi.ui.manga.filterCategoriesForContent
import eu.kanade.tachiyomi.ui.manga.resolveFavoriteCategoryAction
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.rating
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import eu.kanade.domain.source.model.isAdultSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class DiscoverState(
    val isLoading: Boolean = true,
    val isNovel: Boolean = false,
    val sources: List<CatalogueSource> = emptyList(),
    val regularSources: List<CatalogueSource> = emptyList(),
    val adultSources: List<CatalogueSource> = emptyList(),
    val hasAdultSources: Boolean = false,
)

class DiscoverViewModel(
    val authManager: eu.kanade.tachiyomi.data.auth.AuthManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val jsPluginManager: JsPluginManager = Injekt.get(),
    private val preferences: BasePreferences = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val updateManga: eu.kanade.domain.manga.interactor.UpdateManga = Injekt.get(),
    private val getHistory: tachiyomi.domain.history.interactor.GetHistory = Injekt.get(),
    private val getChaptersByMangaId: tachiyomi.domain.chapter.interactor.GetChaptersByMangaId = Injekt.get(),
    private val getNextChapters: tachiyomi.domain.history.interactor.GetNextChapters = Injekt.get(),
    private val extensionManager: eu.kanade.tachiyomi.extension.ExtensionManager = Injekt.get(),
    private val context: android.app.Application = Injekt.get(),
    private val sourcePreferences: eu.kanade.domain.source.service.SourcePreferences = Injekt.get()
) : StateViewModel<DiscoverState>(DiscoverState()) {

    sealed interface Dialog {
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class DuplicateManga(
            val manga: Manga,
            val duplicates: List<MangaWithChapterCount>,
        ) : Dialog
        data class Migrate(val current: Manga, val target: Manga) : Dialog
        data class RemoveManga(val manga: Manga) : Dialog
    }

    // Separate from DiscoverState so opening dialogs never retriggers DiscoverTab's Crossfade.
    private val _dialog = MutableStateFlow<Dialog?>(null)
    val dialog: StateFlow<Dialog?> = _dialog.asStateFlow()

    val popularCache = mutableStateMapOf<Long, List<Manga>>()
    val latestCache = mutableStateMapOf<Long, List<Manga>>()
    val pageCache = mutableStateMapOf<String, List<Manga>>()
    
    var cacheRevision by mutableIntStateOf(0)
        private set

    fun notifyCacheUpdated() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            cacheRevision++
        } else {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                cacheRevision++
            }
        }
    }
    
    val recentlyRead = MutableStateFlow<List<tachiyomi.domain.history.model.HistoryWithRelations>>(emptyList())
    
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val sessionPopularFetched = mutableSetOf<Long>()
    private val sessionLatestFetched = mutableSetOf<Long>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val flag = java.io.File(context.cacheDir, "discover_cache_v2.flag")
                if (!flag.exists()) {
                    context.cacheDir.listFiles { _, name ->
                        name.startsWith("popular_manga_") || name.startsWith("latest_manga_")
                    }?.forEach { it.delete() }
                    flag.createNewFile()
                }
            } catch (_: Exception) {}
        }

        viewModelScope.launch {
            combine(
                getHistory.subscribeGrouped(""),
                preferences.homeTabIsNovel.changes()
            ) { historyList, isNovel ->
                Pair(historyList, isNovel)
            }.collectLatest { (historyList, isNovel) ->
                val incompleteHistory = withContext(Dispatchers.IO) {
                    val result = mutableListOf<tachiyomi.domain.history.model.HistoryWithRelations>()
                    for (history in historyList) {
                        if (history.isNovel != isNovel) continue

                        val chapters = getChaptersByMangaId.await(history.mangaId)
                        val hasUnread = chapters.any { !it.read }
                        if (!hasUnread) continue

                        val resumeItem = if (history.chapterRead) {
                            val nextChapter = getNextChapters.await(history.mangaId, history.chapterId, onlyUnread = false).firstOrNull { !it.read }
                                ?: getNextChapters.await(history.mangaId, onlyUnread = true).firstOrNull()
                            if (nextChapter != null) {
                                history.copy(
                                    chapterId = nextChapter.id,
                                    chapterNumber = nextChapter.chapterNumber,
                                    chapterRead = nextChapter.read,
                                    lastPageRead = nextChapter.lastPageRead,
                                )
                            } else {
                                history
                            }
                        } else {
                            history
                        }

                        result.add(resumeItem)
                        if (result.size >= 5) break
                    }
                    result
                }
                recentlyRead.value = incompleteHistory
            }
        }

        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                kotlinx.coroutines.flow.combine(
                    sourceManager.sources,
                    jsPluginManager.jsSources,
                    extensionManager.installedExtensionsFlow
                ) { apk, js, _ -> apk + js },
                preferences.homeTabIsNovel.changes(),
                jsPluginManager.isInitialized,
                sourcePreferences.pinnedSources.changes(),
                sourcePreferences.enabledLanguages.changes(),
            ) { allApkJs, isNovel: Boolean, jsInitialized: Boolean, pinnedSourceIds: Set<String>, enabledLanguages: Set<String> ->
                if (!jsInitialized) {
                    return@combine null
                }
                
                val allSources = allApkJs.filterIsInstance<CatalogueSource>().distinctBy { it.id }
                val filtered = allSources.filter { 
                    it.isNovelSource() == isNovel && 
                    it.id != tachiyomi.source.local.LocalSource.ID && 
                    it.id != tachiyomi.source.local.LocalNovelSource.ID &&
                    (it.lang in enabledLanguages || it.lang == "all")
                }
                
                val sorted = filtered.sortedWith(compareBy(
                    { it.id.toString() !in pinnedSourceIds }, // Pinned sources first (false comes before true)
                    { it.name.lowercase() } // Then alphabetical
                ))
                
                Pair(sorted, isNovel)
            }.filterNotNull().collectLatest { (sources, isNovel) ->
                val regularSources = sources.filter { !it.isAdultSource() }
                val adultSources = sources.filter { it.isAdultSource() }
                mutableState.update { 
                    it.copy(
                        isLoading = false,
                        sources = sources,
                        regularSources = regularSources,
                        adultSources = adultSources,
                        hasAdultSources = adultSources.isNotEmpty(),
                        isNovel = isNovel
                    )
                }
                // Pre-fetch active sources to populate Trending, Latest, and Hero
                viewModelScope.launch {
                    sources.forEach { source ->
                        if (!popularCache.containsKey(source.id)) {
                            launch {
                                popularCache[source.id] = loadSourcePopular(source)
                            }
                        }
                        if (!latestCache.containsKey(source.id)) {
                            launch {
                                latestCache[source.id] = loadSourceLatest(source)
                            }
                        }
                    }
                }
            }
        }
    }

    fun toggleNovel(isNovel: Boolean) {
        preferences.homeTabIsNovel.set(isNovel)
    }

    suspend fun loadSourcePopular(source: CatalogueSource): List<Manga> {
        return withContext(Dispatchers.IO) {
            val cacheFile = java.io.File(context.cacheDir, "popular_manga_${source.id}.json")
            var returnList = emptyList<Manga>()
            
            // 1. Instant load from persistent cache if not already in memory
            if (cacheFile.exists() && !popularCache.containsKey(source.id)) {
                try {
                    val cachedList = json.decodeFromString<List<Manga>>(cacheFile.readText())
                    if (cachedList.isNotEmpty()) {
                        if (cachedList.all { it.memo.isEmpty() }) {
                            cacheFile.delete()
                        } else {
                            val enrichedList = cachedList.map { m ->
                                val local = getMangaByUrlAndSourceId.await(m.url, m.source)
                                if (local != null) {
                                    m.copy(
                                        author = m.author?.takeIf { it.isNotBlank() } ?: local.author,
                                        artist = m.artist?.takeIf { it.isNotBlank() } ?: local.artist,
                                        description = m.description?.takeIf { it.isNotBlank() } ?: local.description,
                                        genre = m.genre?.takeIf { it.isNotEmpty() } ?: local.genre,
                                        thumbnailUrl = m.thumbnailUrl?.takeIf { it.isNotBlank() } ?: local.thumbnailUrl,
                                        memo = if (local.memo.isNotEmpty()) JsonObject(local.memo + m.memo) else m.memo,
                                    )
                                } else m
                            }
                            popularCache[source.id] = enrichedList
                            notifyCacheUpdated()
                            returnList = enrichedList
                        }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to read popular cache" }
                }
            } else if (popularCache.containsKey(source.id)) {
                returnList = popularCache[source.id] ?: emptyList()
            }

            // 2. Prevent fetching again in the same session
            if (sessionPopularFetched.contains(source.id)) {
                return@withContext returnList
            }
            sessionPopularFetched.add(source.id)

            // 3. Fetch from network silently in background to update cache
            try {
                val page = source.getPopularManga(1)
                val newList = page.mangas.map { sManga ->
                    val domainManga = sManga.toDomainManga(source.id, source.isNovelSource())
                    val local = getMangaByUrlAndSourceId.await(sManga.url, source.id)
                    if (local != null) {
                        domainManga.copy(
                            author = domainManga.author?.takeIf { it.isNotBlank() } ?: local.author,
                            artist = domainManga.artist?.takeIf { it.isNotBlank() } ?: local.artist,
                            description = domainManga.description?.takeIf { it.isNotBlank() } ?: local.description,
                            genre = domainManga.genre?.takeIf { it.isNotEmpty() } ?: local.genre,
                            thumbnailUrl = domainManga.thumbnailUrl?.takeIf { it.isNotBlank() } ?: local.thumbnailUrl,
                            memo = if (local.memo.isNotEmpty()) JsonObject(local.memo + domainManga.memo) else domainManga.memo,
                        )
                    } else {
                        domainManga
                    }
                }
                popularCache[source.id] = newList
                notifyCacheUpdated()
                cacheFile.writeText(json.encodeToString(newList))
                return@withContext newList
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load popular manga for source ${source.name}" }
                if (!popularCache.containsKey(source.id)) {
                    popularCache[source.id] = emptyList()
                    notifyCacheUpdated()
                }
                return@withContext returnList
            }
        }
    }

    suspend fun loadSourceLatest(source: CatalogueSource): List<Manga> {
        return withContext(Dispatchers.IO) {
            if (!source.supportsLatest) {
                latestCache[source.id] = emptyList()
                notifyCacheUpdated()
                return@withContext emptyList()
            }
            val cacheFile = java.io.File(context.cacheDir, "latest_manga_${source.id}.json")
            var returnList = emptyList<Manga>()
            
            if (cacheFile.exists() && !latestCache.containsKey(source.id)) {
                try {
                    val cachedList = json.decodeFromString<List<Manga>>(cacheFile.readText())
                    if (cachedList.isNotEmpty()) {
                        if (cachedList.all { it.memo.isEmpty() }) {
                            cacheFile.delete()
                        } else {
                            val enrichedList = cachedList.map { m ->
                                val local = getMangaByUrlAndSourceId.await(m.url, m.source)
                                if (local != null) {
                                    m.copy(
                                        author = m.author?.takeIf { it.isNotBlank() } ?: local.author,
                                        artist = m.artist?.takeIf { it.isNotBlank() } ?: local.artist,
                                        description = m.description?.takeIf { it.isNotBlank() } ?: local.description,
                                        genre = m.genre?.takeIf { it.isNotEmpty() } ?: local.genre,
                                        thumbnailUrl = m.thumbnailUrl?.takeIf { it.isNotBlank() } ?: local.thumbnailUrl,
                                        memo = if (local.memo.isNotEmpty()) JsonObject(local.memo + m.memo) else m.memo,
                                    )
                                } else m
                            }
                            latestCache[source.id] = enrichedList
                            notifyCacheUpdated()
                            returnList = enrichedList
                        }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to read latest cache" }
                }
            } else if (latestCache.containsKey(source.id)) {
                returnList = latestCache[source.id] ?: emptyList()
            }

            if (sessionLatestFetched.contains(source.id)) {
                return@withContext returnList
            }
            sessionLatestFetched.add(source.id)

            try {
                val page = source.getLatestUpdates(1)
                val newList = page.mangas.map { sManga ->
                    val domainManga = sManga.toDomainManga(source.id, source.isNovelSource())
                    val local = getMangaByUrlAndSourceId.await(sManga.url, source.id)
                    if (local != null) {
                        domainManga.copy(
                            author = domainManga.author?.takeIf { it.isNotBlank() } ?: local.author,
                            artist = domainManga.artist?.takeIf { it.isNotBlank() } ?: local.artist,
                            description = domainManga.description?.takeIf { it.isNotBlank() } ?: local.description,
                            genre = domainManga.genre?.takeIf { it.isNotEmpty() } ?: local.genre,
                            thumbnailUrl = domainManga.thumbnailUrl?.takeIf { it.isNotBlank() } ?: local.thumbnailUrl,
                            memo = if (local.memo.isNotEmpty()) JsonObject(local.memo + domainManga.memo) else domainManga.memo,
                        )
                    } else {
                        domainManga
                    }
                }
                latestCache[source.id] = newList
                notifyCacheUpdated()
                cacheFile.writeText(json.encodeToString(newList))
                return@withContext newList
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load latest manga for source ${source.name}" }
                if (!latestCache.containsKey(source.id)) {
                    latestCache[source.id] = emptyList()
                    notifyCacheUpdated()
                }
                return@withContext returnList
            }
        }
    }

    fun loadSourcesForPage(sources: List<CatalogueSource>, page: Int, isLatest: Boolean) {
        if (page <= 1) return
        viewModelScope.launch {
            sources.forEach { source ->
                val key = "${source.id}_${if (isLatest) "latest" else "popular"}_$page"
                if (!pageCache.containsKey(key)) {
                    launch {
                        val list = withContext(Dispatchers.IO) {
                            try {
                                val mangasPage = if (isLatest) {
                                    if (!source.supportsLatest) return@withContext emptyList()
                                    source.getLatestUpdates(page)
                                } else {
                                    source.getPopularManga(page)
                                }
                                mangasPage.mangas.map { sManga ->
                                    val domainManga = sManga.toDomainManga(source.id, source.isNovelSource())
                                    val local = getMangaByUrlAndSourceId.await(sManga.url, source.id)
                                    if (local != null) {
                                        domainManga.copy(
                                            author = domainManga.author?.takeIf { it.isNotBlank() } ?: local.author,
                                            artist = domainManga.artist?.takeIf { it.isNotBlank() } ?: local.artist,
                                            description = domainManga.description?.takeIf { it.isNotBlank() } ?: local.description,
                                            genre = domainManga.genre?.takeIf { it.isNotEmpty() } ?: local.genre,
                                            thumbnailUrl = domainManga.thumbnailUrl?.takeIf { it.isNotBlank() } ?: local.thumbnailUrl,
                                            memo = if (local.memo.isNotEmpty()) JsonObject(local.memo + domainManga.memo) else domainManga.memo,
                                        )
                                    } else {
                                        domainManga
                                    }
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) { "Failed to load page $page for ${source.name}" }
                                emptyList()
                            }
                        }
                        pageCache[key] = list
                        notifyCacheUpdated()
                    }
                }
            }
        }
    }

    private val featuredDetailsFetched = mutableSetOf<String>()

    fun fetchFeaturedDetails(items: List<Manga>) {
        viewModelScope.launch(Dispatchers.IO) {
            items.take(5).forEach { manga ->
                if ((manga.author.isNullOrBlank() || manga.rating == null) && featuredDetailsFetched.add("${manga.source}_${manga.url}")) {
                    try {
                        val local = getMangaByUrlAndSourceId.await(manga.url, manga.source)
                        if (local != null && !local.author.isNullOrBlank() && local.rating != null) {
                            updateCachedManga(manga.source, local)
                        } else {
                            val source = sourceManager.get(manga.source) as? CatalogueSource
                            if (source != null) {
                                val updateResult = source.getMangaUpdate(manga.toSManga(), emptyList(), fetchDetails = true, fetchChapters = false)
                                val updated = (local ?: manga).copyFrom(updateResult.manga)
                                networkToLocalManga(updated)
                                updateCachedManga(manga.source, updated)
                            }
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "Failed to load featured manga details for ${manga.title}" }
                    }
                }
            }
        }
    }

    private suspend fun updateCachedManga(sourceId: Long, updated: Manga) {
        withContext(Dispatchers.Main) {
            val currentList = popularCache[sourceId]?.toMutableList() ?: return@withContext
            val idx = currentList.indexOfFirst { it.url == updated.url }
            if (idx != -1) {
                currentList[idx] = updated
                popularCache[sourceId] = currentList
                notifyCacheUpdated()
            }
        }
    }

    private suspend fun resolveLocalManga(manga: Manga): Manga {
        val source = sourceManager.get(manga.source)
        val normalizedUrl = source?.let { eu.kanade.tachiyomi.util.source.normalizeSourcePath(it, manga.url) } ?: manga.url
        val existing = if (normalizedUrl != manga.url) {
            getMangaByUrlAndSourceId.await(normalizedUrl, manga.source)
                ?: getMangaByUrlAndSourceId.await(manga.url, manga.source)
        } else {
            getMangaByUrlAndSourceId.await(manga.url, manga.source)
        }
        return existing ?: networkToLocalManga(manga)
    }

    fun observeManga(manga: Manga): Flow<Manga> {
        val source = sourceManager.get(manga.source)
        val normalizedUrl = source?.let { eu.kanade.tachiyomi.util.source.normalizeSourcePath(it, manga.url) } ?: manga.url
        return if (normalizedUrl == manga.url) {
            getManga.subscribe(manga.url, manga.source).map { it ?: manga }
        } else {
            combine(
                getManga.subscribe(manga.url, manga.source),
                getManga.subscribe(normalizedUrl, manga.source),
            ) { raw, normalized -> normalized ?: raw ?: manga }
        }
    }

    suspend fun getNetworkToLocalManga(manga: Manga): Manga {
        return withContext(Dispatchers.IO) {
            resolveLocalManga(manga)
        }
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    fun onLongClickManga(manga: Manga, skipDuplicateCheck: Boolean = false) {
        viewModelScope.launchIO {
            val resolved = resolveLocalManga(manga)
            if (resolved.favorite) {
                _dialog.value = Dialog.RemoveManga(resolved)
                return@launchIO
            }

            if (!skipDuplicateCheck) {
                val duplicates = getDuplicateLibraryManga(resolved)
                if (duplicates.isNotEmpty()) {
                    _dialog.value = Dialog.DuplicateManga(resolved, duplicates)
                    return@launchIO
                }
            }

            val source = sourceManager.get(resolved.source)
            var targetManga = resolved
            if (source != null) {
                val normalizedUrl = eu.kanade.tachiyomi.util.source.normalizeSourcePath(source, targetManga.url)
                if (normalizedUrl != targetManga.url && updateManga.awaitUpdateUrl(targetManga.id, normalizedUrl)) {
                    targetManga = targetManga.copy(url = normalizedUrl)
                }
            }

            val categories = filterCategoriesForManga(targetManga)
            val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
            val defaultCategory = categories.find { it.id == defaultCategoryId }

            when (resolveFavoriteCategoryAction(categories, defaultCategoryId, defaultCategory)) {
                FavoriteCategoryAction.AddToDefaultCategory -> {
                    if (!updateManga.awaitUpdateFavorite(targetManga.id, true)) return@launchIO
                    setMangaDefaultChapterFlags.await(targetManga)
                    setMangaCategories.await(targetManga.id, listOf(defaultCategory!!.id))
                    source?.let { addTracks.bindEnhancedTrackers(targetManga, it) }
                    eu.kanade.tachiyomi.data.sync.DriveSyncWorker.scheduleBackup(context)
                    withUIContext { context.toast("Added to library") }
                }
                FavoriteCategoryAction.AddUncategorized -> {
                    if (!updateManga.awaitUpdateFavorite(targetManga.id, true)) return@launchIO
                    setMangaDefaultChapterFlags.await(targetManga)
                    setMangaCategories.await(targetManga.id, emptyList())
                    source?.let { addTracks.bindEnhancedTrackers(targetManga, it) }
                    eu.kanade.tachiyomi.data.sync.DriveSyncWorker.scheduleBackup(context)
                    withUIContext { context.toast("Added to library") }
                }
                FavoriteCategoryAction.AskCategory -> {
                    showChangeCategoryDialogFor(targetManga)
                }
            }
        }
    }

    fun showChangeCategoryDialogForId(targetMangaId: Long) {
        viewModelScope.launchIO {
            val manga = mangaRepository.getMangaByIdOrNull(targetMangaId) ?: return@launchIO
            showChangeCategoryDialogFor(manga)
        }
    }

    private suspend fun filterCategoriesForManga(manga: Manga): List<Category> {
        val allCategories = getCategories.await().filterNot { it.isSystemCategory }
        val isJoinedLibrary = libraryPreferences.joinedLibrary.get() &&
            !libraryPreferences.spaceSeparatedLibrary.get()
        return filterCategoriesForContent(allCategories, manga.isNovel, isJoinedLibrary)
    }

    private suspend fun showChangeCategoryDialogFor(manga: Manga) {
        val categories = filterCategoriesForManga(manga)
        val selection = getCategories.await(manga.id).map { it.id }
        _dialog.value = Dialog.ChangeCategory(
            manga = manga,
            initialSelection = categories.mapAsCheckboxState { it.id in selection },
        )
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        viewModelScope.launchIO {
            if (!manga.favorite) {
                if (!updateManga.awaitUpdateFavorite(manga.id, true)) return@launchIO
                setMangaDefaultChapterFlags.await(manga)
                sourceManager.get(manga.source)?.let { addTracks.bindEnhancedTrackers(manga, it) }
            }
            setMangaCategories.await(manga.id, categories)
            eu.kanade.tachiyomi.data.sync.DriveSyncWorker.scheduleBackup(context)
        }
    }

    fun removeFavorite(manga: Manga) {
        viewModelScope.launchIO {
            val target = mangaRepository.getMangaByIdOrNull(manga.id) ?: manga
            if (!target.favorite) return@launchIO
            if (updateManga.awaitUpdateFavorite(target.id, false)) {
                if (target.removeCovers(coverCache) != target) {
                    updateManga.awaitUpdateCoverLastModified(target.id)
                }
                eu.kanade.tachiyomi.data.sync.DriveSyncWorker.scheduleBackup(context)
                withUIContext { context.toast("Removed from library") }
            }
        }
    }

    fun showMigrateDialog(duplicate: Manga, target: Manga) {
        _dialog.value = Dialog.Migrate(current = duplicate, target = target)
    }

    suspend fun getResumeChapter(mangaId: Long, chapterId: Long): tachiyomi.domain.chapter.model.Chapter? {
        return withContext(Dispatchers.IO) {
            getNextChapters.await(mangaId, chapterId, onlyUnread = false).firstOrNull { !it.read }
                ?: getNextChapters.await(mangaId, onlyUnread = true).firstOrNull()
        }
    }
}
