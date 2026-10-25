package eu.kanade.presentation.reader.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import eu.kanade.presentation.components.TabTitle
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.components.toTabTitles
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun ReaderSettingsDialog(
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
    viewModel: ReaderSettingsViewModel,
    isNovelMode: Boolean = false,
) {
    if (isNovelMode) {
        NovelReaderSettingsDialog(
            onDismissRequest = onDismissRequest,
            onShowMenus = onShowMenus,
            viewModel = viewModel,
        )
    } else {
        MangaReaderSettingsDialog(
            onDismissRequest = onDismissRequest,
            onShowMenus = onShowMenus,
            onHideMenus = onHideMenus,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun MangaReaderSettingsDialog(
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
    viewModel: ReaderSettingsViewModel,
) {
    val tabTitles = listOf(
        stringResource(MR.strings.pref_category_reading_mode),
        stringResource(MR.strings.pref_category_general),
        stringResource(MR.strings.custom_filter),
    ).toTabTitles()
    val pagerState = rememberPagerState { tabTitles.size }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 2) {
            onHideMenus()
        } else {
            onShowMenus()
        }
    }

    ReaderSettingsSheet(
        onDismissRequest = {
            onDismissRequest()
            onShowMenus()
        },
        tabTitles = tabTitles,
        pagerState = pagerState,
        scrimColor = if (pagerState.currentPage == 2) Color.Transparent else BottomSheetDefaults.ScrimColor,
    ) { page ->
        when (page) {
            0 -> ReadingModePage(viewModel)
            1 -> GeneralPage(viewModel)
            2 -> ColorFilterPage(viewModel)
        }
    }
}

@Composable
private fun NovelReaderSettingsDialog(
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    viewModel: ReaderSettingsViewModel,
) {
    val renderingMode by viewModel.preferences.novelRenderingMode.collectAsState()
    val tabTitles = listOf(
        TabTitle.Icon(imageVector = Icons.Outlined.TextFields), // Reading
        TabTitle.Icon(imageVector = Icons.Outlined.Palette), // Appearance
        TabTitle.Icon(imageVector = Icons.Outlined.Swipe), // Controls
        TabTitle.Icon(imageVector = Icons.Outlined.RecordVoiceOver), // TTS
        TabTitle.Icon(imageVector = Icons.Outlined.Code), // Advanced
    )
    val pagerState = rememberPagerState { tabTitles.size }

    ReaderSettingsSheet(
        onDismissRequest = {
            onDismissRequest()
            onShowMenus()
        },
        tabTitles = tabTitles,
        pagerState = pagerState,
    ) { page ->
        when (page) {
            0 -> NovelReadingTab(viewModel, renderingMode)
            1 -> NovelAppearanceTab(viewModel, renderingMode)
            2 -> NovelControlsTab(viewModel, renderingMode)
            3 -> NovelTtsTab(viewModel)
            4 -> NovelAdvancedTab(viewModel, renderingMode)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    onDismissRequest: () -> Unit,
    tabTitles: List<TabTitle>,
    pagerState: PagerState,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    content: @Composable ColumnScope.(Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        scrimColor = scrimColor,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        BoxWithConstraints {
            val sheetMaxHeight = maxHeight * 0.75f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 16.dp),
            ) {
                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                    divider = { HorizontalDivider() },
                ) {
                    tabTitles.fastForEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = if (tab is TabTitle.Text) ({ TabText(text = tab.value) }) else null,
                            icon = if (tab is TabTitle.Icon) {
                                { Icon(imageVector = tab.imageVector, contentDescription = null) }
                            } else {
                                null
                            },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                HorizontalPager(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = sheetMaxHeight)
                        .animateContentSize(),
                    state = pagerState,
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = TabbedDialogPaddings.Vertical)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        content(page)
                    }
                }
            }
        }
    }
}
