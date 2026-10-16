package eu.kanade.tachiyomi.ui.news

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.news.NewsArticleScreen
import eu.kanade.presentation.news.NewsContent
import eu.kanade.presentation.util.Tab
import tachiyomi.presentation.core.components.material.Scaffold

data object NewsTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            return TabOptions(
                index = 5u,
                title = "News",
                icon = rememberVectorPainter(Icons.Outlined.Newspaper),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // Reselect action if needed
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<NewsViewModel>()
        val state by viewModel.state.collectAsState()

        val infiniteTransition = rememberInfiniteTransition(label = "newsRefreshSpin")
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "newsRefreshRotation",
        )

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "News",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.loadNews(forceRefresh = true) },
                            enabled = !state.isRefreshing && !state.isLoading,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Refresh",
                                modifier = if (state.isRefreshing) Modifier.graphicsLayer { rotationZ = rotation } else Modifier,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { contentPadding ->
            NewsContent(
                state = state,
                contentPadding = contentPadding,
                onRefresh = { viewModel.loadNews(forceRefresh = true) },
                formatRelativeTime = { viewModel.formatRelativeTime(it) },
                onArticleClick = { item, relativeTime ->
                    navigator.push(NewsArticleScreen(item, relativeTime))
                },
            )
        }
    }
}
