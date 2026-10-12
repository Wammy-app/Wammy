package eu.kanade.presentation.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import tachiyomi.presentation.core.util.plus
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
        ScrollbarLazyColumn(contentPadding = contentPadding + PaddingValues(bottom = 84.dp)) {
            item {
                LogoHeader(
                    iconPadding = PaddingValues(top = 20.dp, bottom = 8.dp),
                )
            }

            // Mode Group
            item {
                SettingsGroup(title = "Mode") {
                    item { pos ->
                        SettingItem(
                            title = stringResource(MR.strings.label_downloaded_only),
                            subtitle = stringResource(MR.strings.downloaded_only_summary),
                            icon = Icons.Outlined.CloudOff,
                            shape = pos.shape,
                            onClick = { onDownloadedOnlyChange(!downloadedOnly) },
                            trailing = {
                                Switch(
                                    checked = downloadedOnly,
                                    onCheckedChange = onDownloadedOnlyChange,
                                )
                            },
                        )
                    }
                    item { pos ->
                        SettingItem(
                            title = stringResource(MR.strings.pref_incognito_mode),
                            subtitle = stringResource(MR.strings.pref_incognito_mode_summary),
                            icon = ImageVector.vectorResource(R.drawable.ic_glasses_24dp),
                            shape = pos.shape,
                            onClick = { onIncognitoModeChange(!incognitoMode) },
                            trailing = {
                                Switch(
                                    checked = incognitoMode,
                                    onCheckedChange = onIncognitoModeChange,
                                )
                            },
                        )
                    }
                }
            }

            // Content Group
            item {
                SettingsGroup(title = "Content") {
                    item { pos ->
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
                            shape = pos.shape,
                            onClick = onClickDownloadQueue,
                        )
                    }
                    item { pos ->
                        SettingItem(
                            title = stringResource(MR.strings.categories),
                            icon = Icons.AutoMirrored.Outlined.Label,
                            shape = pos.shape,
                            onClick = onClickCategories,
                        )
                    }
                    item { pos ->
                        SettingItem(
                            title = stringResource(MR.strings.label_stats),
                            icon = Icons.Outlined.QueryStats,
                            shape = pos.shape,
                            onClick = onClickStats,
                        )
                    }
                    item { pos ->
                        SettingItem(
                            title = stringResource(MR.strings.label_data_storage),
                            icon = Icons.Outlined.Storage,
                            shape = pos.shape,
                            onClick = onClickDataAndStorage,
                        )
                    }
                    item { pos ->
                        SettingItem(
                            title = "Sandbox extensions",
                            subtitle = "Install extensions inside the app. No install popups, silent updates.",
                            icon = Icons.Outlined.Security,
                            shape = pos.shape,
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
            }

            // App Group
            item {
                SettingsGroup(title = "App") {
                    item { pos ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = pos.shape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.TouchApp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Switch Button Size",
                                            fontSize = 15.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "${fabSizeDp}dp",
                                            fontSize = 12.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Image(
                                        painter = painterResource(id = eu.kanade.tachiyomi.R.drawable.devil_fruit),
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Slider(
                                    value = fabSizeDp.toFloat(),
                                    onValueChange = { uiPreferences.fabSizeDp.set(it.toInt()) },
                                    valueRange = 48f..96f,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }
                    item { pos ->
                        SettingItem(
                            title = stringResource(MR.strings.label_settings),
                            icon = Icons.Outlined.Settings,
                            shape = pos.shape,
                            onClick = onClickSettings,
                        )
                    }
                    item { pos ->
                        SettingItem(
                            title = stringResource(MR.strings.pref_category_about),
                            icon = Icons.Outlined.Info,
                            shape = pos.shape,
                            onClick = onClickAbout,
                        )
                    }
                    item { pos ->
                        SettingItem(
                            title = stringResource(MR.strings.label_help),
                            icon = Icons.AutoMirrored.Outlined.HelpOutline,
                            shape = pos.shape,
                            onClick = { uriHandler.openUri(Constants.URL_HELP) },
                        )
                    }
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

/** Per-position corner radius — 22dp outer edges, 4dp internal seams. Matches DropSauce. */
fun groupItemShape(index: Int, total: Int): Shape = when {
    total <= 1 -> RoundedCornerShape(22.dp)
    index == 0 -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
    index == total - 1 -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 22.dp, bottomEnd = 22.dp)
    else -> RoundedCornerShape(4.dp)
}

data class GroupItemPosition(val index: Int, val total: Int) {
    val shape: Shape get() = groupItemShape(index, total)
}

class SettingsGroupScope {
    internal val items = mutableListOf<@Composable (GroupItemPosition) -> Unit>()
    fun item(content: @Composable (GroupItemPosition) -> Unit) {
        items += content
    }
}

@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: SettingsGroupScope.() -> Unit,
) {
    val scope = SettingsGroupScope()
    scope.content()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 8.dp),
            )
        }
        val total = scope.items.size
        scope.items.forEachIndexed { i, render ->
            render(GroupItemPosition(index = i, total = total))
            if (i < total - 1) Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
fun SettingItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    shape: Shape = RoundedCornerShape(4.dp),
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
}
