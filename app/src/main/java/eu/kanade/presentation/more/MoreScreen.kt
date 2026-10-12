package eu.kanade.presentation.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.more.DownloadQueueState
import tachiyomi.core.common.Constants
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import tachiyomi.presentation.core.util.collectAsState
import eu.kanade.domain.ui.UiPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.material.icons.outlined.TouchApp

@Composable
fun MoreScreen(
    downloadQueueStateProvider: () -> DownloadQueueState,
    downloadedOnly: Boolean,
    onDownloadedOnlyChange: (Boolean) -> Unit,
    incognitoMode: Boolean,
    onIncognitoModeChange: (Boolean) -> Unit,
    sandboxExtensionsEnabled: Boolean,
    onSandboxExtensionsChange: (Boolean) -> Unit,
    onClickDownloadQueue: () -> Unit,
    onClickUpdates: () -> Unit,
    onClickCategories: () -> Unit,
    onClickStats: () -> Unit,
    onClickDataAndStorage: () -> Unit,
    onClickSettings: () -> Unit,
    onClickSupport: () -> Unit,
    onClickAbout: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val fabSizeDp by uiPreferences.fabSizeDp.collectAsState()
    var showSandboxDialog by remember { mutableStateOf(false) }

    Scaffold { contentPadding ->
        ScrollbarLazyColumn(contentPadding = contentPadding) {
            item {
                LogoHeader(
                    iconPadding = PaddingValues(vertical = 32.dp),
                )
            }

            // Mode Group
            item {
                SettingsGroup(title = "Mode") {
                    SettingItem(
                        title = stringResource(MR.strings.label_downloaded_only),
                        subtitle = stringResource(MR.strings.downloaded_only_summary),
                        icon = Icons.Outlined.CloudOff,
                        onClick = { onDownloadedOnlyChange(!downloadedOnly) },
                        trailing = {
                            Switch(checked = downloadedOnly, onCheckedChange = onDownloadedOnlyChange)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingItem(
                        title = stringResource(MR.strings.pref_incognito_mode),
                        subtitle = stringResource(MR.strings.pref_incognito_mode_summary),
                        icon = ImageVector.vectorResource(R.drawable.ic_glasses_24dp),
                        onClick = { onIncognitoModeChange(!incognitoMode) },
                        trailing = {
                            Switch(checked = incognitoMode, onCheckedChange = onIncognitoModeChange)
                        }
                    )
                }
            }

            // Content Group
            item {
                SettingsGroup(title = "Content") {
                    val downloadQueueState = downloadQueueStateProvider()
                    val downloadSubtitle = when (downloadQueueState) {
                        DownloadQueueState.Stopped -> null
                        is DownloadQueueState.Paused -> {
                            val pending = downloadQueueState.pending
                            if (pending == 0) {
                                stringResource(MR.strings.paused)
                            } else {
                                "${stringResource(MR.strings.paused)} • ${
                                    pluralStringResource(
                                        MR.plurals.download_queue_summary,
                                        count = pending,
                                        pending,
                                    )
                                }"
                            }
                        }
                        is DownloadQueueState.Downloading -> {
                            val pending = downloadQueueState.pending
                            pluralStringResource(MR.plurals.download_queue_summary, count = pending, pending)
                        }
                    }
                    SettingItem(
                        title = stringResource(MR.strings.label_download_queue),
                        subtitle = downloadSubtitle,
                        icon = Icons.Outlined.GetApp,
                        onClick = onClickDownloadQueue,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingItem(
                        title = stringResource(MR.strings.categories),
                        icon = Icons.AutoMirrored.Outlined.Label,
                        onClick = onClickCategories,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingItem(
                        title = stringResource(MR.strings.label_stats),
                        icon = Icons.Outlined.QueryStats,
                        onClick = onClickStats,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingItem(
                        title = stringResource(MR.strings.label_data_storage),
                        icon = Icons.Outlined.Storage,
                        onClick = onClickDataAndStorage,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingItem(
                        title = "Sandbox extensions",
                        subtitle = "Install extensions inside the app. No install popups, silent updates.",
                        icon = Icons.Outlined.Security,
                        onClick = {
                            if (!sandboxExtensionsEnabled) {
                                showSandboxDialog = true
                            } else {
                                onSandboxExtensionsChange(false)
                            }
                        },
                        trailing = {
                            Switch(
                                checked = sandboxExtensionsEnabled,
                                onCheckedChange = { newValue ->
                                    if (newValue) {
                                        showSandboxDialog = true
                                    } else {
                                        onSandboxExtensionsChange(false)
                                    }
                                },
                            )
                        },
                    )
                }
            }

            // App Group
            item {
                SettingsGroup(title = "App") {
                    SettingItem(
                        title = "Switch Button Size",
                        subtitle = "${fabSizeDp}dp",
                        icon = Icons.Outlined.TouchApp,
                        onClick = null,
                        trailing = {
                            Image(
                                painter = painterResource(id = eu.kanade.tachiyomi.R.drawable.devil_fruit),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    )
                    Slider(
                        value = fabSizeDp.toFloat(),
                        onValueChange = { uiPreferences.fabSizeDp.set(it.toInt()) },
                        valueRange = 48f..96f,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 0.dp)
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingItem(
                        title = stringResource(MR.strings.label_settings),
                        icon = Icons.Outlined.Settings,
                        onClick = onClickSettings,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingItem(
                        title = stringResource(MR.strings.pref_category_about),
                        icon = Icons.Outlined.Info,
                        onClick = onClickAbout,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SettingItem(
                        title = stringResource(MR.strings.label_help),
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = { uriHandler.openUri(Constants.URL_HELP) },
                    )
                }
            }
        }
    }

    // Sandbox confirmation dialog
    if (showSandboxDialog) {
        AlertDialog(
            onDismissRequest = { showSandboxDialog = false },
            title = { Text("Enable sandbox mode?") },
            text = {
                Text(
                    "Extensions will be installed inside Wammy's private storage.\n\n" +
                        "• No Android install popups\n" +
                        "• Silent automatic updates\n" +
                        "• Extensions are hidden from other apps\n\n" +
                        "Your existing extensions will keep working. New installs and updates will use sandbox mode.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSandboxDialog = false
                    onSandboxExtensionsChange(true)
                }) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = { showSandboxDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
fun SettingItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(16.dp))
            trailing()
        }
    }
}
