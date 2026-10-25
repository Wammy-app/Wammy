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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import kotlin.math.absoluteValue

/**
 * Hero Featured Card matching the reference visual:
 * - Rounded outer card container with yellow "Best" badge at top-right
 * - Overlapping 3D layered card carousel: center card in front with shadow, side cards tucked underneath
 * - Centered pager indicator dots
 * - Two side-by-side action buttons at the bottom: "Read" and "+ Subscribe"
 */
@Composable
fun HeroFeaturedCard(
    items: List<Manga>,
    onClick: (Manga) -> Unit,
    modifier: Modifier = Modifier,
    onSubscribe: ((Manga) -> Unit)? = null,
    title: String = "Popular New Titles",
) {
    if (items.isEmpty()) return

    val isInfinite = items.size > 1
    val virtualPageCount = if (isInfinite) items.size * 2000 else items.size
    val initialPage = if (isInfinite) (virtualPageCount / 2) - ((virtualPageCount / 2) % items.size) else 0

    val pagerState = key(items.size) {
        rememberPagerState(
            initialPage = initialPage,
            pageCount = { virtualPageCount },
        )
    }

    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()

    // Auto-scroll logic (rotates forward every 5 seconds)
    LaunchedEffect(pagerState, items.size) {
        while (true) {
            delay(5000)
            if (items.size > 1 && !pagerState.isScrollInProgress) {
                try {
                    pagerState.animateScrollToPage(pagerState.currentPage + 1, animationSpec = tween(700))
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

    val currentActualIndex = if (items.isNotEmpty()) {
        (pagerState.currentPage % items.size + items.size) % items.size
    } else {
        0
    }
    val currentManga = items.getOrNull(currentActualIndex)

    val containerBg = if (isDark) Color(0xFF13131C) else MaterialTheme.colorScheme.surfaceContainerLow

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = containerBg,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 1. Top-Right Yellow "Best" Badge
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .zIndex(10f),
                shape = CircleShape,
                color = Color(0xFFFDE047), // Vibrant yellow
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Whatshot,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "Best",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                        ),
                        color = Color.Black,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 2. Overlapping Cover Carousel
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val screenWidth = maxWidth
                    val centerWidth = (screenWidth * 0.54f).coerceIn(180.dp, 215.dp)
                    val sidePadding = ((screenWidth - centerWidth) / 2).coerceAtLeast(24.dp)

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = sidePadding),
                        pageSpacing = 0.dp,
                        beyondViewportPageCount = 2,
                    ) { page ->
                        val actualIndex = (page % items.size + items.size) % items.size
                        val manga = items[actualIndex]
                        val coverData = remember(manga.id, manga.thumbnailUrl, manga.coverLastModified) {
                            manga.asMangaCover()
                        }

                        val rawOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                        val absOffset = rawOffset.absoluteValue

                        val scale = 1f - (0.12f * absOffset.coerceIn(0f, 1f))
                        val alpha = 1f - (0.42f * absOffset.coerceIn(0f, 1f))
                        val zIndex = 1f - absOffset.coerceIn(0f, 1f)
                        val shadowElevation = (14f * (1f - absOffset.coerceIn(0f, 1f))).dp

                        Box(
                            modifier = Modifier
                                .zIndex(zIndex)
                                .graphicsLayer {
                                    this.translationX = rawOffset.coerceIn(-1f, 1f) * 44.dp.toPx()
                                    this.scaleX = scale
                                    this.scaleY = scale
                                    this.alpha = alpha
                                }
                                .width(centerWidth)
                                .aspectRatio(3f / 3.8f)
                                .shadow(
                                    elevation = shadowElevation,
                                    shape = RoundedCornerShape(16.dp),
                                    clip = false,
                                )
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

                // 3. Pager Indicator Dots
                if (items.size > 1) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(items.size) { index ->
                            val isSelected = currentActualIndex == index
                            Box(
                                modifier = Modifier
                                    .size(
                                        width = if (isSelected) 22.dp else 6.dp,
                                        height = 4.dp,
                                    )
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (isSelected) Color.White.copy(alpha = 0.95f)
                                        else Color.White.copy(alpha = 0.28f),
                                    )
                                    .clickable {
                                        val currentVirtual = pagerState.currentPage
                                        val diff = index - currentActualIndex
                                        scope.launch { pagerState.animateScrollToPage(currentVirtual + diff) }
                                    },
                            )
                        }
                    }
                }

                // 4. Two Bottom Action Buttons (Read and + Subscribe)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left Button: Read
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0xFFE2E4EB) else MaterialTheme.colorScheme.primary)
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
                                tint = if (isDark) Color(0xFF121218) else MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(19.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Read",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                ),
                                color = if (isDark) Color(0xFF121218) else MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }

                    // Right Button: + Subscribe
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0xFF1B1D2A) else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable {
                                currentManga?.let { onSubscribe?.invoke(it) ?: onClick(it) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "+ Subscribe",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                ),
                                color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
