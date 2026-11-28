package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.ui.manga.FavoriteCategoryAction
import eu.kanade.tachiyomi.ui.manga.filterCategoriesForContent
import eu.kanade.tachiyomi.ui.manga.resolveFavoriteCategoryAction
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.viewmodel.StateViewModel
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
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
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.Executors

abstract class SearchViewModel(
    initialState: State = State(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val context: Application = Injekt.get(),
) : StateViewModel<SearchViewModel.State>(initialState) {

    private val coroutineDispatcher = Executors.newFixedThreadPool(5).asCoroutineDispatcher()
    private var searchJob: Job? = null

    private val enabledLanguages = sourcePreferences.enabledLanguages.get()
    private val disabledSources = sourcePreferences.disabledSources.get()
    protected val pinnedSources = sourcePreferences.pinnedSources.get()

    // Group name -> member source ids
    protected val sourceGroups: Map<String, Set<String>> =
        sourcePreferences.groupPinnedSources.get()
            .groupBy({ it.substringBeforeLast("|") }, { it.substringAfterLast("|") })
            .mapValues { (_, ids) -> ids.toSet() }

    private var lastQuery: String? = null
    private var lastSourceFilter: SourceFilter? = null

    protected var extensionFilter: String? = null

    open val sortComparator = { map: Map<Source, SearchItemResult> ->
        compareBy<Source>(
            { (map[it] as? SearchItemResult.Success)?.isEmpty ?: true },
            { "${it.id}" !in pinnedSources },
            { "${it.name.lowercase()} (${it.lang})" },
        )
    }

    init {
        mutableState.update { it.copy(pinGroups = sourceGroups.keys.sorted()) }
        viewModelScope.launch {
            preferences.globalSearchFilterState.changes().collectLatest { state ->
                mutableState.update { it.copy(onlyShowHasResults = state) }
            }
        }
    }

    @Composable
    fun getManga(initialManga: Manga): androidx.compose.runtime.State<Manga> {
        return produceState(initialValue = initialManga, key1 = initialManga.source, key2 = initialManga.url) {
            observeManga(initialManga)
                .catch { /* Keep cached state if row unavailable */ }
                .collectLatest { manga ->
                    value = manga
                }
        }
    }

    open fun getEnabledSources(): List<Source> {
        return sourceManager.getAll()
            .filter { it.lang in enabledLanguages && "${it.id}" !in disabledSources }
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinnedSources },
                    { "${it.name.lowercase()} (${it.lang})" },
                ),
            )
    }

    private fun getSelectedSources(): List<Source> {
        val enabledSources = getEnabledSources()

        val filter = extensionFilter
        if (filter.isNullOrEmpty()) {
            return enabledSources
        }

        return extensionManager.installedExtensionsFlow.value
            .filter { it.pkgName == filter }
            .flatMap { it.sources }
            .filter { it in enabledSources }
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setSourceFilter(filter: SourceFilter) {
        mutableState.update { it.copy(sourceFilter = filter) }
        search()
    }

    fun toggleFilterResults() {
        preferences.globalSearchFilterState.toggle()
    }

    fun search() {
        val query = state.value.searchQuery
        val sourceFilter = state.value.sourceFilter

        if (query.isNullOrBlank()) return

        val sameQuery = this.lastQuery == query
        if (sameQuery && this.lastSourceFilter == sourceFilter) return

        this.lastQuery = query
        this.lastSourceFilter = sourceFilter

        searchJob?.cancel()

        val sources = getSelectedSources()

        // Reuse previous results if possible
        val nextItems = if (sameQuery) {
            val existingResults = state.value.items
            sources.associateWith { existingResults[it] ?: SearchItemResult.Loading }
        } else {
            sources.associateWith { SearchItemResult.Loading }
        }
        updateItems(nextItems, searchedQuery = query)

        val searchQuery = when {
            query.startsWith("tag:", ignoreCase = true) -> query.substringAfter("tag:").trim()
            query.startsWith("genre:", ignoreCase = true) -> query.substringAfter("genre:").trim()
            else -> query
        }

        searchJob = viewModelScope.launchIO {
            sources.map { source ->
                async {
                    if (state.value.items[source] !is SearchItemResult.Loading) {
                        return@async
                    }

                    try {
                        val page = withContext(coroutineDispatcher) {
                            source.getSearchManga(1, searchQuery, source.getFilterList())
                        }

                        val titles = page.mangas
                            .map { it.toDomainManga(source.id, source.isNovelSource()) }
                            .distinctBy { it.url }
                            .let { networkToLocalManga(it) }

                        if (isActive) {
                            updateItem(source, SearchItemResult.Success(titles))
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            updateItem(source, SearchItemResult.Error(e))
                        }
                    } catch (e: LinkageError) {
                        // Outdated/incompatible extensions throw LinkageError subtypes
                        // (NoClassDefFoundError, NoSuchMethodError, etc.) rather than Exception.
                        // Catching only Exception let one broken source crash the whole global
                        // search instead of failing just that source.
                        if (isActive) {
                            updateItem(source, SearchItemResult.Error(e))
                        }
                    }
                }
            }
                .awaitAll()
        }
    }

    private fun updateItems(items: Map<Source, SearchItemResult>, searchedQuery: String) {
        mutableState.update { state ->
            state.copy(
                searchedQuery = searchedQuery,
                items = items.toSortedMap(sortComparator(items)),
            )
        }
    }

    private fun updateItem(source: Source, result: SearchItemResult) {
        if (source !in getSelectedSources()) return
        mutableState.update { state ->
            val newItems = state.items + (source to result)
            state.copy(
                items = newItems.toSortedMap(sortComparator(newItems)),
            )
        }
    }

    fun setMigrateDialog(currentId: Long, target: Manga) {
        viewModelScope.launchIO {
            val current = getManga.await(currentId) ?: return@launchIO
            mutableState.update { it.copy(dialog = Dialog.Migrate(target, current)) }
        }
    }

    fun clearDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun dismissDialog() = clearDialog()

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

    fun onLongClickManga(manga: Manga, skipDuplicateCheck: Boolean = false) {
        viewModelScope.launchIO {
            val resolved = resolveLocalManga(manga)
            if (resolved.favorite) {
                mutableState.update { it.copy(dialog = Dialog.RemoveManga(resolved)) }
                return@launchIO
            }

            if (!skipDuplicateCheck) {
                val duplicates = getDuplicateLibraryManga(resolved)
                if (duplicates.isNotEmpty()) {
                    mutableState.update { it.copy(dialog = Dialog.DuplicateManga(resolved, duplicates)) }
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
        val selection = if (manga.favorite) getCategories.await(manga.id).map { it.id } else emptyList()
        mutableState.update {
            it.copy(
                dialog = Dialog.ChangeCategory(
                    manga = manga,
                    initialSelection = categories.mapAsCheckboxState { category -> category.id in selection },
                ),
            )
        }
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
        mutableState.update { it.copy(dialog = Dialog.Migrate(target = target, current = duplicate)) }
    }

    @Immutable
    data class State(
        val from: Manga? = null,
        val searchQuery: String? = null,
        val searchedQuery: String? = null,
        val sourceFilter: SourceFilter = SourceFilter.PinnedOnly,
        val pinGroups: List<String> = emptyList(),
        val onlyShowHasResults: Boolean = false,
        val items: Map<Source, SearchItemResult> = mapOf(),
        val dialog: Dialog? = null,
    ) {
        val progress: Int = items.count { it.value !is SearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems = items.filter { (_, result) -> result.isVisible(onlyShowHasResults) }
    }

    sealed interface Dialog {
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class DuplicateManga(
            val manga: Manga,
            val duplicates: List<MangaWithChapterCount>,
        ) : Dialog
        data class RemoveManga(val manga: Manga) : Dialog
    }
}

sealed interface SourceFilter {
    data object All : SourceFilter
    data object PinnedOnly : SourceFilter
    data class Group(val name: String) : SourceFilter
}

sealed interface SearchItemResult {
    data object Loading : SearchItemResult

    data class Error(
        val throwable: Throwable,
    ) : SearchItemResult

    data class Success(
        val result: List<Manga>,
    ) : SearchItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || (this is Success && !this.isEmpty)
    }
}
