package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.effectiveRating
import tachiyomi.domain.manga.model.effectiveViews
import tachiyomi.presentation.core.util.plus
import eu.kanade.presentation.browse.components.GlobalSearchCardRow
import tachiyomi.presentation.core.components.material.padding

@Composable
fun BrowseSourceComfortableGrid(
    latestItems: List<Manga>?,
    browseItems: List<Manga>?,
    getMangaState: @Composable (Manga) -> androidx.compose.runtime.State<Manga>,
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    columns: GridCells,
    contentPadding: PaddingValues,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    selectionMode: Boolean = false,
    selection: Set<Manga> = emptySet(),
    translateTitles: Boolean = false,
    translatedTitles: Map<Long, String> = emptyMap(),
    onMangaVisible: (Manga) -> Unit = {},
    titleMaxLines: Int = 2,
    skipCoverLoading: Boolean = false,
    showFeeds: Boolean = false,
    source: eu.kanade.tachiyomi.source.Source? = null,
) {
    LazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        if (showFeeds) {
            if (source?.supportsLatest == true) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.Text(
                            text = tachiyomi.presentation.core.i18n.stringResource(tachiyomi.i18n.MR.strings.latest),
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = androidx.compose.material3.MaterialTheme.padding.medium, vertical = androidx.compose.material3.MaterialTheme.padding.small)
                        )
                        if (latestItems == null) {
                            eu.kanade.presentation.browse.components.GlobalSearchSkeletonRow()
                        } else if (latestItems.isNotEmpty()) {
                            GlobalSearchCardRow(
                                titles = latestItems,
                                getManga = getMangaState,
                                onClick = onMangaClick,
                                onLongClick = onMangaLongClick,
                                selection = selection,
                            )
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        text = tachiyomi.presentation.core.i18n.stringResource(tachiyomi.i18n.MR.strings.popular),
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = androidx.compose.material3.MaterialTheme.padding.medium, vertical = androidx.compose.material3.MaterialTheme.padding.small)
                    )
                    if (browseItems == null) {
                        eu.kanade.presentation.browse.components.GlobalSearchSkeletonRow()
                    } else if (browseItems.isNotEmpty()) {
                        GlobalSearchCardRow(
                            titles = browseItems,
                            getManga = getMangaState,
                            onClick = onMangaClick,
                            onLongClick = onMangaLongClick,
                                selection = selection,
                        )
                    }
                }
            }
        }
        if (mangaList.loadState.prepend is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            val manga by mangaList[index]?.collectAsState() ?: return@items
            val isSelected = selectionMode && manga in selection
            val displayTitle = if (translateTitles) {
                translatedTitles[manga.id] ?: manga.title
            } else {
                manga.title
            }

            if (translateTitles) {
                onMangaVisible(manga)
            }

            BrowseSourceComfortableGridItem(
                manga = manga,
                displayTitle = displayTitle,
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
                isSelected = isSelected,
                // Use titleMaxLines parameter instead of hardcoded 2
                titleMaxLines = titleMaxLines,
                skipCoverLoading = skipCoverLoading,
            )
        }

        if (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceComfortableGridItem(
    manga: Manga,
    displayTitle: String = manga.title,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
    isSelected: Boolean = false,
    titleMaxLines: Int = 2,
    skipCoverLoading: Boolean = false,
    showFeeds: Boolean = false,
    source: eu.kanade.tachiyomi.source.Source? = null,
) {
    MangaComfortableGridItem(
        isSelected = isSelected,
        title = displayTitle,
        titleMaxLines = titleMaxLines,
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            url = if (skipCoverLoading) "" else manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        coverBadgeStart = {
            InLibraryBadge(enabled = manga.favorite)
        },
        rating = manga.effectiveRating,
        views = manga.effectiveViews,
        subtitle = manga.author,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}









