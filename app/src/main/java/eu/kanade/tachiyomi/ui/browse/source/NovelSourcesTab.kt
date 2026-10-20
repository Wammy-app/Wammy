package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined._18UpRating
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.SourceOptionsDialog
import eu.kanade.presentation.browse.SourcePinGroupsDialog
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import eu.kanade.tachiyomi.ui.browse.source.custom.CustomSourcesScreen
import eu.kanade.tachiyomi.ui.browse.source.feed.SourceFeedScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.NovelGlobalSearchScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tachiyomi.domain.source.model.Pin
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.novelSourcesTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = viewModel<NovelSourcesViewModel>()
    val nsfwOnly by remember(screenModel) {
        screenModel.state.map { it.nsfwOnly }.distinctUntilChanged()
    }.collectAsState(initial = screenModel.state.value.nsfwOnly)

    return TabContent(
        titleRes = TDMR.strings.label_novel_sources,
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = { navigator.push(NovelGlobalSearchScreen()) },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.action_filter),
                icon = Icons.Outlined.FilterList,
                onClick = { navigator.push(SourcesFilterScreen(isNovel = true)) },
            ),
            AppBar.Action(
                title = "Custom Sources", // TODO: Add string resource
                icon = Icons.Outlined.Edit,
                onClick = { navigator.push(CustomSourcesScreen()) },
            ),
            AppBar.Action(
                title = "18+",
                icon = Icons.Outlined._18UpRating,
                iconTint = if (nsfwOnly) androidx.compose.material3.MaterialTheme.colorScheme.error else null,
                onClick = { screenModel.toggleNsfwOnly() },
            ),
        ),
        content = { contentPadding, snackbarHostState ->
            val state by screenModel.state.collectAsState()
            val mappedDialog = when (val d = state.dialog) {
                is NovelSourcesViewModel.Dialog.SourceOptions -> SourcesViewModel.Dialog.SourceOptions(d.source)
                is NovelSourcesViewModel.Dialog.PinGroups -> SourcesViewModel.Dialog.PinGroups(d.source)
                null -> null
            }
            SourcesScreen(
                state = SourcesViewModel.State(
                    dialog = mappedDialog,
                    isLoading = state.isLoading,
                    builtinSources = state.builtinSources,
                    items = state.items,
                ),
                contentPadding = contentPadding,
                onClickItem = { source, listing ->
                    navigator.push(BrowseSourceScreen(source.id, listing.query, isFeed = true))
                },
                onLongClickItem = { source ->
                    screenModel.showSourceDialog(source)
                },
                onClickPin = screenModel::togglePin,
                onLongClickPin = screenModel::showPinGroupsDialog,
                onRemoveFromGroup = screenModel::removeSourceFromGroup,
                onClickUninstall = screenModel::uninstallSource,
            )

            val currentDialog = state.dialog

            if (currentDialog != null) {
                when (currentDialog) {
                    is NovelSourcesViewModel.Dialog.SourceOptions -> {
                        val source = currentDialog.source
                        SourceOptionsDialog(
                            source = source,
                            onClickPin = {
                                screenModel.togglePin(source)
                                screenModel.closeDialog()
                            },
                            onClickPinGroups = {
                                screenModel.showPinGroupsDialog(source)
                            },
                            onClickDisable = {
                                screenModel.toggleSource(source)
                                screenModel.closeDialog()
                            },
                            onDismiss = screenModel::closeDialog,
                        )
                    }
                    is NovelSourcesViewModel.Dialog.PinGroups -> {
                        val source = currentDialog.source
                        SourcePinGroupsDialog(
                            source = source,
                            pinGroups = screenModel.getSourcePinGroups(source),
                            isPinned = Pin.Pinned in source.pin,
                            onTogglePin = { screenModel.togglePin(source) },
                            onConfirm = { selectedGroups ->
                                screenModel.setSourcePinGroups(source, selectedGroups)
                                screenModel.closeDialog()
                            },
                            onDeleteGroup = screenModel::deleteSourcePinGroup,
                            onDismiss = screenModel::closeDialog,
                        )
                    }
                }
            }

            val internalErrString = stringResource(MR.strings.internal_error)
            LaunchedEffect(Unit) {
                screenModel.events.collectLatest { event ->
                    when (event) {
                        NovelSourcesViewModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                    }
                }
            }
        },
    )
}
