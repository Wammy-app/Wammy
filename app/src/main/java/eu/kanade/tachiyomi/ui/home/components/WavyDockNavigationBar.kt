package eu.kanade.tachiyomi.ui.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
    val primaryColor = MaterialTheme.colorScheme.primary
    val baseSurface = MaterialTheme.colorScheme.surfaceContainer
    // Nav bar background tinted with the theme color, but softer than the selected tab
    val dockBackground = lerp(baseSurface, primaryColor, 0.14f)
    // Selected tab highlighted more strongly with the theme color
    val activePillColor = lerp(baseSurface, primaryColor, 0.34f)

    val selectedIndex = remember(tabs, currentTab) {
        tabs.indexOfFirst { it::class == currentTab::class }.coerceAtLeast(0)
    }

    val activeItemShape = RoundedCornerShape(12.dp)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = dockBackground,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(64.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = index == selectedIndex
                val interactionSource = remember { MutableInteractionSource() }

                val itemBackgroundColor by animateColorAsState(
                    targetValue = if (isSelected) activePillColor else Color.Transparent,
                    animationSpec = tween(durationMillis = 220),
                    label = "nav_item_bg",
                )

                Box(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(activeItemShape)
                        .background(itemBackgroundColor, activeItemShape)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onTabSelected(tab) },
                        )
                        .padding(
                            horizontal = if (isSelected) 14.dp else 10.dp,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        TabIcon(
                            tab = tab,
                            isSelected = isSelected,
                            badgeContent = badgeContent,
                        )

                        AnimatedVisibility(
                            visible = isSelected,
                            enter = expandHorizontally(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                                expandFrom = Alignment.Start,
                            ) + fadeIn(animationSpec = tween(180)),
                            exit = shrinkHorizontally(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                                shrinkTowards = Alignment.Start,
                            ) + fadeOut(animationSpec = tween(120)),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = tab.options.title,
                                    color = primaryColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
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
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    }

    val iconComposable: @Composable () -> Unit = {
        if (filledIcon != null && outlinedIcon != null) {
            val vector = if (isSelected) filledIcon else outlinedIcon
            Icon(
                imageVector = vector,
                contentDescription = tab.options.title,
                tint = iconColor,
                modifier = Modifier.size(21.dp),
            )
        } else {
            val painter = tab.options.icon ?: rememberVectorPainter(Icons.Outlined.CollectionsBookmark)
            Icon(
                painter = painter,
                contentDescription = tab.options.title,
                tint = iconColor,
                modifier = Modifier.size(21.dp),
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
