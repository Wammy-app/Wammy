package eu.kanade.tachiyomi.ui.discover

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.data.auth.AuthManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.builtin.BuiltinSourceManager
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.discover.components.DiscoverMangaCard
import eu.kanade.tachiyomi.ui.discover.components.DiscoverSkeletonScreen
import eu.kanade.tachiyomi.ui.discover.components.FeedGridSkeleton
import eu.kanade.tachiyomi.ui.discover.components.HeroFeaturedCard
import eu.kanade.tachiyomi.ui.discover.components.HeroFeaturedCardSkeleton
import eu.kanade.tachiyomi.ui.discover.components.HomeTopBar
import eu.kanade.tachiyomi.ui.discover.components.rememberShimmerBrush
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class DiscoverSection {
    LATEST,
    POPULAR,
}

private const val PAGE_SIZE = 12

object DiscoverTab : Tab {

    override val options: cafe.adriel.voyager.navigator.tab.TabOptions
        @Composable
        get() {
            val title = "Home"
            val icon = rememberVectorPainter(Icons.Outlined.Home)
            return remember {
                cafe.adriel.voyager.navigator.tab.TabOptions(
                    index = 0u,
                    title = title,
                    icon = icon,
                )
            }
        }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<DiscoverViewModel>()
        val state by viewModel.state.collectAsState()
        val scope = rememberCoroutineScope()
        val tabNavigator = LocalTabNavigator.current
        val context = androidx.compose.ui.platform.LocalContext.current

        LaunchedEffect(Unit) {
            (context as? eu.kanade.tachiyomi.ui.main.MainActivity)?.ready = true
        }

        val authManager: AuthManager = remember { Injekt.get() }
        val user by authManager.currentUser.collectAsState()

        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
        val updatesCount by libraryPreferences.newUpdatesCount.collectAsState()

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

