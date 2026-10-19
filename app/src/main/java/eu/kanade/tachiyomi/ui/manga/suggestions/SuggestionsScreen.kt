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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.util.Screen
import tachiyomi.domain.manga.model.effectiveRating
import tachiyomi.domain.manga.model.effectiveViews
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

class SuggestionsScreen(
    private val mangaId: Long
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<SuggestionsViewModel>(
            factory = viewModelFactory {
                initializer {
                    SuggestionsViewModel(mangaId)
                }
            }
        )
        val state by viewModel.state.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = "Suggestions") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            if (state.isFetching) {
                LoadingScreen(Modifier.padding(paddingValues))
                return@Scaffold
            }

            val mangas = state.mangas

            if (mangas.isEmpty()) {
                EmptyScreen(
                    stringRes = tachiyomi.i18n.MR.strings.information_no_recent_manga,
                    modifier = Modifier.padding(paddingValues)
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
                    top = 8.dp
                )
            ) {
                items(mangas) { manga ->
                    MangaComfortableGridItem(
                        title = manga.title,
                        coverData = tachiyomi.domain.manga.model.MangaCover(
                            mangaId = manga.id,
                            sourceId = manga.source,
                            isMangaFavorite = manga.favorite,
                            url = manga.thumbnailUrl,
                            lastModified = manga.coverLastModified
                        ),
                        coverAlpha = if (manga.favorite) 0.3f else 1f,
                        coverBadgeStart = {
                            if (manga.favorite) {
                                tachiyomi.presentation.core.components.Badge(
                                    text = "In library"
                                )
                            }
                        },
                        coverBadgeEnd = { },
                        rating = manga.effectiveRating,
                        views = manga.effectiveViews,
                        subtitle = manga.author,
                        onLongClick = { },
                        onClick = {
                            navigator.push(MangaScreen(manga.id, true))
                        }
                    )
                }
            }
        }
    }
}
