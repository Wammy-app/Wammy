package eu.kanade.tachiyomi.ui.discover.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import kotlin.math.absoluteValue

/**
 * Hero Featured Card with center-focused cover carousel:
 * - Center card is large, prominent, and in focus
 * - Left and right cards peek in scaled down and dimmed
 * - Centered pager indicator dots below
 * - Full-width "Read" button at the bottom
 */
@Composable
fun HeroFeaturedCard(
    items: List<Manga>,
    onClick: (Manga) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Popular New Titles",
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { items.size })
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()

    // Auto-scroll logic (rotates every 5 seconds)
    LaunchedEffect(pagerState, items.size) {
        while (true) {
            delay(5000)
            if (items.size > 1 && !pagerState.isScrollInProgress) {
                try {
                    val nextPage = (pagerState.currentPage + 1) % items.size
                    pagerState.animateScrollToPage(nextPage, animationSpec = tween(700))
                } catch (_: Exception) {
                    // Ignored on interruption
                }
            }
        }
    }

    // Auto-snap safeguard: if scrolling was interrupted midway, snap to settled page
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && pagerState.currentPageOffsetFraction.absoluteValue > 0.001f) {
            try {
                pagerState.animateScrollToPage(pagerState.settledPage)
            } catch (_: Exception) {
                pagerState.scrollToPage(pagerState.settledPage)
            }
        }
    }

    val currentManga = items.getOrNull(pagerState.currentPage)

    // Button theme styling matching the reference: light contrasting button in dark mode
    val buttonBg = if (isDark) Color(0xFFE8E8EE) else MaterialTheme.colorScheme.primary
    val buttonContentColor = if (isDark) Color(0xFF121218) else MaterialTheme.colorScheme.onPrimary

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1. Cover Carousel (center card focused, side cards peeking)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
            ) {
                val screenWidth = maxWidth
                val centerWidth = (screenWidth * 0.58f).coerceIn(190.dp, 230.dp)
                val sidePadding = ((screenWidth - centerWidth) / 2).coerceAtLeast(32.dp)

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = sidePadding),
                    pageSpacing = 14.dp,
                    beyondViewportPageCount = 2,
                ) { page ->
                    val manga = items[page]
                    val coverData = remember(manga.id, manga.thumbnailUrl, manga.coverLastModified) {
                        manga.asMangaCover()
                    }

                    val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                    val scale = 1f - (0.16f * pageOffset.coerceIn(0f, 1f))
                    val alpha = 1f - (0.50f * pageOffset.coerceIn(0f, 1f))

                    Box(
                        modifier = Modifier
                            .width(centerWidth)
                            .aspectRatio(1f)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                if (page == pagerState.currentPage) {
                                    onClick(manga)
                                } else {
                                    scope.launch { pagerState.animateScrollToPage(page) }
                                }
                            },
                    ) {
                        AsyncImage(
                            model = coverData,
                            contentDescription = manga.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }

            // 2. Pager Indicator Dots
            if (items.size > 1) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(items.size) { index ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(
                                    width = if (isSelected) 22.dp else 6.dp,
                                    height = 4.dp,
                                )
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                                )
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                        )
                    }
                }
            }

            // 3. Full-width Read Button
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(buttonBg)
                    .clickable {
                        currentManga?.let { onClick(it) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        tint = buttonContentColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Read",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.5.sp,
                        ),
                        color = buttonContentColor,
                    )
                }
            }
        }
    }
}
