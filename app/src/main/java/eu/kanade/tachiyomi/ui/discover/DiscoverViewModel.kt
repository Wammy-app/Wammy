package eu.kanade.tachiyomi.ui.discover

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import androidx.compose.runtime.mutableStateMapOf
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
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class DiscoverState(
    val isLoading: Boolean = true,
    val isNovel: Boolean = false,
    val sources: List<CatalogueSource> = emptyList(),
)

class DiscoverViewModel(
    val authManager: eu.kanade.tachiyomi.data.auth.AuthManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val jsPluginManager: JsPluginManager = Injekt.get(),
    private val preferences: BasePreferences = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val updateManga: eu.kanade.domain.manga.interactor.UpdateManga = Injekt.get(),
    private val getHistory: tachiyomi.domain.history.interactor.GetHistory = Injekt.get(),
    private val context: android.app.Application = Injekt.get(),
    private val sourcePreferences: eu.kanade.domain.source.service.SourcePreferences = Injekt.get()
) : StateViewModel<DiscoverState>(DiscoverState()) {

    val popularCache = mutableStateMapOf<Long, List<Manga>>()
    val latestCache = mutableStateMapOf<Long, List<Manga>>()
    
    val recentlyRead = MutableStateFlow<List<tachiyomi.domain.history.model.HistoryWithRelations>>(emptyList())
    
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val sessionPopularFetched = mutableSetOf<Long>()
    private val sessionLatestFetched = mutableSetOf<Long>()

    init {
        viewModelScope.launch {
            combine(
                getHistory.subscribeGrouped(""),
                preferences.homeTabIsNovel.changes()
            ) { historyList, isNovel ->
                historyList.filter { it.isNovel == isNovel }.take(10)
            }.collectLatest { filtered ->
                recentlyRead.value = filtered
            }
        }

        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                kotlinx.coroutines.flow.combine(sourceManager.sources, jsPluginManager.jsSources) { apk, js -> apk + js },
                preferences.homeTabIsNovel.changes(),
                jsPluginManager.isInitialized,
                sourcePreferences.pinnedSources.changes(),
                sourcePreferences.enabledLanguages.changes()
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
                mutableState.update { 
                    it.copy(isLoading = false, sources = sources, isNovel = isNovel)
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
                        popularCache[source.id] = cachedList
                        returnList = cachedList
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
                    Manga.create().copy(
                        source = source.id,
                        url = sManga.url,
                        title = sManga.title,
                        thumbnailUrl = sManga.thumbnail_url,
                        initialized = sManga.initialized,
                        isNovel = source.isNovelSource()
                    )
                }
                popularCache[source.id] = newList
                cacheFile.writeText(json.encodeToString(newList))
                return@withContext newList
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load popular manga for source ${source.name}" }
                if (!popularCache.containsKey(source.id)) {
                    popularCache[source.id] = emptyList()
                }
                return@withContext returnList
            }
        }
    }

    suspend fun loadSourceLatest(source: CatalogueSource): List<Manga> {
        return withContext(Dispatchers.IO) {
            val cacheFile = java.io.File(context.cacheDir, "latest_manga_${source.id}.json")
            var returnList = emptyList<Manga>()
            
            if (cacheFile.exists() && !latestCache.containsKey(source.id)) {
                try {
                    val cachedList = json.decodeFromString<List<Manga>>(cacheFile.readText())
                    if (cachedList.isNotEmpty()) {
                        latestCache[source.id] = cachedList
                        returnList = cachedList
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
                    Manga.create().copy(
                        source = source.id,
                        url = sManga.url,
                        title = sManga.title,
                        thumbnailUrl = sManga.thumbnail_url,
                        initialized = sManga.initialized,
                        isNovel = source.isNovelSource()
                    )
                }
                latestCache[source.id] = newList
                cacheFile.writeText(json.encodeToString(newList))
                return@withContext newList
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load latest manga for source ${source.name}" }
                if (!latestCache.containsKey(source.id)) {
                    latestCache[source.id] = emptyList()
                }
                return@withContext returnList
            }
        }
    }

    suspend fun getNetworkToLocalManga(manga: Manga): Manga {
        return withContext(Dispatchers.IO) {
            networkToLocalManga(manga)
        }
    }

    fun toggleFavorite(manga: Manga, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val localManga = networkToLocalManga(manga)
            val newFavorite = !localManga.favorite
            updateManga.awaitUpdateFavorite(localManga.id, newFavorite)
            withContext(Dispatchers.Main) {
                onResult(newFavorite)
            }
        }
    }
}
