package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.domain.source.model.isAdultSource
import eu.kanade.presentation.browse.components.GlobalSearchCardRow
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.browse.components.GlobalSearchToolbar
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.nameWithTypeTag
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import eu.kanade.tachiyomi.ui.discover.components.DiscoverMangaCard
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.effectiveRating
import tachiyomi.presentation.core.components.material.Scaffold

private val WORD_SPLIT_REGEX = Regex("[^\\p{L}\\p{N}]+")

@Composable
fun GlobalSearchScreen(
    state: SearchViewModel.State,
    navigateUp: () -> Unit,
    onChangeSearchQuery: (String?) -> Unit,
    onSearch: (String) -> Unit,
    onChangeSearchFilter: (SourceFilter) -> Unit,
    onToggleResults: () -> Unit,
    getManga: @Composable (Manga) -> State<Manga>,
    onClickSource: (Source) -> Unit,
    onClickItem: (Manga) -> Unit,
    onLongClickItem: (Manga) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            GlobalSearchToolbar(
                searchQuery = state.searchQuery,
                progress = state.progress,
                total = state.total,
                navigateUp = navigateUp,
                onChangeSearchQuery = onChangeSearchQuery,
                onSearch = onSearch,
                hideSourceFilter = false,
                sourceFilter = state.sourceFilter,
                pinGroups = state.pinGroups,
                onChangeSearchFilter = onChangeSearchFilter,
                onlyShowHasResults = state.onlyShowHasResults,
                onToggleResults = onToggleResults,
                scrollBehavior = scrollBehavior,
                showHasResultsFilter = false,
            )
        },
    ) { paddingValues ->
        val searchedQuery = state.searchedQuery
        if (searchedQuery != null) {
            val gridState = rememberLazyGridState()
            val layoutDirection = LocalLayoutDirection.current
            LaunchedEffect(searchedQuery, state.sourceFilter) {
                gridState.scrollToItem(0, 0)
            }
            val emptyMessage = when {
                state.sourceFilter is SourceFilter.PinnedOnly && state.total == 0 -> {
                    "No pinned sources. Tap \"All\" above to search all sources."
                }
                state.total == 0 -> "No sources available to search."
                else -> "No results found for \"$searchedQuery\"."
            }
            UnifiedSearchGrid(
                items = state.items,
                searchQuery = searchedQuery,
                gridState = gridState,
                onMangaClick = onClickItem,
                contentPadding = PaddingValues(
                    start = paddingValues.calculateStartPadding(layoutDirection) + 12.dp,
                    end = paddingValues.calculateEndPadding(layoutDirection) + 12.dp,
                    top = paddingValues.calculateTopPadding() + 8.dp,
                    bottom = paddingValues.calculateBottomPadding() + 16.dp,
                ),
                getManga = getManga,
                onMangaLongClick = onLongClickItem,
                showInlineLoadingBar = false,
                emptyMessage = emptyMessage,
            )
        }
    }
}