        var defaultSection by rememberSaveable { mutableStateOf(DiscoverSection.LATEST) }
        var defaultPage by rememberSaveable { mutableIntStateOf(1) }
        var extensionSection by rememberSaveable { mutableStateOf(DiscoverSection.LATEST) }
        var extensionIsAdult by rememberSaveable { mutableStateOf(false) }
        var extensionPage by rememberSaveable { mutableIntStateOf(1) }
        val gridState = rememberLazyGridState()

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                HomeTopBar(
                    isNovel = state.isNovel,
                    updatesCount = updatesCount,
                    user = user,
                    onUpdatesClick = { navigator.push(eu.kanade.tachiyomi.ui.updates.UpdatesTab) },
                    onSearchClick = { navigator.push(eu.kanade.tachiyomi.ui.search.UserSearchScreen()) },
                    onProfileClick = { navigator.push(eu.kanade.tachiyomi.ui.profile.ProfileScreen()) },
                    onToggleMode = { viewModel.toggleNovel(!state.isNovel) },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                SwipeUpFab(onSlideUpTriggered = { viewModel.toggleNovel(!state.isNovel) })
            }
        ) { contentPadding ->
            androidx.compose.animation.Crossfade(
                targetState = state,
                animationSpec = tween(400),
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxSize(),
                label = "discover",
            ) { animatedState ->
                if (animatedState.isLoading) {
                    DiscoverSkeletonScreen()
                } else {
                    val cacheRevision = viewModel.cacheRevision

                    val builtinRegularSources = remember(animatedState.regularSources) {
                        animatedState.regularSources.filter { BuiltinSourceManager.isBuiltin(it.id) }
                    }

                    val trendingAggregated = remember(cacheRevision, builtinRegularSources) {
                        val result = mutableListOf<Manga>()
                        val seenTitles = mutableSetOf<String>()
                        val lists = builtinRegularSources.mapNotNull { viewModel.popularCache[it.id] }
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

                    val featuredManga = remember(trendingAggregated) {
                        trendingAggregated.take(5)
                    }

                    LaunchedEffect(featuredManga) {
                        if (featuredManga.isNotEmpty()) {
                            viewModel.fetchFeaturedDetails(featuredManga)
                        }
                    }

                    val builtinSources = remember(animatedState.regularSources, defaultSection) {
                        val s = animatedState.regularSources.filter { BuiltinSourceManager.isBuiltin(it.id) }
                        if (defaultSection == DiscoverSection.LATEST) s.filter { it.supportsLatest } else s
                    }

                    val extensionRegularSources = remember(animatedState.regularSources) {
                        animatedState.regularSources.filter { !BuiltinSourceManager.isBuiltin(it.id) }
                    }
                    val extensionAdultSources = remember(animatedState.adultSources) {
                        animatedState.adultSources.filter { !BuiltinSourceManager.isBuiltin(it.id) }
                    }
                    val hasExtensionAdultSources = extensionAdultSources.isNotEmpty()
                    val hasAnyExtensionSources = extensionRegularSources.isNotEmpty() || extensionAdultSources.isNotEmpty()

                    val activeExtensionSources = remember(extensionRegularSources, extensionAdultSources, extensionIsAdult, extensionSection) {
                        val s = if (extensionIsAdult) extensionAdultSources else extensionRegularSources
                        if (extensionSection == DiscoverSection.LATEST) s.filter { it.supportsLatest } else s
                    }

                    fun extractMangaFromSources(sources: List<CatalogueSource>, isLatest: Boolean): List<Manga> {
                        val result = mutableListOf<Manga>()
                        val seenTitles = mutableSetOf<String>()

                        val cache = if (isLatest) viewModel.latestCache else viewModel.popularCache
                        val lists1 = sources.mapNotNull { cache[it.id] }
                        val maxLen1 = lists1.maxOfOrNull { it.size } ?: 0
                        for (i in 0 until maxLen1) {
                            for (list in lists1) {
                                if (i < list.size) {
                                    val manga = list[i]
                                    val normalized = manga.title.trim().lowercase()
                                    if (seenTitles.add(normalized)) {
                                        result.add(manga)
                                    }
                                }
                            }
                        }

                        val keySuffix = if (isLatest) "latest" else "popular"
                        var p = 2
                        while (true) {
                            val listsP = sources.mapNotNull { viewModel.pageCache["${it.id}_${keySuffix}_$p"] }
                            if (listsP.isEmpty()) break
                            val maxLenP = listsP.maxOfOrNull { it.size } ?: 0
                            for (i in 0 until maxLenP) {
                                for (list in listsP) {
                                    if (i < list.size) {
                                        val manga = list[i]
                                        val normalized = manga.title.trim().lowercase()
                                        if (seenTitles.add(normalized)) {
                                            result.add(manga)
                                        }
                                    }
                                }
                            }
                            p++
                        }
                        return result
                    }

                    val allBuiltinManga = remember(
                        defaultSection,
                        cacheRevision,
                        builtinSources,
                    ) {
                        extractMangaFromSources(builtinSources, defaultSection == DiscoverSection.LATEST)
                    }

                    val allExtensionManga = remember(
                        extensionSection,
                        extensionIsAdult,
                        cacheRevision,
                        activeExtensionSources,
                    ) {
                        extractMangaFromSources(activeExtensionSources, extensionSection == DiscoverSection.LATEST)
                    }

                    val totalBuiltin = allBuiltinManga.size
                    val totalBuiltinPages = maxOf(1, (totalBuiltin + PAGE_SIZE - 1) / PAGE_SIZE)
                    val safeBuiltinPage = defaultPage.coerceIn(1, totalBuiltinPages)
                    val startBuiltinIndex = (safeBuiltinPage - 1) * PAGE_SIZE
                    val displayedBuiltinManga = allBuiltinManga.drop(startBuiltinIndex).take(PAGE_SIZE)

                    val totalExtension = allExtensionManga.size
                    val totalExtensionPages = maxOf(1, (totalExtension + PAGE_SIZE - 1) / PAGE_SIZE)
                    val safeExtensionPage = extensionPage.coerceIn(1, totalExtensionPages)
                    val startExtensionIndex = (safeExtensionPage - 1) * PAGE_SIZE
                    val displayedExtensionManga = allExtensionManga.drop(startExtensionIndex).take(PAGE_SIZE)

                    // Prefetch next source page when approaching the end of available items
                    val nextBuiltinSourcePage = (totalBuiltin / 20) + 2
                    LaunchedEffect(safeBuiltinPage, builtinSources, totalBuiltin) {
                        if (safeBuiltinPage * PAGE_SIZE >= totalBuiltin - (PAGE_SIZE * 2) && builtinSources.isNotEmpty()) {
                            viewModel.loadSourcesForPage(
                                sources = builtinSources,
                                page = nextBuiltinSourcePage,
                                isLatest = (defaultSection == DiscoverSection.LATEST),
                            )
                        }
                    }

                    val nextExtensionSourcePage = (totalExtension / 20) + 2
                    LaunchedEffect(safeExtensionPage, activeExtensionSources, totalExtension) {
                        if (safeExtensionPage * PAGE_SIZE >= totalExtension - (PAGE_SIZE * 2) && activeExtensionSources.isNotEmpty()) {
                            viewModel.loadSourcesForPage(
                                sources = activeExtensionSources,
                                page = nextExtensionSourcePage,
                                isLatest = (extensionSection == DiscoverSection.LATEST),
                            )
                        }
                    }

                    val allCachesLoaded = remember(cacheRevision, animatedState.sources) {
                        animatedState.sources.all { viewModel.popularCache.containsKey(it.id) }
                    }
                    val hasNoMangaAtAll = remember(allCachesLoaded, cacheRevision) {
                        allCachesLoaded && viewModel.popularCache.values.all { it.isEmpty() }
                    }
                    val hasAnyContent = remember(trendingAggregated, allBuiltinManga, allExtensionManga) {
                        trendingAggregated.isNotEmpty() || allBuiltinManga.isNotEmpty() || allExtensionManga.isNotEmpty()
                    }

                    if (animatedState.sources.isEmpty() || hasNoMangaAtAll) {
                        EmptyDiscoverScreen(
                            isNovel = animatedState.isNovel,
                            onBrowseExtensions = { tabNavigator.current = BrowseTab }
                        )
                    } else if (!hasAnyContent) {
                        DiscoverSkeletonScreen()
                    } else {
                        val shimmerBrush = rememberShimmerBrush()

                        var hasSettledInitialTop by rememberSaveable(animatedState.isNovel) { mutableStateOf(false) }

                        LaunchedEffect(featuredManga.isNotEmpty()) {
                            if (featuredManga.isNotEmpty() && !hasSettledInitialTop) {
                                hasSettledInitialTop = true
                                if (!gridState.isScrollInProgress && gridState.firstVisibleItemIndex <= 1) {
                                    gridState.scrollToItem(0, 0)
                                }
                            }
                        }

                        LaunchedEffect(animatedState.isNovel) {
                            gridState.scrollToItem(0, 0)
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 0.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // 1. Hero Featured Card at Top (Consistent stable key so scroll anchor never drops to filters)
                            item(key = "hero_section", span = { GridItemSpan(maxLineSpan) }, contentType = "hero") {
                                if (featuredManga.isNotEmpty()) {
                                    HeroFeaturedCard(
                                        items = featuredManga,
                                        onClick = { manga ->
                                            scope.launch {
                                                val localManga = viewModel.getNetworkToLocalManga(manga)
                                                navigator.push(MangaScreen(localManga.id))
                                            }
                                        },
                                        onSubscribe = { manga ->
                                            viewModel.toggleFavorite(manga) { isFav ->
                                                context.toast(if (isFav) "Added to library" else "Removed from library")
                                            }
                                        },
                                    )
                                } else {
                                    HeroFeaturedCardSkeleton(shimmerBrush = shimmerBrush)
                                }
                            }

                            // 2. Filter Buttons Row for Default Sources (Latest Updates, Popular, News — NO Adult)
                            item(key = "default_filters", span = { GridItemSpan(maxLineSpan) }, contentType = "filters") {
                                DiscoverFilterButtonRow(
                                    selectedSection = defaultSection,
                                    isAdultSelected = false,
                                    showAdultOption = false,
                                    onSelectSection = { section ->
                                        if (defaultSection != section) {
                                            defaultSection = section
                                            defaultPage = 1
                                            scope.launch { gridState.animateScrollToItem(0) }
                                        }
                                    },
                                    onToggleAdult = {},
                                    onNewsClick = {
                                        navigator.push(eu.kanade.presentation.news.NewsScreen())
                                    },
                                )
                            }

                            // 3. Default Section Title Header (e.g. "Latest Updates" or "Popular Titles")
                            item(key = "default_header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                                DiscoverSectionHeader(
                                    selectedSection = defaultSection,
                                    isAdultSelected = false,
                                )
                            }

                            // 4. Built-in Manga Cards or Empty State or Skeleton
                            if (displayedBuiltinManga.isEmpty()) {
                                val isLoadingTarget = builtinSources.any { source ->
                                    val cache = if (defaultSection == DiscoverSection.LATEST) viewModel.latestCache else viewModel.popularCache
                                    !cache.containsKey(source.id)
                                }
                                if (isLoadingTarget) {
                                    item(key = "builtin_skeleton", span = { GridItemSpan(maxLineSpan) }, contentType = "skeleton") {
                                        FeedGridSkeleton(shimmerBrush = shimmerBrush)
                                    }
                                } else {
                                    item(key = "builtin_empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 40.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "No titles found.",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(
                                    items = displayedBuiltinManga,
                                    key = { "builtin_${it.source}_${it.url}" },
                                    contentType = { "manga_card" },
                                ) { manga ->
                                    DiscoverMangaCard(
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

                            // 5. Built-in Pagination Controls (< Prev  Page X of Y  Next >)
                            if (displayedBuiltinManga.isNotEmpty()) {
                                item(key = "builtin_pagination", span = { GridItemSpan(maxLineSpan) }, contentType = "pagination") {
                                    DiscoverPaginationControls(
                                        currentPage = safeBuiltinPage,
                                        totalPages = totalBuiltinPages,
                                        onPrev = {
                                            if (safeBuiltinPage > 1) {
                                                defaultPage = safeBuiltinPage - 1
                                                scope.launch { gridState.animateScrollToItem(0) }
                                            }
                                        },
                                        onNext = {
                                            if (safeBuiltinPage < totalBuiltinPages) {
                                                defaultPage = safeBuiltinPage + 1
                                                scope.launch { gridState.animateScrollToItem(0) }
                                            } else {
                                                viewModel.loadSourcesForPage(
                                                    sources = builtinSources,
                                                    page = nextBuiltinSourcePage,
                                                    isLatest = (defaultSection == DiscoverSection.LATEST),
                                                )
                                                defaultPage = safeBuiltinPage + 1
                                                scope.launch { gridState.animateScrollToItem(0) }
                                            }
                                        },
                                    )
                                }
                            }

                            // 6. Extension Section (Separate Section Below — only when extensions installed)
                            if (hasAnyExtensionSources) {
                                item(key = "ext_spacer_top", span = { GridItemSpan(maxLineSpan) }, contentType = "spacer") {
                                    Spacer(modifier = Modifier.height(20.dp))
                                }

                                item(key = "ext_divider", span = { GridItemSpan(maxLineSpan) }, contentType = "divider") {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                        thickness = 1.dp,
                                    )
                                }

                                item(key = "ext_spacer_mid", span = { GridItemSpan(maxLineSpan) }, contentType = "spacer") {
                                    Spacer(modifier = Modifier.height(14.dp))
                                }

                                // Extension Filter Buttons Row (Latest Updates, Popular, Adult)
                                item(key = "ext_filters", span = { GridItemSpan(maxLineSpan) }, contentType = "filters") {
                                    DiscoverFilterButtonRow(
                                        selectedSection = extensionSection,
                                        isAdultSelected = extensionIsAdult,
                                        showAdultOption = hasExtensionAdultSources,
                                        onSelectSection = { section ->
                                            if (extensionSection != section) {
                                                extensionSection = section
                                                extensionIsAdult = false
                                                extensionPage = 1
                                            }
                                        },
                                        onToggleAdult = {
                                            extensionIsAdult = !extensionIsAdult
                                            extensionPage = 1
                                        },
                                    )
                                }

                                // Extension Section Header (e.g. "Latest From Extensions", "18+ From Extensions")
                                item(key = "ext_header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                                    ExtensionSectionHeader(
                                        selectedSection = extensionSection,
                                        isAdultSelected = extensionIsAdult,
                                    )
                                }

                                if (displayedExtensionManga.isEmpty()) {
                                    val isLoadingExt = activeExtensionSources.any { source ->
                                        val cache = if (extensionSection == DiscoverSection.LATEST) viewModel.latestCache else viewModel.popularCache
                                        !cache.containsKey(source.id)
                                    }
                                    if (isLoadingExt) {
                                        item(key = "ext_skeleton", span = { GridItemSpan(maxLineSpan) }, contentType = "skeleton") {
                                            FeedGridSkeleton(shimmerBrush = shimmerBrush)
                                        }
                                    } else {
                                        item(key = "ext_empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 30.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = if (extensionIsAdult) "No 18+ titles from extensions." else "No titles from extensions.",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    items(
                                        items = displayedExtensionManga,
                                        key = { "ext_${it.source}_${it.url}" },
                                        contentType = { "manga_card" },
                                    ) { manga ->
                                        DiscoverMangaCard(
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

                                // Extension Pagination Controls (< Prev  Page X of Y  Next >)
                                if (displayedExtensionManga.isNotEmpty()) {
                                    item(key = "ext_pagination", span = { GridItemSpan(maxLineSpan) }, contentType = "pagination") {
                                        DiscoverPaginationControls(
                                            currentPage = safeExtensionPage,
                                            totalPages = totalExtensionPages,
                                            onPrev = {
                                                if (safeExtensionPage > 1) {
                                                    extensionPage = safeExtensionPage - 1
                                                }
                                            },
                                            onNext = {
                                                if (safeExtensionPage < totalExtensionPages) {
                                                    extensionPage = safeExtensionPage + 1
                                                } else {
                                                    viewModel.loadSourcesForPage(
                                                        sources = activeExtensionSources,
                                                        page = nextExtensionSourcePage,
                                                        isLatest = (extensionSection == DiscoverSection.LATEST),
                                                    )
                                                    extensionPage = safeExtensionPage + 1
                                                }
                                            },
                                        )
                                    }
                                }
                            }

                            // 7. Bottom clearance spacer
                            item(key = "bottom_clearance", span = { GridItemSpan(maxLineSpan) }, contentType = "spacer") {
                                Spacer(modifier = Modifier.height(96.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverFilterButtonRow(
    selectedSection: DiscoverSection,
    isAdultSelected: Boolean,
    showAdultOption: Boolean,
    onSelectSection: (DiscoverSection) -> Unit,
    onToggleAdult: () -> Unit,
    modifier: Modifier = Modifier,
    onNewsClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. Latest Updates Button
        FilterPillButton(
            selected = selectedSection == DiscoverSection.LATEST,
            text = "Latest Updates",
            icon = Icons.Outlined.Schedule,
            onClick = { onSelectSection(DiscoverSection.LATEST) },
        )

        // 2. Popular Button
        FilterPillButton(
            selected = selectedSection == DiscoverSection.POPULAR,
            text = "Popular",
            icon = Icons.Outlined.Star,
            onClick = { onSelectSection(DiscoverSection.POPULAR) },
        )

        // 3. News Image Banner Button (on default source row right next to Popular)
        if (onNewsClick != null) {
            NewsImageBannerButton(
                onClick = onNewsClick,
            )
        }

        // 4. Adult Button (only visible in extension section if user has adult extensions)
        if (showAdultOption) {
            AdultFilterButton(
                isActive = isAdultSelected,
                onClick = onToggleAdult,
            )
        }
    }
}

@Composable
private fun NewsImageBannerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonShape = RoundedCornerShape(8.dp)
    Surface(
        modifier = modifier
            .height(36.dp)
            .clip(buttonShape)
            .clickable(onClick = onClick),
        shape = buttonShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Image(
            painter = painterResource(R.drawable.btn_news_banner),
            contentDescription = "News",
            modifier = Modifier
                .height(36.dp)
                .aspectRatio(1024f / 571f),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun FilterPillButton(
    selected: Boolean,
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonShape = RoundedCornerShape(8.dp)
    Surface(
        modifier = modifier
            .clip(buttonShape)
            .clickable(onClick = onClick),
        shape = buttonShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (selected) {
            null
        } else {
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun AdultFilterButton(
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonShape = RoundedCornerShape(8.dp)
    val activeGreen = Color(0xFF388E3C) // Material Green 700
    Surface(
        modifier = modifier
            .clip(buttonShape)
            .clickable(onClick = onClick),
        shape = buttonShape,
        color = if (isActive) {
            activeGreen
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (isActive) {
            Color.White
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (isActive) {
            null
        } else {
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Adult",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiscoverSectionHeader(
    selectedSection: DiscoverSection,
    isAdultSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val title = when {
            selectedSection == DiscoverSection.LATEST && isAdultSelected -> "18+ Latest Updates"
            selectedSection == DiscoverSection.LATEST -> "Latest Updates"
            isAdultSelected -> "18+ Popular Titles"
            else -> "Popular Titles"
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ExtensionSectionHeader(
    selectedSection: DiscoverSection,
    isAdultSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val title = when {
            selectedSection == DiscoverSection.LATEST && isAdultSelected -> "18+ From Extensions"
            selectedSection == DiscoverSection.LATEST -> "Latest From Extensions"
            isAdultSelected -> "18+ Extension Titles"
            else -> "Popular From Extensions"
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DiscoverPaginationControls(
    currentPage: Int,
    totalPages: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonShape = RoundedCornerShape(8.dp)
    val buttonBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val prevEnabled = currentPage > 1
    val nextEnabled = currentPage < totalPages || totalPages <= 1

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Prev button
        Surface(
            modifier = Modifier
                .clip(buttonShape)
                .clickable(enabled = prevEnabled, onClick = onPrev),
            shape = buttonShape,
            color = if (prevEnabled) buttonBg else buttonBg.copy(alpha = 0.35f),
            border = BorderStroke(
                1.dp,
                if (prevEnabled) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f) else Color.Transparent,
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    contentDescription = "Previous Page",
                    modifier = Modifier.size(18.dp),
                    tint = if (prevEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                )
                Text(
                    text = "Prev",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = if (prevEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                )
            }
        }

        Spacer(modifier = Modifier.width(18.dp))

        // Page Indicator: Page X of Y
        Text(
            text = "Page $currentPage of $totalPages",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.width(18.dp))

        // Next button
        Surface(
            modifier = Modifier
                .clip(buttonShape)
                .clickable(enabled = nextEnabled, onClick = onNext),
            shape = buttonShape,
            color = if (nextEnabled) buttonBg else buttonBg.copy(alpha = 0.35f),
            border = BorderStroke(
                1.dp,
                if (nextEnabled) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f) else Color.Transparent,
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Next",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = if (nextEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "Next Page",
                    modifier = Modifier.size(18.dp),
                    tint = if (nextEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                )
            }
        }
    }
}


@Composable
private fun AdultEmptyStateCard(
    onBrowseExtensions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = CircleShape,
                color = Color(0xFF388E3C).copy(alpha = 0.15f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "18+",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF388E3C),
                        fontSize = 18.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "No 18+ Extensions Installed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Install adult extensions in the Browse tab to view 18+ titles here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = onBrowseExtensions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF388E3C),
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Browse Extensions", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun EmptyDiscoverScreen(isNovel: Boolean, onBrowseExtensions: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No sources found. Please install an extension.")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onBrowseExtensions) {
            Text("Browse Extensions")
        }
    }
}
