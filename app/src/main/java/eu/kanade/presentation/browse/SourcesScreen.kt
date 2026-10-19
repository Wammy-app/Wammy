package eu.kanade.presentation.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import eu.kanade.tachiyomi.source.builtin.BuiltinSourceManager
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.browse.components.SourceIcon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.BaseSourceItem
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.browse.source.SourcesViewModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.Listing
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.plus
import tachiyomi.source.local.isLocal
import tachiyomi.source.local.isLocalNovel

@Composable
fun SourcesScreen(
    state: SourcesViewModel.State,
    contentPadding: PaddingValues,
    onClickItem: (Source, Listing) -> Unit,
    onClickPin: (Source) -> Unit,
    onLongClickPin: (Source) -> Unit,
    onRemoveFromGroup: (Source, String) -> Unit,
    onLongClickItem: (Source) -> Unit,
    onClickUninstall: (Source) -> Unit = {},
) {
    when {
        state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
        state.isEmpty -> EmptyScreen(
            stringRes = MR.strings.source_empty_screen,
            modifier = Modifier.padding(contentPadding),
        )
        else -> {
            ScrollbarLazyColumn(
                contentPadding = contentPadding + topSmallPaddingValues,
            ) {
                if (state.builtinSources.isNotEmpty()) {
                    item(key = "builtin-sources-header") {
                        BuiltinSourcesHeader(
                            count = state.builtinSources.size,
                            modifier = Modifier.animateItem(),
                        )
                    }

                    item(key = "builtin-sources-grid") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.builtinSources.chunked(4).forEach { rowSources ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    rowSources.forEach { source ->
                                        BuiltinSourceCard(
                                            source = source,
                                            modifier = Modifier.weight(1f),
                                            onClickItem = onClickItem,
                                            onLongClickItem = onLongClickItem,
                                        )
                                    }
                                    repeat(4 - rowSources.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    if (state.items.isNotEmpty()) {
                        item(key = "builtin-sources-divider") {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                        }
                    }
                }

                items(
                    items = state.items,
                    contentType = {
                        when (it) {
                            is SourceUiModel.Header -> "header"
                            is SourceUiModel.Item -> "item"
                        }
                    },
                    key = {
                        when (it) {
                            is SourceUiModel.Header -> it.hashCode()
                            is SourceUiModel.Item -> "source-${it.sectionKey}-${it.source.key()}"
                        }
                    },
                ) { model ->
                    when (model) {
                        is SourceUiModel.Header -> {
                            SourceHeader(
                                modifier = Modifier.animateItem(),
                                language = model.language,
                                isGroup = model.isGroup,
                            )
                        }
                        is SourceUiModel.Item -> SourceItem(
                            modifier = Modifier.animateItem(),
                            source = model.source,
                            hasDefaultPreset = model.hasDefaultPreset,
                            groupSection = model.sectionKey.ifEmpty { null },
                            onClickItem = onClickItem,
                            onLongClickItem = onLongClickItem,
                            onClickPin = onClickPin,
                            onLongClickPin = onLongClickPin,
                            onRemoveFromGroup = onRemoveFromGroup,
                            onClickUninstall = onClickUninstall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltinSourcesHeader(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Wammy Inbuilt Sources",
            style = MaterialTheme.typography.header,
        )
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
        ) {
            Text(
                text = "$count",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun BuiltinSourceCard(
    source: Source,
    modifier: Modifier = Modifier,
    onClickItem: (Source, Listing) -> Unit,
    onLongClickItem: (Source) -> Unit,
) {
    val isPinned = Pin.Pinned in source.pin
    val iconRes = BuiltinSourceManager.getIconRes(source.id, source.name)
    val isNovel = BuiltinSourceManager.isNovel(source.id)
    val cardSize = if (isNovel) 46.dp else 52.dp

    Box(
        modifier = modifier.padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(cardSize)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = { onClickItem(source, Listing.Popular) },
                    onLongClick = { onLongClickItem(source) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (iconRes != null) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = source.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                SourceIcon(
                    source = source,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp)),
                )
            }

            if (isPinned) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(16.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = stringResource(MR.strings.action_pin),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceHeader(
    language: String,
    modifier: Modifier = Modifier,
    isGroup: Boolean = false,
) {
    val context = LocalContext.current
    Text(
        text = if (isGroup) language else LocaleHelper.getSourceDisplayName(language, context),
        modifier = modifier
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        style = MaterialTheme.typography.header,
    )
}

@Composable
private fun SourceItem(
    source: Source,
    hasDefaultPreset: Boolean,
    groupSection: String?,
    onClickItem: (Source, Listing) -> Unit,
    onLongClickItem: (Source) -> Unit,
    onClickPin: (Source) -> Unit,
    onLongClickPin: (Source) -> Unit,
    onRemoveFromGroup: (Source, String) -> Unit,
    onClickUninstall: (Source) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canUninstall = !source.isLocal() && !source.isLocalNovel()
    BaseSourceItem(
        modifier = modifier,
        source = source,
        onClickItem = { onClickItem(source, Listing.Popular) },
        onLongClickItem = { onLongClickItem(source) },
        action = {
            if (hasDefaultPreset) {
                IconButton(onClick = { onClickItem(source, Listing.Search(query = null, filters = FilterList())) }) {
                    Icon(
                        imageVector = Icons.Outlined.FilterList,
                        tint = MaterialTheme.colorScheme.primary,
                        contentDescription = stringResource(MR.strings.action_filter),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourcePinButton(
                    source = source,
                    groupSection = groupSection,
                    onClickPin = onClickPin,
                    onLongClickPin = onLongClickPin,
                    onRemoveFromGroup = onRemoveFromGroup,
                )
                if (canUninstall) {
                    VerticalDivider(
                        modifier = Modifier
                            .height(20.dp)
                            .padding(horizontal = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 1.dp,
                    )
                    IconButton(
                        onClick = { onClickUninstall(source) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            contentDescription = stringResource(MR.strings.ext_uninstall),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun SourcePinButton(
    source: Source,
    groupSection: String?,
    onClickPin: (Source) -> Unit,
    onLongClickPin: (Source) -> Unit,
    onRemoveFromGroup: (Source, String) -> Unit,
) {
    val isPinned = Pin.Pinned in source.pin
    val isInGroup = source.pinnedGroups.isNotEmpty()
    val showGroupIcon = groupSection != null || (isInGroup && !isPinned)

    val icon = when {
        showGroupIcon -> Icons.AutoMirrored.Filled.Label
        isPinned -> Icons.Filled.PushPin
        else -> Icons.Outlined.PushPin
    }
    val tint = when {
        showGroupIcon -> MaterialTheme.colorScheme.secondary
        isPinned -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
    val description = when {
        groupSection != null -> MR.strings.action_remove
        showGroupIcon -> TDMR.strings.action_pin_groups
        isPinned -> MR.strings.action_unpin
        else -> MR.strings.action_pin
    }
    // In a group section: remove from that group; grouped elsewhere: open dialog; else: toggle pin.
    val onClick: () -> Unit = when {
        groupSection != null -> {
            { onRemoveFromGroup(source, groupSection) }
        }
        showGroupIcon -> {
            { onLongClickPin(source) }
        }
        else -> {
            { onClickPin(source) }
        }
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { onLongClickPin(source) },
                role = androidx.compose.ui.semantics.Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            tint = tint,
            contentDescription = stringResource(description),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun SourceOptionsDialog(
    source: Source,
    onClickPin: () -> Unit,
    onClickPinGroups: () -> Unit,
    onClickDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = source.visualName)
        },
        text = {
            Column {
                val textId = if (Pin.Pinned in source.pin) MR.strings.action_unpin else MR.strings.action_pin
                Text(
                    text = stringResource(textId),
                    modifier = Modifier
                        .clickable(onClick = onClickPin)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
                Text(
                    text = stringResource(TDMR.strings.action_pin_groups),
                    modifier = Modifier
                        .clickable(onClick = onClickPinGroups)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
                if (!source.isLocal()) {
                    Text(
                        text = stringResource(MR.strings.action_disable),
                        modifier = Modifier
                            .clickable(onClick = onClickDisable)
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {},
    )
}

@Composable
fun SourcePinGroupsDialog(
    source: Source,
    pinGroups: Pair<List<String>, List<Boolean>>,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (initialGroups, initialSelections) = pinGroups
    val groups = remember {
        mutableStateListOf<String>().apply { addAll(initialGroups) }
    }
    val selectedIndices = remember {
        mutableStateListOf<Boolean>().apply { addAll(initialSelections) }
    }
    var pinned by remember { mutableStateOf(isPinned) }
    var newGroupName by remember { mutableStateOf("") }
    var createNewGroup by remember { mutableStateOf(false) }
    var groupPendingDelete by remember { mutableStateOf<String?>(null) }

    groupPendingDelete?.let { group ->
        AlertDialog(
            onDismissRequest = { groupPendingDelete = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteGroup(group)
                        val index = groups.indexOf(group)
                        if (index != -1) {
                            groups.removeAt(index)
                            selectedIndices.removeAt(index)
                        }
                        groupPendingDelete = null
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { groupPendingDelete = null }) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
            title = {
                Text(text = stringResource(TDMR.strings.delete_pin_group))
            },
            text = {
                Text(text = stringResource(TDMR.strings.delete_pin_group_confirmation, group))
            },
        )
    }

    AlertDialog(
        title = {
            Column {
                Text(text = source.visualName)
                Text(
                    text = stringResource(TDMR.strings.action_pin_groups),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            pinned = !pinned
                            onTogglePin()
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = null,
                        tint = if (pinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = stringResource(if (pinned) MR.strings.action_unpin else MR.strings.action_pin),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                groups.forEachIndexed { index, group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedIndices[index] = !selectedIndices[index]
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selectedIndices[index],
                            onCheckedChange = null,
                        )
                        Text(
                            text = group,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        )
                        IconButton(
                            onClick = { groupPendingDelete = group },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(MR.strings.action_delete),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { createNewGroup = !createNewGroup }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = createNewGroup,
                        onCheckedChange = null,
                    )
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = {
                            val sanitized = it.replace("|", "")
                            newGroupName = sanitized
                            createNewGroup = sanitized.isNotBlank()
                        },
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .fillMaxWidth(),
                        placeholder = { Text(text = stringResource(TDMR.strings.action_add_pin_group)) },
                        singleLine = true,
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val finalGroups = groups
                        .filterIndexed { index, _ -> selectedIndices[index] }
                        .toMutableSet()

                    if (createNewGroup && newGroupName.isNotBlank()) {
                        finalGroups.add(newGroupName.trim())
                    }
                    onConfirm(finalGroups)
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

sealed interface SourceUiModel {
    data class Item(
        val source: Source,
        val sectionKey: String = "",
        val hasDefaultPreset: Boolean = false,
    ) : SourceUiModel
    data class Header(
        val language: String,
        val isGroup: Boolean = false,
    ) : SourceUiModel
}
