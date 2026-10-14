package eu.kanade.tachiyomi.ui.discover

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.rememberResourceBitmapPainter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.interactor.GetRemoteManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DiscoverFeedScreen(
    private val isLatest: Boolean,
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<DiscoverViewModel>()
        val state by viewModel.state.collectAsState()
        val scope = rememberCoroutineScope()

        var isSourcesExpanded by remember { mutableStateOf(false) }
        val chevronRotation by animateFloatAsState(
            targetValue = if (isSourcesExpanded) 180f else 0f,
            label = "sources_expand_chevron",
        )

        val aggregatedManga = remember(
            viewModel.popularCache.toMap(),
            viewModel.latestCache.toMap(),
            state.sources,
            isLatest,
        ) {
            val result = mutableListOf<Manga>()
            val seenTitles = mutableSetOf<String>()
            val cache = if (isLatest) viewModel.latestCache else viewModel.popularCache
            val lists = state.sources.mapNotNull { cache[it.id] }
            val maxLen = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until maxLen) {
                for (list in lists) {
                    if (i < list.size) {
                        val manga = list[i]
                        val normalized = manga.title.trim().lowercase()
                        if (seenTitles.add(normalized)) {
                            result.add(manga)
                        }
                    }
                }
            }
            result
        }

        val pageTitle = if (isLatest) "Latest" else "Trending"
        val visibleSources = if (isSourcesExpanded) state.sources else state.sources.take(4)
        val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                    navigationIcon = {
                        Box(modifier = Modifier.padding(start = 12.dp, end = 4.dp)) {
                            Surface(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .clickable { navigator.pop() },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = "Back",
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    },
                    title = {
                        Text(
                            text = pageTitle,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { contentPadding ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(
                    top = contentPadding.calculateTopPadding() + 4.dp,
                    bottom = contentPadding.calculateBottomPadding() + 32.dp,
                    start = 16.dp,
                    end = 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // 1. Installed Sources Section (Rounded Square Logos ONLY + Expand Arrow)
                if (state.sources.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize(),
                        ) {
                            Text(
                                text = "Extensions",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )

                            val rows = visibleSources.chunked(4)
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                rows.forEach { rowSources ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        rowSources.forEach { source ->
                                            Box(
                                                modifier = Modifier.weight(1f),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                SourceLogoSquareTile(
                                                    source = source,
                                                    onClick = {
                                                        navigator.push(
                                                            BrowseSourceScreen(
                                                                source.id,
                                                                GetRemoteManga.QUERY_POPULAR,
                                                                isFeed = true,
                                                            ),
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                        repeat(4 - rowSources.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }

                            if (state.sources.size > 4) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Surface(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .clickable { isSourcesExpanded = !isSourcesExpanded },
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .border(
                                                    1.dp,
                                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                                    CircleShape,
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.KeyboardArrowDown,
                                                contentDescription = if (isSourcesExpanded) "Show Less" else "Show More",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .rotate(chevronRotation),
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = if (isLatest) "Latest Releases" else "Popular",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                    }
                }

                // 2. Aggregated Manga Grid from all installed sources
                if (aggregatedManga.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    items(
                        items = aggregatedManga,
                        key = { "${it.source}_${it.url}" },
                    ) { manga ->
                        FeedMangaGridCard(
                            manga = manga,
                            onClick = {
                                scope.launch {
                                    val localManga = viewModel.getNetworkToLocalManga(manga)
                                    navigator.push(MangaScreen(localManga.id))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceLogoSquareTile(
    source: CatalogueSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconBitmap = remember(source.id) {
        Injekt.get<ExtensionManager>().getAppIconForSource(source.id)
            ?.toBitmap()
            ?.asImageBitmap()
    }

    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    RoundedCornerShape(20.dp),
                )
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                iconBitmap != null -> {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = source.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                source is JsSource -> {
                    AsyncImage(
                        model = source.iconUrl,
                        contentDescription = source.name,
                        error = rememberResourceBitmapPainter(id = R.mipmap.ic_default_source),
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                else -> {
                    Image(
                        painter = painterResource(R.mipmap.ic_default_source),
                        contentDescription = source.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedMangaGridCard(
    manga: Manga,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = manga.asMangaCover(),
            contentDescription = manga.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = manga.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
        )
    }
}
