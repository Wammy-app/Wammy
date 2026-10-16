package eu.kanade.tachiyomi.ui.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
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
    val unselectedContent = cs.onSurfaceVariant

    val selectedIndex = remember(tabs, currentTab) {
        tabs.indexOfFirst { it::class == currentTab::class }.coerceAtLeast(0)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.wrapContentWidth(),
            shape = RoundedCornerShape(50),
            color = barContainer,
            contentColor = barContent,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 50.dp)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    ),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
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
                        moreThanFourTabs = tabs.size > 4,
                        onClick = { onTabSelected(tab) },
                        badgeContent = badgeContent,
                    )
                }
            }
        }

        if (onReadingClick != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Surface(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.material3.ripple(),
                        onClick = onReadingClick,
                    ),
                shape = RoundedCornerShape(14.dp),
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
                        modifier = Modifier.size(22.dp),
                        tint = selectedContent,
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingNavItem(
    tab: Tab,
    isSelected: Boolean,
    itemBackgroundColor: Color,
    itemContentColor: Color,
    moreThanFourTabs: Boolean,
    onClick: () -> Unit,
    badgeContent: @Composable ((Tab) -> Unit)?,
) {
    val title = tab.options.title
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .height(38.dp)
            .background(color = itemBackgroundColor, shape = CircleShape)
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
        Row(
            modifier = Modifier.padding(
                horizontal = if (moreThanFourTabs) {
                    if (isSelected) 8.dp else 4.dp
                } else {
                    10.dp
                },
            ),
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
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
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
            is MoreTab -> Pair(Icons.Filled.MoreHoriz, Icons.Outlined.MoreHoriz)
            else -> Pair(null, null)
        }
    }

    val iconComposable: @Composable () -> Unit = {
        if (filledIcon != null && outlinedIcon != null) {
            val vector = if (isSelected) filledIcon else outlinedIcon
            Icon(
                imageVector = vector,
                contentDescription = title,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        } else {
            val painter = tab.options.icon ?: rememberVectorPainter(Icons.Outlined.CollectionsBookmark)
            Icon(
                painter = painter,
                contentDescription = title,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
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
