package eu.kanade.tachiyomi.ui.home.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.tab.Tab as VoyagerTab
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.discover.DiscoverTab
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.more.MoreTab

@Composable
fun WavyDockNavigationBar(
    tabs: List<Tab>,
    currentTab: VoyagerTab,
    onTabSelected: (Tab) -> Unit,
    modifier: Modifier = Modifier,
    badgeContent: @Composable ((Tab) -> Unit)? = null,
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val dockBackground = MaterialTheme.colorScheme.surfaceContainer
    val badgeBg = MaterialTheme.colorScheme.surfaceContainerHigh

    val selectedIndex = remember(tabs, currentTab) {
        tabs.indexOfFirst { it::class == currentTab::class }.coerceAtLeast(0)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(70.dp),
    ) {
        val totalWidthPx = constraints.maxWidth.toFloat()
        val totalHeightPx = constraints.maxHeight.toFloat()
        val tabCount = tabs.size.coerceAtLeast(1)
        val tabWidthPx = totalWidthPx / tabCount

        val targetCenterX = (selectedIndex + 0.5f) * tabWidthPx
        val animatedCenterX by animateFloatAsState(
            targetValue = targetCenterX,
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "wavy_dock_center_x",
        )

        // 1. Solid Custom Wavy Canvas Background
        Canvas(modifier = Modifier.fillMaxSize()) {
            val baselineY = 12.dp.toPx()
            val peakY = 2.dp.toPx()
            val waveRadius = 38.dp.toPx()

            // Fill Path
            val fillPath = Path().apply {
                moveTo(0f, baselineY)
                val leftStart = (animatedCenterX - waveRadius - 14.dp.toPx()).coerceAtLeast(0f)
                val rightEnd = (animatedCenterX + waveRadius + 14.dp.toPx()).coerceAtMost(totalWidthPx)

                lineTo(leftStart, baselineY)
                cubicTo(
                    animatedCenterX - waveRadius, baselineY,
                    animatedCenterX - waveRadius * 0.45f, peakY,
                    animatedCenterX, peakY,
                )
                cubicTo(
                    animatedCenterX + waveRadius * 0.45f, peakY,
                    animatedCenterX + waveRadius, baselineY,
                    rightEnd, baselineY,
                )
                lineTo(totalWidthPx, baselineY)
                lineTo(totalWidthPx, totalHeightPx)
                lineTo(0f, totalHeightPx)
                close()
            }

            drawPath(fillPath, color = dockBackground)

            // Accent Glow Stroke ONLY on the active wave hump (no horizontal line across baseline)
            val leftStart = (animatedCenterX - waveRadius - 14.dp.toPx()).coerceAtLeast(0f)
            val rightEnd = (animatedCenterX + waveRadius + 14.dp.toPx()).coerceAtMost(totalWidthPx)

            val rimPath = Path().apply {
                moveTo(leftStart, baselineY)
                cubicTo(
                    animatedCenterX - waveRadius, baselineY,
                    animatedCenterX - waveRadius * 0.45f, peakY,
                    animatedCenterX, peakY,
                )
                cubicTo(
                    animatedCenterX + waveRadius * 0.45f, peakY,
                    animatedCenterX + waveRadius, baselineY,
                    rightEnd, baselineY,
                )
            }

            val rimBrush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    accentColor.copy(alpha = 0.85f),
                    Color.Transparent,
                ),
                startX = leftStart,
                endX = rightEnd,
            )

            drawPath(
                path = rimPath,
                brush = rimBrush,
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // 2. Tab Items Row
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Bottom,
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = index == selectedIndex
                val interactionSource = remember { MutableInteractionSource() }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onTabSelected(tab) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        // Elevated Active Tab Component
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(bottom = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            // Upward Floating Circular Active Badge
                            Box(
                                modifier = Modifier
                                    .size(46.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                // Glowing Halo Accent
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .background(
                                            Brush.radialGradient(
                                                colors = listOf(
                                                    accentColor.copy(alpha = 0.35f),
                                                    Color.Transparent,
                                                ),
                                            ),
                                            CircleShape,
                                        ),
                                    )

                                // Themed Dark Badge Container with Accent Border
                                Surface(
                                    modifier = Modifier.size(42.dp),
                                    shape = CircleShape,
                                    color = badgeBg,
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, accentColor),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        TabIcon(tab = tab, isSelected = true, badgeContent = badgeContent)
                                    }
                                }
                            }

                            // Active Label + Theme Dot Indicator Underneath
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                Text(
                                    text = tab.options.title,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                // Tiny Active Dot Indicator
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(accentColor),
                                )
                            }
                        }
                    } else {
                        // Secondary Inactive Tab Items
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(top = 16.dp, bottom = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                TabIcon(tab = tab, isSelected = false, badgeContent = badgeContent)
                            }

                            Spacer(modifier = Modifier.height(3.dp))

                            Text(
                                text = tab.options.title,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabIcon(
    tab: Tab,
    isSelected: Boolean,
    badgeContent: @Composable ((Tab) -> Unit)?,
) {
    val (filledIcon, outlinedIcon) = remember(tab) {
        when (tab) {
            is DiscoverTab -> Pair(Icons.Filled.Home, Icons.Outlined.Home)
            is HistoryTab -> Pair(Icons.Filled.History, Icons.Outlined.History)
            is BrowseTab -> Pair(Icons.Filled.Explore, Icons.Outlined.Explore)
            is MoreTab -> Pair(Icons.Filled.MoreHoriz, Icons.Outlined.MoreHoriz)
            else -> Pair(null, null)
        }
    }

    val iconColor = if (isSelected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val iconComposable: @Composable () -> Unit = {
        if (filledIcon != null && outlinedIcon != null) {
            val vector = if (isSelected) filledIcon else outlinedIcon
            Icon(
                imageVector = vector,
                contentDescription = tab.options.title,
                tint = iconColor,
                modifier = Modifier.size(24.dp),
            )
        } else {
            val painter = tab.options.icon ?: rememberVectorPainter(Icons.Outlined.CollectionsBookmark)
            Icon(
                painter = painter,
                contentDescription = tab.options.title,
                tint = iconColor,
                modifier = Modifier.size(24.dp),
            )
        }
    }

    if (badgeContent != null) {
        BadgedBox(
            badge = { badgeContent(tab) },
        ) {
            iconComposable()
        }
    } else {
        iconComposable()
    }
}
