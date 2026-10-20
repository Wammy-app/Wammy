package eu.kanade.tachiyomi.ui.discover.components

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import eu.kanade.presentation.library.components.RatingBadge
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.model.effectiveRating
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Ensures the bitmap is in software memory (ARGB_8888) so Palette can read pixels without throwing
 * IllegalStateException on Hardware bitmaps.
 */
private fun Bitmap.toSoftwareBitmap(): Bitmap {
    return if (config == Bitmap.Config.HARDWARE) {
        copy(Bitmap.Config.ARGB_8888, false) ?: this
    } else {
        this
    }
}

/**
 * Extracts the true dominant, vibrant color that represents the manga cover.
 * Filters out pure greys, blacks, and whites, and weights candidates by population * saturation^2
 * so prominent vivid colors (e.g. orange sunsets, bright yellow backgrounds) are chosen.
 */
private fun extractMangaCoverColor(palette: Palette?): Color? {
    if (palette == null) return null

    val candidates = palette.swatches.filter { swatch ->
        val hsl = swatch.hsl
        val sat = hsl[1]
        val lum = hsl[2]
        sat >= 0.20f && lum in 0.15f..0.85f
    }

    val bestSwatch = candidates.maxByOrNull { swatch ->
        val sat = swatch.hsl[1]
        swatch.population.toFloat() * (sat * sat)
    }

    val colorInt = bestSwatch?.rgb
        ?: palette.getBestColor()
        ?: palette.vibrantSwatch?.rgb
        ?: palette.lightVibrantSwatch?.rgb
        ?: palette.dominantSwatch?.rgb

    return colorInt?.let { Color(it) }
}

