package eu.kanade.tachiyomi.ui.browse

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel
import eu.kanade.tachiyomi.ui.browse.extension.NovelExtensionsViewModel
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import eu.kanade.tachiyomi.ui.browse.extension.novelExtensionsTab
import eu.kanade.tachiyomi.ui.browse.migration.sources.migrateSourceTab
import eu.kanade.tachiyomi.ui.browse.migration.sources.novelMigrateSourceTab
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.NovelGlobalSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.novelSourcesTab
import eu.kanade.tachiyomi.ui.browse.source.sourcesTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data object BrowseTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_browse_enter)
            return TabOptions(
                index = 4u,
                title = stringResource(MR.strings.browse),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        val hideMangaUi = Injekt.get<BasePreferences>().hideMangaUi.get()
        navigator.push(if (hideMangaUi) NovelGlobalSearchScreen() else GlobalSearchScreen())
    }

    private val switchToExtensionTabChannel = Channel<Unit>(1, BufferOverflow.DROP_OLDEST)

    fun showExtension() {
        switchToExtensionTabChannel.trySend(Unit)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val basePreferences = remember { Injekt.get<BasePreferences>() }
        val hideMangaUi by basePreferences.hideMangaUi.collectAsState()
        val hideMangaBrowseTabs = hideMangaUi

        // Hoisted for extensions tab's search bar
        val extensionsViewModel = viewModel<ExtensionsViewModel>()
        val mangaSearchQuery by remember(extensionsViewModel) {
            extensionsViewModel.state.map { it.searchQuery }.distinctUntilChanged()
        }.collectAsState(initial = null)

        val novelExtensionsViewModel = viewModel<NovelExtensionsViewModel>()
        val novelSearchQuery by remember(novelExtensionsViewModel) {
            novelExtensionsViewModel.state.map { it.searchQuery }.distinctUntilChanged()
        }.collectAsState(initial = null)

        val tabs = if (hideMangaBrowseTabs) {
            listOf(
                novelSourcesTab(),
                novelExtensionsTab(novelExtensionsViewModel),
                novelMigrateSourceTab(),
            )
        } else {
            listOf(
                sourcesTab(),
                novelSourcesTab(),
                extensionsTab(extensionsViewModel),
                novelExtensionsTab(novelExtensionsViewModel),
                migrateSourceTab(),
                novelMigrateSourceTab(),
            )
        }

        val novelExtensionsTabIndex = if (hideMangaBrowseTabs) 1 else 3
        val mangaExtensionsTabIndex = if (hideMangaBrowseTabs) null else 2

        val state = rememberPagerState { tabs.size }

        TabbedScreen(
            titleRes = MR.strings.browse,
            tabs = tabs,
            state = state,
            searchQuery = when (state.currentPage) {
                novelExtensionsTabIndex -> novelSearchQuery
                mangaExtensionsTabIndex -> mangaSearchQuery
                else -> null
            },
            onChangeSearchQuery = { query ->
                when (state.currentPage) {
                    novelExtensionsTabIndex -> novelExtensionsViewModel.search(query)
                    mangaExtensionsTabIndex -> extensionsViewModel.search(query)
                }
            },
        )
        LaunchedEffect(Unit) {
            switchToExtensionTabChannel.receiveAsFlow()
                .collectLatest {
                    state.scrollToPage(if (hideMangaBrowseTabs) novelExtensionsTabIndex else 2)
                }
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }
    }
}
