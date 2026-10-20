package eu.kanade.tachiyomi.ui.browse.extension

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined._18UpRating
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.ExtensionScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun extensionsTab(
    extensionsViewModel: ExtensionsViewModel,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current

    val updates by remember(extensionsViewModel) {
        extensionsViewModel.state.map { it.updates }.distinctUntilChanged()
    }.collectAsState(initial = extensionsViewModel.state.value.updates)

    val nsfwOnly by remember(extensionsViewModel) {
        extensionsViewModel.state.map { it.nsfwOnly }.distinctUntilChanged()
    }.collectAsState(initial = extensionsViewModel.state.value.nsfwOnly)

    var privateExtensionToUninstall by remember { mutableStateOf<Extension?>(null) }

    return TabContent(
        titleRes = TDMR.strings.label_manga_extensions,
        badgeNumber = updates.takeIf { it > 0 },
        searchEnabled = true,
        actions = listOf(
            AppBar.Action(
                title = "18+",
                icon = Icons.Outlined._18UpRating,
                iconTint = if (nsfwOnly) androidx.compose.material3.MaterialTheme.colorScheme.error else null,
                onClick = { extensionsViewModel.toggleNsfwOnly() },
            ),
            AppBar.OverflowAction(
                title = stringResource(MR.strings.action_filter),
                onClick = { navigator.push(ExtensionFilterScreen()) },
            ),
            AppBar.OverflowAction(
                title = stringResource(MR.strings.extensionStores),
                onClick = { navigator.push(ExtensionStoresScreen()) },
            ),
        ),
        content = { contentPadding, _ ->
            val state by extensionsViewModel.state.collectAsState()
            BackHandler(enabled = state.searchQuery != null) {
                extensionsViewModel.search(null)
            }

            ExtensionScreen(
                state = state,
                contentPadding = contentPadding,
                searchQuery = state.searchQuery,
                onLongClickItem = { extension ->
                    when (extension) {
                        is Extension.Available -> extensionsViewModel.installExtension(extension)
                        else -> {
                            if (context.isPackageInstalled(extension.pkgName)) {
                                extensionsViewModel.uninstallExtension(extension)
                            } else {
                                privateExtensionToUninstall = extension
                            }
                        }
                    }
                },
                onClickItemCancel = extensionsViewModel::cancelInstallUpdateExtension,
                onClickUpdateAll = extensionsViewModel::updateAllExtensions,
                onOpenWebView = { extension ->
                    when (extension) {
                        is Extension.Available -> {
                            extension.sources.firstOrNull()?.let {
                                navigator.push(
                                    WebViewScreen(
                                        url = it.baseUrl,
                                        initialTitle = it.name,
                                        sourceId = it.id,
                                    ),
                                )
                            }
                        }
                        is Extension.Installed -> {
                            (extension.sources.firstOrNull() as? eu.kanade.tachiyomi.source.online.HttpSource)?.let {
                                navigator.push(
                                    WebViewScreen(
                                        url = it.baseUrl,
                                        initialTitle = it.name,
                                        sourceId = it.id,
                                    ),
                                )
                            }
                        }
                        is Extension.JsPlugin -> {
                            extension.sources.firstOrNull()?.let {
                                navigator.push(
                                    WebViewScreen(
                                        url = it.baseUrl,
                                        initialTitle = it.name,
                                        sourceId = it.id,
                                    ),
                                )
                            }
                        }
                        else -> {}
                    }
                },
                onInstallExtension = { if (it is Extension.Available) extensionsViewModel.installExtension(it) },
                onOpenExtension = { if (it is Extension.Installed) navigator.push(ExtensionDetailsScreen(it.pkgName)) },
                onTrustExtension = { extensionsViewModel.trustExtension(it) },
                onUninstallExtension = { extensionsViewModel.uninstallExtension(it) },
                onUpdateExtension = { if (it is Extension.Installed) extensionsViewModel.updateExtension(it) },
                onRefresh = extensionsViewModel::findAvailableExtensions,
            )

            privateExtensionToUninstall?.let { extension ->
                ExtensionUninstallConfirmation(
                    extensionName = extension.name,
                    onClickConfirm = {
                        extensionsViewModel.uninstallExtension(extension)
                    },
                    onDismissRequest = {
                        privateExtensionToUninstall = null
                    },
                )
            }
        },
    )
}

@Composable
private fun ExtensionUninstallConfirmation(
    extensionName: String,
    onClickConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = stringResource(MR.strings.ext_confirm_remove))
        },
        text = {
            Text(text = stringResource(MR.strings.remove_private_extension_message, extensionName))
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onClickConfirm()
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.ext_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        onDismissRequest = onDismissRequest,
    )
}
