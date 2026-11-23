package eu.kanade.tachiyomi.ui.manga.suggestions

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import mihon.feature.migration.dialog.MigrateMangaDialog
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.effectiveRating
import tachiyomi.domain.manga.model.effectiveViews
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

class SuggestionsScreen(
    private val mangaId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<SuggestionsViewModel>(
            factory = viewModelFactory {
                initializer {
                    SuggestionsViewModel(mangaId)
                }
            },
        )
        val state by viewModel.state.collectAsState()
        val dialogState by viewModel.dialog.collectAsState()
        val haptic = LocalHapticFeedback.current

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = "Suggestions") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            if (state.isFetching) {
                LoadingScreen(Modifier.padding(paddingValues))
                return@Scaffold
            }

            val mangas = state.mangas

            if (mangas.isEmpty()) {
                EmptyScreen(
                    stringRes = tachiyomi.i18n.MR.strings.information_no_recent_manga,
                    modifier = Modifier.padding(paddingValues),
                )
                return@Scaffold
            }

            val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(
                    bottom = navBarPadding + 16.dp,
                    start = 8.dp,
                    end = 8.dp,
                    top = 8.dp,
                ),
            ) {
                items(mangas, key = { it.id }) { manga ->
                    val liveManga by produceState(initialValue = manga, key1 = manga.id) {
                        viewModel.observeManga(manga)
                            .catch { /* Keep cached state if row unavailable */ }
                            .collectLatest { value = it }
                    }

                    MangaComfortableGridItem(
                        title = liveManga.title,
                        coverData = MangaCover(
                            mangaId = liveManga.id,
                            sourceId = liveManga.source,
                            isMangaFavorite = liveManga.favorite,
                            url = liveManga.thumbnailUrl,
                            lastModified = liveManga.coverLastModified,
                        ),
                        coverAlpha = if (liveManga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                        coverBadgeStart = {
                            InLibraryBadge(enabled = liveManga.favorite)
                        },
                        coverBadgeEnd = { },
                        rating = liveManga.effectiveRating,
                        views = liveManga.effectiveViews,
                        subtitle = liveManga.author,
                        onLongClick = {
                            viewModel.onLongClickManga(liveManga)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onClick = {
                            navigator.push(MangaScreen(liveManga.id, true))
                        },
                    )
                }
            }
        }

        var reopenCategoryDialogMangaId by rememberSaveable { mutableStateOf<Long?>(null) }
        LaunchedEffect(navigator.lastItem) {
            val targetId = reopenCategoryDialogMangaId
            if (targetId != null && navigator.lastItem is SuggestionsScreen) {
                reopenCategoryDialogMangaId = null
                viewModel.showChangeCategoryDialogForId(targetId)
            }
        }

        val onDismissDialog = viewModel::dismissDialog
        when (val dialog = dialogState) {
            null -> {}
            is SuggestionsViewModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissDialog,
                    onEditCategories = {
                        reopenCategoryDialogMangaId = dialog.manga.id
                        navigator.push(CategoryScreen())
                    },
                    onConfirm = { addCategories, removeCategories ->
                        val currentSelected = dialog.initialSelection
                            .filter {
                                it is CheckboxState.State.Checked ||
                                    it is CheckboxState.TriState.Include
                            }
                            .map { it.value.id }
                            .toMutableSet()
                        currentSelected.addAll(addCategories)
                        currentSelected.removeAll(removeCategories.toSet())
                        viewModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, currentSelected.toList())
                    },
                )
            }
            is SuggestionsViewModel.Dialog.DuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissDialog,
                    onConfirm = {
                        viewModel.onLongClickManga(dialog.manga, skipDuplicateCheck = true)
                    },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { viewModel.showMigrateDialog(duplicate = it, target = dialog.manga) },
                    canAddAnyway = true,
                )
            }
            is SuggestionsViewModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissDialog,
                )
            }
            is SuggestionsViewModel.Dialog.RemoveManga -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissDialog,
                    onConfirm = { viewModel.removeFavorite(dialog.manga) },
                    mangaToRemove = dialog.manga,
                )
            }
        }
    }
}
