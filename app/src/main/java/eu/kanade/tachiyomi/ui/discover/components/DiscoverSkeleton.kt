package eu.kanade.tachiyomi.ui.discover.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Surface
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
 * Matches the center-focused cover carousel layout with dots and Read button.
 */
@Composable
fun HeroFeaturedCardSkeleton(
    shimmerBrush: Brush,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 3 covers placeholder: center 200dp square, left/right peeking blocks
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 46.dp, height = 160.dp)
                        .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                        .background(shimmerBrush),
                )
                Spacer(modifier = Modifier.width(14.dp))
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(shimmerBrush),
                )
                Spacer(modifier = Modifier.width(14.dp))
                Box(
                    modifier = Modifier
                        .size(width = 46.dp, height = 160.dp)
                        .clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
                        .background(shimmerBrush),
                )
            }

            // Dots placeholder
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 22.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(shimmerBrush),
                )
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(width = 6.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(shimmerBrush),
                    )
                }
            }

            // Read button placeholder
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(shimmerBrush),
            )
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
    subtitle: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        // Section Header: Title (+ Subtitle) + Arrow button placeholder
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
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

                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    )
                }
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
        
        // Filter Buttons Row Placeholder
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(shimmerBrush),
            )
            Box(
                modifier = Modifier
                    .width(90.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(shimmerBrush),
            )
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(shimmerBrush),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section Title Placeholder
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(130.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBrush),
            )
            Box(
                modifier = Modifier
                    .width(70.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBrush),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3-Column Grid Placeholder
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            FeedGridSkeleton(shimmerBrush = shimmerBrush)
        }

        Spacer(modifier = Modifier.height(96.dp))
    }
}

/**
 * Grid skeleton placeholder for feed screens.
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
