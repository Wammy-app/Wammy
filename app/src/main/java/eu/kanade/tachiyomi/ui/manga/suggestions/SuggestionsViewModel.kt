package eu.kanade.tachiyomi.ui.manga.suggestions

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.manga.FavoriteCategoryAction
import eu.kanade.tachiyomi.ui.manga.filterCategoriesForContent
import eu.kanade.tachiyomi.ui.manga.resolveFavoriteCategoryAction
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
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
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuggestionsViewModel(
    private val mangaId: Long,
    private val getManga: GetManga = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
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
) : ViewModel() {

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

    private val _dialog = MutableStateFlow<Dialog?>(null)
    val dialog: StateFlow<Dialog?> = _dialog.asStateFlow()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launchIO {
            try {
                val manga = getManga.await(mangaId) ?: return@launchIO
                val source = sourceManager.get(manga.source) as? CatalogueSource ?: return@launchIO

                _state.update { it.copy(isFetching = true) }

                val regexWhitespace = Regex("\\s+")
                val regexSpecialCharacters = Regex("([^a-zA-Z0-9 ]|\\s-|-\\s|\\s\\.|\\.\\s)")
                val regexNumberOnly = Regex("^\\d+$")

                fun String.stripKeywordForRelatedMangas(): List<String> {
                    return replace(regexSpecialCharacters, " ")
                        .split(regexWhitespace)
                        .map { it.replace(regexNumberOnly, "").lowercase() }
                        .filter { it.length > 1 }
                }

                val words = HashSet<String>()
                words.add(manga.title)
                manga.title.stripKeywordForRelatedMangas()
                    .filterNot { word -> words.any { it.lowercase() == word } }
                    .onEach { words.add(it) }

                if (words.isEmpty()) {
                    _state.update { it.copy(isFetching = false) }
                    return@launchIO
                }

                val filterList = eu.kanade.tachiyomi.source.model.FilterList()
                val allResults = java.util.concurrent.ConcurrentLinkedQueue<eu.kanade.tachiyomi.source.model.SManga>()

                kotlinx.coroutines.coroutineScope {
                    words.forEach { keyword ->
                        launch {
                            try {
                                val results = source.getSearchManga(1, keyword, filterList).mangas
                                allResults.addAll(results)
                            } catch (e: Exception) {
                                logcat(LogPriority.ERROR, e) { "Failed related manga search: ${e.message}" }
                            }
                        }
                    }
                }

                val domainMangas = allResults.map {
                    it.toDomainManga(source.id, false) // Defaulting to false for isNovel fallback
                }

                val savedMangas = if (domainMangas.isNotEmpty()) {
                    networkToLocalManga(domainMangas.toList())
                } else {
                    emptyList()
                }

                val filteredMangas = savedMangas.filter { it.id != manga.id }.distinctBy { it.url }

                _state.update {
                    it.copy(
                        mangas = filteredMangas,
                        isFetching = false
                    )
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to fetch suggestions: $e" }
                _state.update { it.copy(isFetching = false) }
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

    data class State(
        val isFetching: Boolean = false,
        val mangas: List<Manga> = emptyList()
    )
}
