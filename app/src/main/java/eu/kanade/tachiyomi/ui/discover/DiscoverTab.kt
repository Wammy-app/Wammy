package eu.kanade.tachiyomi.ui.discover

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import eu.kanade.tachiyomi.data.auth.AuthManager
import uy.kohesive.injekt.Injekt
import androidx.compose.material.icons.outlined.Update
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.api.get
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.absoluteValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import coil3.compose.AsyncImage
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.presentation.core.i18n.stringResource
import eu.kanade.tachiyomi.ui.webview.TrackerWebViewLoginActivity
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.history.HistoryFilter
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import tachiyomi.domain.source.interactor.GetRemoteManga
import eu.kanade.tachiyomi.ui.discover.components.ContinueReadingCarouselWidget
import eu.kanade.tachiyomi.ui.discover.components.ContinueReadingDiscWidget
import eu.kanade.tachiyomi.ui.discover.components.DiscoverSkeletonScreen
import eu.kanade.tachiyomi.ui.discover.components.HeroFeaturedCard
import eu.kanade.tachiyomi.ui.discover.components.HeroFeaturedCardSkeleton
import eu.kanade.tachiyomi.ui.discover.components.HomeMediaSection
import eu.kanade.tachiyomi.ui.discover.components.HomeMediaSectionSkeleton
import eu.kanade.tachiyomi.ui.discover.components.HomeTopBar
import eu.kanade.tachiyomi.ui.discover.components.RecentlyUploadedCard
import eu.kanade.tachiyomi.ui.discover.components.rememberShimmerBrush
import eu.kanade.tachiyomi.ui.reader.ReaderActivity

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

        val authManager: AuthManager = Injekt.get()
        val user by authManager.currentUser.collectAsState()

        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
        val updatesCount by libraryPreferences.newUpdatesCount.collectAsState()

        val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

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
            androidx.compose.animation.Crossfade(targetState = state, animationSpec = tween(400), modifier = Modifier.padding(contentPadding).fillMaxSize(), label = "discover") { animatedState ->
                if (animatedState.isLoading) {
                    DiscoverSkeletonScreen()
                } else {
                    val recentlyRead by viewModel.recentlyRead.collectAsState()

                    val trendingAggregated = remember(viewModel.popularCache.toMap(), animatedState.regularSources) {
                        val result = mutableListOf<Manga>()
                        val seenTitles = mutableSetOf<String>()
                        val lists = animatedState.regularSources.mapNotNull { viewModel.popularCache[it.id] }
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

                    val latestAggregated = remember(viewModel.latestCache.toMap(), animatedState.regularSources) {
                        val result = mutableListOf<Manga>()
                        val seenTitles = mutableSetOf<String>()
                        val lists = animatedState.regularSources.mapNotNull { viewModel.latestCache[it.id] }
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

                    val adultTrendingAggregated = remember(viewModel.popularCache.toMap(), animatedState.adultSources) {
                        val result = mutableListOf<Manga>()
                        val seenTitles = mutableSetOf<String>()
                        val lists = animatedState.adultSources.mapNotNull { viewModel.popularCache[it.id] }
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

                    val adultLatestAggregated = remember(viewModel.latestCache.toMap(), animatedState.adultSources) {
                        val result = mutableListOf<Manga>()
                        val seenTitles = mutableSetOf<String>()
                        val lists = animatedState.adultSources.mapNotNull { viewModel.latestCache[it.id] }
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

                    val allCachesLoaded = animatedState.sources.all { viewModel.popularCache.containsKey(it.id) }
                    val hasNoMangaAtAll = allCachesLoaded && viewModel.popularCache.values.all { it.isEmpty() }
                    val hasAnyContent = trendingAggregated.isNotEmpty() || latestAggregated.isNotEmpty() ||
                        (animatedState.hasAdultSources && (adultTrendingAggregated.isNotEmpty() || adultLatestAggregated.isNotEmpty()))

                    if (animatedState.sources.isEmpty() || hasNoMangaAtAll) {
                        EmptyDiscoverScreen(
                            isNovel = animatedState.isNovel,
                            onBrowseExtensions = { tabNavigator.current = BrowseTab }
                        )
                    } else if (!hasAnyContent) {
                        DiscoverSkeletonScreen()
                    } else {
                        val shimmerBrush = rememberShimmerBrush()
                        LazyColumn(
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // 1. Hero Featured Card
                            if (featuredManga.isNotEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    HeroFeaturedCard(
                                        items = featuredManga,
                                        onClick = { manga ->
                                            scope.launch {
                                                val localManga = viewModel.getNetworkToLocalManga(manga)
                                                navigator.push(MangaScreen(localManga.id))
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                }
                            } else if (animatedState.regularSources.isNotEmpty() && !animatedState.regularSources.all { viewModel.popularCache.containsKey(it.id) }) {
                                item {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    HeroFeaturedCardSkeleton(shimmerBrush = shimmerBrush)
                                    Spacer(modifier = Modifier.height(10.dp))
                                }
                            }

                            // 2. Continue Reading (Vinyl Disc Carousel)
                            if (recentlyRead.isNotEmpty()) {
                                item {
                                    val continueReadingItems = remember(recentlyRead) { recentlyRead.take(5) }
                                    val context = androidx.compose.ui.platform.LocalContext.current
                                    ContinueReadingCarouselWidget(
                                        items = continueReadingItems,
                                        onClick = { history ->
                                            scope.launch {
                                                val chapterId = if (history.chapterRead) {
                                                    viewModel.getResumeChapter(history.mangaId, history.chapterId)?.id ?: history.chapterId
                                                } else {
                                                    history.chapterId
                                                }
                                                if (chapterId > 0) {
                                                    val intent = ReaderActivity.newIntent(context, history.mangaId, chapterId)
                                                    context.startActivity(intent)
                                                } else {
                                                    navigator.push(MangaScreen(history.mangaId))
                                                }
                                            }
                                        },
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                }
                            }

                            // 3. Recently Uploaded Card (with expand chevron & timestamps)
                            if (latestAggregated.isNotEmpty()) {
                                item {
                                    RecentlyUploadedCard(
                                        items = latestAggregated,
                                        onClick = { manga ->
                                            scope.launch {
                                                val localManga = viewModel.getNetworkToLocalManga(manga)
                                                navigator.push(MangaScreen(localManga.id))
                                            }
                                        },
                                        onFilterClick = {
                                            navigator.push(DiscoverFeedScreen(isLatest = true, isAdult = false))
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                }
                            }

                            // 4. Trending Section (2 stacked LazyRows, merged across regular sources)
                            if (animatedState.regularSources.isNotEmpty()) {
                                val isRegularTrendingLoading = !animatedState.regularSources.all { viewModel.popularCache.containsKey(it.id) }
                                val regularSourcesWithLatest = animatedState.regularSources.filter { it.supportsLatest }
                                val isRegularLatestLoading = regularSourcesWithLatest.isNotEmpty() &&
                                    !regularSourcesWithLatest.all { viewModel.latestCache.containsKey(it.id) }

                                if (trendingAggregated.isNotEmpty()) {
                                    item {
                                        HomeMediaSection(
                                            title = "Trending",
                                            items = trendingAggregated,
                                            onSeeAll = {
                                                navigator.push(DiscoverFeedScreen(isLatest = false, isAdult = false))
                                            },
                                            onClick = { manga ->
                                                scope.launch {
                                                    val localManga = viewModel.getNetworkToLocalManga(manga)
                                                    navigator.push(MangaScreen(localManga.id))
                                                }
                                            }
                                        )
                                    }
                                } else if (isRegularTrendingLoading) {
                                    item {
                                        HomeMediaSectionSkeleton(
                                            title = "Trending",
                                            shimmerBrush = shimmerBrush,
                                        )
                                    }
                                }

                                // 5. Latest Section (2 stacked LazyRows, merged across regular sources)
                                if (latestAggregated.isNotEmpty()) {
                                    item {
                                        HomeMediaSection(
                                            title = "Latest",
                                            items = latestAggregated,
                                            onSeeAll = {
                                                navigator.push(DiscoverFeedScreen(isLatest = true, isAdult = false))
                                            },
                                            onClick = { manga ->
                                                scope.launch {
                                                    val localManga = viewModel.getNetworkToLocalManga(manga)
                                                    navigator.push(MangaScreen(localManga.id))
                                                }
                                            }
                                        )
                                    }
                                } else if (isRegularLatestLoading) {
                                    item {
                                        HomeMediaSectionSkeleton(
                                            title = "Latest",
                                            shimmerBrush = shimmerBrush,
                                        )
                                    }
                                }
                            }

                            // 6. Adult Content Sections (Exclusively shown when sexual/adult extensions are installed)
                            if (animatedState.hasAdultSources) {
                                val isAdultTrendingLoading = animatedState.adultSources.isNotEmpty() &&
                                    !animatedState.adultSources.all { viewModel.popularCache.containsKey(it.id) }
                                val adultSourcesWithLatest = animatedState.adultSources.filter { it.supportsLatest }
                                val isAdultLatestLoading = adultSourcesWithLatest.isNotEmpty() &&
                                    !adultSourcesWithLatest.all { viewModel.latestCache.containsKey(it.id) }

                                // Adult Trending Section
                                if (adultTrendingAggregated.isNotEmpty()) {
                                    item {
                                        HomeMediaSection(
                                            title = "Trending",
                                            subtitle = "Adult Only",
                                            items = adultTrendingAggregated,
                                            onSeeAll = {
                                                navigator.push(DiscoverFeedScreen(isLatest = false, isAdult = true))
                                            },
                                            onClick = { manga ->
                                                scope.launch {
                                                    val localManga = viewModel.getNetworkToLocalManga(manga)
                                                    navigator.push(MangaScreen(localManga.id))
                                                }
                                            }
                                        )
                                    }
                                } else if (isAdultTrendingLoading) {
                                    item {
                                        HomeMediaSectionSkeleton(
                                            title = "Trending",
                                            subtitle = "Adult Only",
                                            shimmerBrush = shimmerBrush,
                                        )
                                    }
                                }

                                // Adult Latest Section
                                if (adultLatestAggregated.isNotEmpty()) {
                                    item {
                                        HomeMediaSection(
                                            title = "Latest",
                                            subtitle = "Adult Only",
                                            items = adultLatestAggregated,
                                            onSeeAll = {
                                                navigator.push(DiscoverFeedScreen(isLatest = true, isAdult = true))
                                            },
                                            onClick = { manga ->
                                                scope.launch {
                                                    val localManga = viewModel.getNetworkToLocalManga(manga)
                                                    navigator.push(MangaScreen(localManga.id))
                                                }
                                            }
                                        )
                                    }
                                } else if (isAdultLatestLoading) {
                                    item {
                                        HomeMediaSectionSkeleton(
                                            title = "Latest",
                                            subtitle = "Adult Only",
                                            shimmerBrush = shimmerBrush,
                                        )
                                    }
                                }
                            }

                            // Bottom Spacer for clean clearance above dock
                            item {
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
fun CustomTab(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
        if (isSelected) {
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .height(3.dp)
                    .width(40.dp)
                    .background(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(1.5.dp))
            )
        } else {
            Spacer(modifier = Modifier.height(7.dp))
        }
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSeeAll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = "See All",
            tint = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
fun RecentlyUpdatedItem(manga: Manga, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = manga.asMangaCover(),
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = manga.title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Updated recently",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
        Box(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = "New",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun HeroCarousel(
    featuredManga: List<Manga>,
    onClick: (Manga) -> Unit
) {
    if (featuredManga.isEmpty()) {
        val infiniteTransition = rememberInfiniteTransition()
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        )
        return
    }

    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { featuredManga.size })

    // Auto-scroll logic
    LaunchedEffect(pagerState) {
        while (true) {
            kotlinx.coroutines.delay(5000)
            if (featuredManga.size > 1) {
                val nextPage = (pagerState.currentPage + 1) % featuredManga.size
                pagerState.animateScrollToPage(
                    page = nextPage,
                    animationSpec = tween(1000)
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val pageOffset = (
                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            ).absoluteValue

            val manga = featuredManga[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 1f - pageOffset.coerceIn(0f, 1f)
                    }
                    .clickable { onClick(manga) }
            ) {
                AsyncImage(
                    model = manga.asMangaCover(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // Gradients
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(MaterialTheme.colorScheme.background, Color.Transparent)
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                                startY = 100f
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent),
                                endX = 600f
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                ) {
                    Text(
                        text = manga.title,
                        color = Color.White,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    val subtitle = manga.author ?: "Trending Now"
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    Button(
                        onClick = { onClick(manga) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Start Reading",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Start Reading",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Pager Indicators
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(featuredManga.size) { iteration ->
                val color = if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f)
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(color)
                )
            }
        }
    }
}



@Composable
fun EmptyDiscoverScreen(isNovel: Boolean, onBrowseExtensions: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No sources found. Please install an extension.")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onBrowseExtensions) {
            Text("Browse Extensions")
        }
    }
}


























