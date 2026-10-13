package eu.kanade.tachiyomi.ui.discover.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import tachiyomi.domain.history.model.HistoryWithRelations
import kotlin.math.absoluteValue

@Composable
fun ContinueReadingCarouselWidget(
    items: List<HistoryWithRelations>,
    onClick: (HistoryWithRelations) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        // Section Header
        Text(
            text = "CONTINUE READING",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        )

        val pagerState = rememberPagerState(
            initialPage = 0,
            pageCount = { items.size },
        )

        HorizontalPager(
            state = pagerState,
            contentPadding = if (items.size > 1) PaddingValues(horizontal = 38.dp) else PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
        ) { page ->
            val history = items[page]
            val pageOffset = (
                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            ).absoluteValue
            val scale = 0.90f + (1f - 0.90f) * (1f - pageOffset.coerceIn(0f, 1f))
            val alpha = 0.50f + (1f - 0.50f) * (1f - pageOffset.coerceIn(0f, 1f))

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .zIndex(1f - pageOffset.coerceIn(0f, 1f)),
                contentAlignment = Alignment.Center,
            ) {
                ContinueReadingCard(
                    history = history,
                    onClick = { onClick(history) },
                )
            }
        }

        // Pager indicator dots (below carousel)
        if (items.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(items.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (isSelected) 7.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                },
                            )
                            .clickable {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(
    history: HistoryWithRelations,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chapterNumStr = if (history.chapterNumber > 0) {
        if (history.chapterNumber % 1.0 == 0.0) {
            history.chapterNumber.toInt().toString()
        } else {
            history.chapterNumber.toString()
        }
    } else {
        "1"
    }

    val titleWithChapter = if (history.chapterNumber > 0) {
        "${history.title} • Ch. $chapterNumStr"
    } else {
        history.title
    }

    val progress = if (history.lastPageRead > 0) {
        (history.lastPageRead.toFloat() / 20f).coerceIn(0.1f, 1f)
    } else if (history.chapterRead) {
        1f
    } else {
        0.5f
    }
    val percentRead = (progress * 100).toInt()
    val cardColor = MaterialTheme.colorScheme.surfaceContainer

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(74.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = cardColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Compact Vinyl Disc Graphic
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                // Cover art inside vinyl
                AsyncImage(
                    model = history.coverData,
                    contentDescription = history.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )

                // Groove ring
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .border(0.8.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                )

                // Center spindle metallic hub
                Box(
                    modifier = Modifier
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFDDDDDD),
                                    Color(0xFF888888),
                                ),
                            ),
                        )
                        .border(0.6.dp, Color.Black.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    // Center hole cutout matching card bg
                    Box(
                        modifier = Modifier
                            .size(5.5.dp)
                            .clip(CircleShape)
                            .background(cardColor),
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Details & progress bar
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "CONTINUE READING",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.6.sp,
                )

                Spacer(modifier = Modifier.height(1.dp))

                Text(
                    text = titleWithChapter,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(1.dp))

                Text(
                    text = "Chapter $chapterNumStr • $percentRead% Read",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(5.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
fun ContinueReadingDiscWidget(
    history: HistoryWithRelations?,
    onClick: (HistoryWithRelations) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (history == null) return
    ContinueReadingCarouselWidget(
        items = listOf(history),
        onClick = onClick,
        modifier = modifier,
    )
}