/**
 * Compact Hero Featured Card matching the reference design:
 * - Color INSIDE the card is the color of the current manga banner (changes dynamically with each cover)
 * - Top border is visible with the banner color, bottom has NO border, and ZERO outside glow
 * - Clean rounded poster cover without artificial white border
 * - Active indicator dot uses the current manga's banner color
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
    val context = LocalContext.current
    val density = LocalDensity.current

    // Dominant color cache extracted dynamically from each manga's cover art
    val dominantColorMap = remember { mutableStateMapOf<String, Color>() }
    val currentManga = items.getOrNull(pagerState.currentPage)
    val targetDominantColor = currentManga?.let {
        dominantColorMap[it.url]
            ?: MangaCover.vibrantCoverColorMap[it.id]?.let { c -> Color(c) }
    } ?: MaterialTheme.colorScheme.primary

    val activeColor by animateColorAsState(
        targetValue = targetDominantColor,
        animationSpec = tween(500),
        label = "hero_banner_color",
    )

    // Pre-extract palette colors for all carousel items in background
    LaunchedEffect(items) {
        items.forEach { manga ->
            if (!dominantColorMap.containsKey(manga.url)) {
                val precomputed = MangaCover.vibrantCoverColorMap[manga.id]
                if (precomputed != null) {
                    dominantColorMap[manga.url] = Color(precomputed)
                } else {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val req = ImageRequest.Builder(context)
                                .data(manga.asMangaCover())
                                .allowHardware(false)
                                .build()
                            val result = context.imageLoader.execute(req)
                            val drawable = result.image?.asDrawable(context.resources)
                            val rawBitmap = drawable?.getBitmapOrNull()
                                ?: (drawable as? BitmapDrawable)?.bitmap
                                ?: drawable?.toBitmap()
                            val softwareBitmap = rawBitmap?.toSoftwareBitmap()
                            if (softwareBitmap != null) {
                                val palette = Palette.from(softwareBitmap)
                                    .maximumColorCount(24)
                                    .generate()
                                val color = extractMangaCoverColor(palette)
                                if (color != null) {
                                    dominantColorMap[manga.url] = color
                                    MangaCover.vibrantCoverColorMap[manga.id] = color.toArgb()
                                }
                            }
                        } catch (e: Throwable) {
                            logcat(LogPriority.WARN, e) { "Failed to extract cover color for ${manga.title}" }
                        }
                    }
                }
            }
        }
    }

    // Auto-scroll logic (rotates every 5 seconds)
    LaunchedEffect(pagerState, items.size) {
        while (true) {
            delay(5000)
            if (items.size > 1 && !pagerState.isScrollInProgress) {
                try {
                    val nextPage = (pagerState.currentPage + 1) % items.size
                    pagerState.animateScrollToPage(nextPage, animationSpec = tween(800))
                } catch (_: Exception) {
                    // Ignored on interruption
                }
            }
        }
    }

    // Auto-snap safeguard: if scrolling was interrupted midway, snap immediately to the settled page
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && kotlin.math.abs(pagerState.currentPageOffsetFraction) > 0.001f) {
            try {
                pagerState.animateScrollToPage(pagerState.settledPage)
            } catch (_: Exception) {
                pagerState.scrollToPage(pagerState.settledPage)
            }
        }
    }

    val gradientEndY = with(density) { 180.dp.toPx() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Very subtle ambient wash that seamlessly blends with the dark background without any harshness
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            activeColor.copy(alpha = 0.04f),
                            Color.Transparent,
                        ),
                        startY = 0f,
                        endY = gradientEndY,
                    ),
                ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Card Header: Section Title on Left, Pager Dots + Nav Arrows on Right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // Pager Indicator Dots (active pill uses current manga's banner color)
                        if (items.size > 1) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                repeat(items.size) { index ->
                                    val isSelected = pagerState.currentPage == index
                                    Box(
                                        modifier = Modifier
                                            .size(
                                                width = if (isSelected) 18.dp else 5.dp,
                                                height = 5.dp,
                                            )
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(
                                                if (isSelected) activeColor
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                            ),
                                    )
                                }
                            }
                        }

                        // Previous Chevron (<)
                        IconButton(
                            onClick = {
                                if (items.size > 1) {
                                    val prev = (pagerState.currentPage - 1 + items.size) % items.size
                                    scope.launch { pagerState.animateScrollToPage(prev) }
                                }
                            },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Previous",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        // Next Chevron (>)
                        IconButton(
                            onClick = {
                                if (items.size > 1) {
                                    val next = (pagerState.currentPage + 1) % items.size
                                    scope.launch { pagerState.animateScrollToPage(next) }
                                }
                            },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Next",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                // Pager Body: 2-column layout
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    beyondViewportPageCount = 1,
                ) { page ->
                    val manga = items[page]
                    val coverData = remember(manga.id, manga.thumbnailUrl, manga.coverLastModified) {
                        manga.asMangaCover()
                    }
                    val rating = remember(manga.id, manga.memo, manga.description) {
                        manga.effectiveRating
                    }
                    val rawAuthor = manga.author?.takeIf { it.isNotBlank() } ?: manga.artist?.takeIf { it.isNotBlank() }
                    val authorText = rawAuthor?.let { if (it.startsWith("by ", ignoreCase = true)) it else "by $it" } ?: ""
                    val genres = manga.genre?.take(3) ?: emptyList()

                    val sourceManager = remember { Injekt.get<SourceManager>() }
                    val sourceName = remember(manga.source) {
                        try {
                            sourceManager.getOrStub(manga.source).name
                        } catch (_: Exception) {
                            ""
                        }
                    }

                    val statusText = when (manga.status.toInt()) {
                        SManga.ONGOING -> "Ongoing"
                        SManga.COMPLETED -> "Completed"
                        SManga.LICENSED -> "Licensed"
                        SManga.PUBLISHING_FINISHED -> "Finished"
                        SManga.CANCELLED -> "Cancelled"
                        SManga.ON_HIATUS -> "On Hiatus"
                        else -> null
                    }
                    val statusColor = when (manga.status.toInt()) {
                        SManga.ONGOING -> Color(0xFF4CAF50)
                        SManga.COMPLETED -> Color(0xFF2196F3)
                        SManga.ON_HIATUS -> Color(0xFFFFA000)
                        SManga.CANCELLED -> MaterialTheme.colorScheme.error
                        else -> activeColor
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClick(manga) }
                            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        // Left Column: Clean rounded poster cover
                        Box(
                            modifier = Modifier
                                .width(108.dp)
                                .aspectRatio(3f / 4f)
                                .clip(RoundedCornerShape(10.dp)),
                        ) {
                            AsyncImage(
                                model = coverData,
                                contentDescription = manga.title,
                                onSuccess = { successState ->
                                    if (!dominantColorMap.containsKey(manga.url) && !MangaCover.vibrantCoverColorMap.containsKey(manga.id)) {
                                        val drawable = successState.result.image.asDrawable(context.resources)
                                        val rawBitmap = (drawable as? BitmapDrawable)?.bitmap
                                            ?: drawable.getBitmapOrNull()
                                            ?: drawable.toBitmap()
                                        val softwareBitmap = rawBitmap.toSoftwareBitmap()
                                        Palette.from(softwareBitmap).maximumColorCount(24).generate { palette ->
                                            val color = extractMangaCoverColor(palette)
                                            if (color != null) {
                                                dominantColorMap[manga.url] = color
                                                MangaCover.vibrantCoverColorMap[manga.id] = color.toArgb()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )

                            if (rating != null && rating > 0.0) {
                                RatingBadge(
                                    rating = rating,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(5.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Right Column: Title, Metadata, Genre Pills, Synopsis
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 1.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // Title (max 2 lines)
                            Text(
                                text = manga.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    lineHeight = 19.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )

                            // Metadata: Status Badge, Source Name, Author
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (statusText != null) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = statusColor.copy(alpha = 0.15f),
                                    ) {
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 10.sp,
                                            ),
                                            color = statusColor,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                                        )
                                    }
                                }

                                if (sourceName.isNotBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Public,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(11.dp),
                                        )
                                        Text(
                                            text = sourceName,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }

                                if (authorText.isNotBlank()) {
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                    Text(
                                        text = authorText,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                }
                            }

                            // Genre Pills
                            if (genres.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    genres.forEach { genre ->
                                        Surface(
                                            shape = RoundedCornerShape(5.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        ) {
                                            Text(
                                                text = genre,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Medium,
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }

                            // Synopsis / Description (max 3 lines)
                            val description = manga.description?.trim().orEmpty()
                            if (description.isNotBlank()) {
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
