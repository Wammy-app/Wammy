package eu.kanade.tachiyomi.ui.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.ui.draw.clip
import eu.kanade.tachiyomi.ui.news.NewsTab

@Composable
fun WavyDockNavigationBar(
    tabs: List<Tab>,
    currentTab: VoyagerTab,
    onTabSelected: (Tab) -> Unit,
    modifier: Modifier = Modifier,
    badgeContent: @Composable ((Tab) -> Unit)? = null,
    onReadingClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    // DropSauce design tokens dynamically mapped to MaterialTheme
    val barContainer = cs.surfaceContainer
    val barContent = cs.onSurface
    val selectedContainer = cs.primaryContainer
    val selectedContent = cs.onPrimaryContainer
    val unselectedContent = cs.onSurface.copy(alpha = 0.78f)

    val selectedIndex = remember(tabs, currentTab) {
        tabs.indexOfFirst { it::class == currentTab::class }.coerceAtLeast(0)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = if (onReadingClick != null) Modifier.weight(1f) else Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
            color = barContainer,
            contentColor = barContent,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = index == selectedIndex

                    val itemBackgroundColor by animateColorAsState(
                        targetValue = if (isSelected) selectedContainer else Color.Transparent,
                        animationSpec = tween(durationMillis = 220),
                        label = "navItemContainer",
                    )
                    val itemContentColor by animateColorAsState(
                        targetValue = if (isSelected) selectedContent else unselectedContent,
                        animationSpec = tween(durationMillis = 220),
                        label = "navItemContent",
                    )

                    FloatingNavItem(
                        tab = tab,
                        isSelected = isSelected,
                        itemBackgroundColor = itemBackgroundColor,
                        itemContentColor = itemContentColor,
                        onClick = { onTabSelected(tab) },
                        badgeContent = badgeContent,
                    )
                }
            }
        }

        if (onReadingClick != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.material3.ripple(),
                        onClick = onReadingClick,
                    ),
                shape = RoundedCornerShape(16.dp),
                color = selectedContainer,
                contentColor = selectedContent,
                shadowElevation = 4.dp,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = "Resume Reading",
                        modifier = Modifier.size(24.dp),
                        tint = selectedContent,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.FloatingNavItem(
    tab: Tab,
    isSelected: Boolean,
    itemBackgroundColor: Color,
    itemContentColor: Color,
    onClick: () -> Unit,
    badgeContent: @Composable ((Tab) -> Unit)?,
) {
    val title = tab.options.title
    val interactionSource = remember { MutableInteractionSource() }

    val animatedWeight by animateFloatAsState(
        targetValue = if (isSelected) 2.0f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "tabWeight",
    )

    Box(
        modifier = Modifier
            .weight(animatedWeight)
            .height(50.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                this.selected = isSelected
                role = Role.Tab
                contentDescription = title
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(38.dp)
                .background(color = itemBackgroundColor, shape = CircleShape)
                .padding(horizontal = if (isSelected) 8.dp else 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                TabIcon(
                    tab = tab,
                    title = title,
                    isSelected = isSelected,
                    contentColor = itemContentColor,
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
                    Text(
                        text = title,
                        color = itemContentColor,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabIcon(
    tab: Tab,
    title: String,
    isSelected: Boolean,
    contentColor: Color,
    badgeContent: @Composable ((Tab) -> Unit)?,
) {
    val (filledIcon, outlinedIcon) = remember(tab) {
        when (tab) {
            is DiscoverTab -> Pair(Icons.Filled.Home, Icons.Outlined.Home)
            is HistoryTab -> Pair(Icons.Filled.History, Icons.Outlined.History)
            is BrowseTab -> Pair(Icons.Filled.Explore, Icons.Outlined.Explore)
            is NewsTab -> Pair(Icons.Outlined.Newspaper, Icons.Outlined.Newspaper)
            is MoreTab -> Pair(Icons.Filled.MoreHoriz, Icons.Filled.MoreHoriz)
            else -> Pair(null, null)
        }
    }

    val iconComposable: @Composable () -> Unit = {
        val iconSize = if (tab is MoreTab) 24.dp else 22.dp
        if (filledIcon != null && outlinedIcon != null) {
            val vector = if (isSelected) filledIcon else outlinedIcon
            Icon(
                imageVector = vector,
                contentDescription = title,
                tint = contentColor,
                modifier = Modifier.size(iconSize),
            )
        } else {
            val painter = tab.options.icon ?: rememberVectorPainter(Icons.Outlined.CollectionsBookmark)
            Icon(
                painter = painter,
                contentDescription = title,
                tint = contentColor,
                modifier = Modifier.size(iconSize),
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
