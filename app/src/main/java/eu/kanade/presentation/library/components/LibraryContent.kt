package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.presentation.core.components.material.PullRefresh
import kotlin.time.Duration.Companion.seconds

@Composable
fun LibraryContent(
    categories: List<Category>,
    searchQuery: String?,
    selection: Set<Long>,
    contentPadding: PaddingValues,
    currentPage: Int,
    hasActiveFilters: Boolean,
    isQueryRunning: Boolean,
    showPageTabs: Boolean,
    onChangeCurrentPage: (Int) -> Unit,
    onClickManga: (Long) -> Unit,
    onContinueReadingClicked: ((LibraryManga) -> Unit)?,
    onToggleSelection: (Category, LibraryManga) -> Unit,
    onToggleRangeSelection: (Category, LibraryManga) -> Unit,
    onRefresh: () -> Boolean,
    onGlobalSearchClicked: () -> Unit,
    getItemCountForCategory: (Category) -> Int?,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    getItemsForCategory: (Category) -> List<LibraryItem>,
    titleMaxLines: Int = 2,
    showUrlInList: Boolean = false,
    paginationEnabled: Boolean = false,
    onCategoryFirstVisible: (Category) -> Unit = {},
    onLoadMore: (Category) -> Unit = {},
    getLoadMoreKey: (Category) -> Long = { 0 },
    isCategoryLoading: (Category) -> Boolean = { false },
    onCreateSubCategory: (Category) -> Unit = {},
) {
    Column(
        modifier = Modifier.padding(
            top = (contentPadding.calculateTopPadding() - 8.dp).coerceAtLeast(0.dp),
            start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
            end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
        ),
    ) {
        val topLevelCategories = remember(categories) {
            val top = categories.filter { it.isTopLevel }
            if (top.isEmpty() && categories.isNotEmpty()) categories else top
        }
        val safeCurrentPage = currentPage.coerceIn(0, (topLevelCategories.size - 1).coerceAtLeast(0))
        val pagerState = rememberPagerState(safeCurrentPage) { topLevelCategories.size }

        val scope = rememberCoroutineScope()
        var isRefreshing by remember(pagerState.currentPage) { mutableStateOf(false) }

        val activeCategory = topLevelCategories.getOrNull(pagerState.currentPage)
        val activeSubcategories = remember(categories, activeCategory) {
            if (activeCategory != null) {
                categories.filter { it.parentId == activeCategory.id }
            } else {
                emptyList()
            }
        }
        var selectedSubCategoryId by remember(activeCategory?.id) { mutableStateOf<Long?>(null) }

        if (showPageTabs && topLevelCategories.isNotEmpty() && (topLevelCategories.size > 1 || !topLevelCategories.first().isSystemCategory)) {
            LaunchedEffect(topLevelCategories) {
                if (topLevelCategories.size <= pagerState.currentPage) {
                    pagerState.scrollToPage(topLevelCategories.size - 1)
                }
            }
            LibraryTabs(
                categories = topLevelCategories,
                pagerState = pagerState,
                getItemCountForCategory = getItemCountForCategory,
                onTabItemClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(it)
                    }
                },
            )
        }

        PullRefresh(
            refreshing = isRefreshing,
            enabled = selection.isEmpty(),
            onRefresh = {
                val started = onRefresh()
                if (!started) return@PullRefresh
                scope.launch {
                    // Fake refresh status but hide it after a second as it's a long running task
                    isRefreshing = true
                    delay(1.seconds)
                    isRefreshing = false
                }
            },
        ) {
            LibraryPager(
                state = pagerState,
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                hasActiveFilters = hasActiveFilters,
                selection = selection,
                searchQuery = searchQuery,
                onGlobalSearchClicked = onGlobalSearchClicked,
                getCategoryForPage = { page -> topLevelCategories[page] },
                getDisplayMode = getDisplayMode,
                getColumnsForOrientation = getColumnsForOrientation,
                getItemsForCategory = { category ->
                    val items = getItemsForCategory(category)
                    val subId = selectedSubCategoryId
                    if (subId != null && category.id == activeCategory?.id) {
                        items.filter { it.libraryManga.categories.contains(subId) }
                    } else {
                        items
                    }
                },
                onClickManga = { category, manga ->
                    if (selection.isNotEmpty()) {
                        onToggleSelection(category, manga)
                    } else {
                        onClickManga(manga.manga.id)
                    }
                },
                onLongClickManga = onToggleRangeSelection,
                onClickContinueReading = onContinueReadingClicked,
                titleMaxLines = titleMaxLines,
                showUrlInList = showUrlInList,
                paginationEnabled = paginationEnabled,
                onCategoryFirstVisible = onCategoryFirstVisible,
                onLoadMore = onLoadMore,
                getLoadMoreKey = getLoadMoreKey,
                isCategoryLoading = isCategoryLoading,
                header = { category ->
                    val pageSubcategories = remember(categories, category) {
                        categories.filter { it.parentId == category.id }
                    }
                    SubCategoryChipRow(
                        subcategories = pageSubcategories,
                        selectedSubCategoryId = if (category.id == activeCategory?.id) selectedSubCategoryId else null,
                        getItemCountForCategory = getItemCountForCategory,
                        parentTotalCount = getItemCountForCategory(category),
                        onSelectSubCategory = { selectedSubCategoryId = it },
                        onCreateSubCategory = { onCreateSubCategory(category) },
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                },
            )
        }

        if (isQueryRunning && selection.isEmpty()) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
        }

        LaunchedEffect(pagerState.currentPage) {
            onChangeCurrentPage(pagerState.currentPage)
        }
    }
}