@Composable
fun UnifiedSearchGrid(
    items: Map<Source, SearchItemResult>,
    searchQuery: String,
    gridState: LazyGridState,
    onMangaClick: (Manga) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
    getManga: (@Composable (Manga) -> State<Manga>)? = null,
    onMangaLongClick: ((Manga) -> Unit)? = null,
    showInlineLoadingBar: Boolean = true,
    emptyMessage: String = "No results found for \"$searchQuery\".",
) {
    val mangaResults = remember(items, searchQuery) {
        rankUnifiedSearchResults(items, searchQuery)
    }
    val isLoading = remember(items) {
        items.values.any { it is SearchItemResult.Loading }
    }

    if (mangaResults.isEmpty()) {
        if (isLoading) {
            Box(
                modifier = modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emptyMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            if (isLoading && showInlineLoadingBar) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            items(
                items = mangaResults,
                key = { "${it.source}_${it.id}_${it.url}" },
            ) { manga ->
                val liveManga = if (getManga != null) getManga(manga).value else manga
                DiscoverMangaCard(
                    manga = liveManga,
                    isFavorite = liveManga.favorite,
                    showSourceLogo = true,
                    onClick = { onMangaClick(liveManga) },
                    onLongClick = onMangaLongClick?.let { onLong -> { onLong(liveManga) } },
                )
            }
        }
    }
}

internal fun rankUnifiedSearchResults(
    items: Map<Source, SearchItemResult>,
    searchQuery: String,
    isAdultSource: (Source) -> Boolean = { it.isAdultSource() },
): List<Manga> {
    val query = searchQuery.trim().lowercase()
    return items.entries
        .filter { it.value is SearchItemResult.Success }
        .flatMap { (source, result) ->
            (result as SearchItemResult.Success).result.map { manga ->
                Triple(manga, source, calculateRelevanceScore(manga, query))
            }
        }
        .distinctBy { "${it.first.source}_${it.first.url}" }
        .sortedWith(
            compareByDescending<Triple<Manga, Source, Int>> { it.third }
                .thenBy { isAdultSource(it.second) }
                .thenByDescending { it.first.effectiveRating ?: 0.0 },
        )
        .map { it.first }
}

internal fun calculateRelevanceScore(manga: Manga, query: String): Int {
    if (query.isBlank()) return 0
    val title = manga.title.trim().lowercase()
    val q = query.trim().lowercase()

    // 1. Exact title match
    if (title == q) return 100

    // 2. Title starts with query
    if (title.startsWith(q)) return 90

    // 3. Acronym / Initials match (e.g. "hxh" matching "Hunter x Hunter")
    val words = title.split(WORD_SPLIT_REGEX).filter { it.isNotEmpty() }
    val initials = words.mapNotNull { it.firstOrNull() }.joinToString("")
    if (initials == q || (q.length >= 2 && initials.contains(q))) return 85

    // 4. Word boundary or substring in title
    if (title.contains(q)) return 80

    // 5. Multi-word all match (e.g. query "hunter hunter" in "Hunter x Hunter")
    val qWords = q.split(WORD_SPLIT_REGEX).filter { it.isNotEmpty() }
    if (qWords.size > 1 && qWords.all { title.contains(it) }) return 75

    // 6. Author or Artist match
    val author = manga.author?.trim()?.lowercase()
    if (author?.contains(q) == true) return 60
    val artist = manga.artist?.trim()?.lowercase()
    if (artist?.contains(q) == true) return 60

    // 7. Any query word present in title (if query has multiple words)
    if (qWords.size > 1 && qWords.any { title.contains(it) }) return 40

    return 0
}

@Composable
internal fun GlobalSearchContent(
    items: Map<Source, SearchItemResult>,
    contentPadding: PaddingValues,
    getManga: @Composable (Manga) -> State<Manga>,
    onClickSource: (Source) -> Unit,
    onClickItem: (Manga) -> Unit,
    onLongClickItem: (Manga) -> Unit,
    fromSourceId: Long? = null,
    lazyListState: LazyListState = rememberLazyListState(),
) {
    val isScrolledToTop = remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0
        }
    }
    LaunchedEffect(items.keys.firstOrNull()) {
        if (!lazyListState.isScrollInProgress && isScrolledToTop.value) {
            lazyListState.scrollToItem(0, 0)
        }
    }

    LazyColumn(
        state = lazyListState,
        contentPadding = contentPadding,
    ) {
        items.forEach { (source, result) ->
            item {
                val name = source.nameWithTypeTag()
                GlobalSearchResultItem(
                    title = fromSourceId?.let { "▶ $name".takeIf { source.id == fromSourceId } } ?: name,
                    subtitle = LocaleHelper.getLocalizedDisplayName(source.lang),
                    onClick = { onClickSource(source) },
                ) {
                    when (result) {
                        SearchItemResult.Loading -> {
                            GlobalSearchLoadingResultItem()
                        }
                        is SearchItemResult.Success -> {
                            GlobalSearchCardRow(
                                titles = result.result,
                                getManga = getManga,
                                onClick = onClickItem,
                                onLongClick = onLongClickItem,
                            )
                        }
                        is SearchItemResult.Error -> {
                            GlobalSearchErrorResultItem(message = result.throwable.message)
                        }
                    }
                }
            }
        }
    }
}
