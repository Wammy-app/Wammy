package eu.kanade.tachiyomi.ui.discover.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Creates a smooth, high-fps diagonal shimmer wave that sweeps across
 * skeleton placeholders, matching industry standards (Google / Spotify).
 */
@Composable
fun rememberShimmerBrush(): Brush {
    val baseColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val highlightColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)

    val shimmerColors = listOf(
        baseColor,
        highlightColor,
        baseColor,
    )

    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val translateAnimation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1800f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_translate",
    )

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnimation.value - 600f, translateAnimation.value - 600f),
        end = Offset(translateAnimation.value, translateAnimation.value),
    )
}

/**
 * Skeleton placeholder for the Hero Featured Card at the top of Discover.
 * Exact 215dp height, 12dp rounded corner matching HeroFeaturedCard.
 */
@Composable
fun HeroFeaturedCardSkeleton(
    shimmerBrush: Brush,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(215.dp)
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(shimmerBrush),
    ) {
        // Bottom gradient overlay for realistic depth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.5f to Color.Black.copy(alpha = 0.25f),
                        1.0f to Color.Black.copy(alpha = 0.65f),
                    )
                ),
        )

        // Placeholder content at the bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            // Title placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.35f)),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Author / subtitle placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.25f)),
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Genre tags placeholder
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .width(52.dp)
                            .height(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.25f)),
                    )
                }
            }
        }

        // Pager indicator dots placeholder at bottom center
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 16.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.7f)),
            )
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(width = 6.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.3f)),
                )
            }
        }
    }
}

/**
 * Skeleton placeholder for the Recently Uploaded section.
 * Matches RecentlyUploadedCard header and 2 compact items.
 */
@Composable
fun RecentlyUploadedSkeleton(
    shimmerBrush: Brush,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        // Section Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(150.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBrush),
            )

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(shimmerBrush),
            )
        }

        // 2 Compact Cards
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Cover thumbnail
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(shimmerBrush),
                    )

                    // Text placeholders
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.65f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.38f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush),
                        )
                    }

                    // Chapter / Time chip
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerBrush),
                    )
                }
            }
        }
    }
}

/**
 * Skeleton placeholder for Trending / Latest sections.
 * 2 stacked rows with 3 visible columns matching HomeMediaSection dimensions exactly.
 */
@Composable
fun HomeMediaSectionSkeleton(
    shimmerBrush: Brush,
    title: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        // Section Header: Title + Arrow button placeholder
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush),
                )
            }

            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(shimmerBrush),
            )
        }

        // 2 Stacked Rows with 3 visible columns
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            val itemSpacing = 12.dp
            val cardWidth = (maxWidth - itemSpacing * 2) / 3

            Row(
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                modifier = Modifier.fillMaxWidth(),
            ) {
                repeat(3) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        repeat(2) {
                            Column(
                                modifier = Modifier.width(cardWidth),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(shimmerBrush),
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.75f)
                                        .height(12.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(shimmerBrush),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Complete Discover screen skeleton loading state.
 * Fills the full screen with synchronized, realistic placeholders
 * for Hero, Recently Uploaded, Trending, and Latest sections.
 */
@Composable
fun DiscoverSkeletonScreen(
    modifier: Modifier = Modifier,
    shimmerBrush: Brush = rememberShimmerBrush(),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        HeroFeaturedCardSkeleton(shimmerBrush = shimmerBrush)
        Spacer(modifier = Modifier.height(14.dp))
        RecentlyUploadedSkeleton(shimmerBrush = shimmerBrush)
        Spacer(modifier = Modifier.height(10.dp))
        HomeMediaSectionSkeleton(title = "Trending", shimmerBrush = shimmerBrush)
        Spacer(modifier = Modifier.height(6.dp))
        HomeMediaSectionSkeleton(title = "Latest", shimmerBrush = shimmerBrush)
        Spacer(modifier = Modifier.height(96.dp))
    }
}

/**
 * Grid skeleton placeholder for feed screens (e.g. DiscoverFeedScreen).
 */
@Composable
fun FeedGridSkeleton(
    modifier: Modifier = Modifier,
    shimmerBrush: Brush = rememberShimmerBrush(),
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(4) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                repeat(3) {
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(shimmerBrush),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush),
                        )
                    }
                }
            }
        }
    }
}
