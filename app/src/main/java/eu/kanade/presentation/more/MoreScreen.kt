package eu.kanade.presentation.more

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.firebase.auth.FirebaseUser
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = LocalUriHandler.current
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val fabSizeDp by uiPreferences.fabSizeDp.collectAsState()
    var showSandboxDialog by remember { mutableStateOf(false) }

    val authManager = remember { Injekt.get<eu.kanade.tachiyomi.data.auth.AuthManager>() }
    val user: FirebaseUser? by authManager.currentUser.collectAsState()
    val userPrefs = remember { context.getSharedPreferences("user_profile_cache", android.content.Context.MODE_PRIVATE) }
    var username by remember {
        mutableStateOf<String?>(userPrefs.getString("cached_username", null) ?: user?.displayName)
    }

    LaunchedEffect(user?.uid) {
        val uid = user?.uid
        if (uid != null) {
            val repo = eu.kanade.tachiyomi.data.sync.FirestoreUserRepository()
            val cloudUser = tachiyomi.core.common.util.lang.withIOContext { repo.getUser(uid) }
            val fetchedName: String? = cloudUser?.username?.takeIf { it.isNotBlank() } ?: user?.displayName?.takeIf { it.isNotBlank() }
            if (fetchedName != null) {
                username = fetchedName
                userPrefs.edit().putString("cached_username", fetchedName).apply()
            }
        }
    }

    Scaffold { contentPadding ->
        ScrollbarLazyColumn(contentPadding = contentPadding + PaddingValues(bottom = 84.dp)) {
            item {
                LogoHeader(
                    iconPadding = PaddingValues(top = 20.dp, bottom = 2.dp),
                )
            }

            item {
                ShareCommunityCard(username = username)
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

            // GitHub Sponsors Button
            item {
                Spacer(modifier = Modifier.height(4.dp))
                FilledTonalButton(
                    onClick = { uriHandler.openUri("https://github.com/sponsors/kainotch") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    contentPadding = PaddingValues(vertical = 13.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        tint = Color(0xFFEA4C89),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sponsor",
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // GitHub Star Footer Callout
            item {
                Spacer(modifier = Modifier.height(8.dp))
                val gitHubUrl = "https://github.com/kainotch/Wammy"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri(gitHubUrl) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    val primaryColor = MaterialTheme.colorScheme.primary
                    val starText = remember(primaryColor, gitHubUrl) {
                        buildAnnotatedString {
                            append("Enjoying the app? Star it on ")
                            withLink(
                                LinkAnnotation.Url(
                                    url = gitHubUrl,
                                    styles = TextLinkStyles(
                                        style = SpanStyle(
                                            color = primaryColor,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                    ),
                                    linkInteractionListener = {
                                        uriHandler.openUri(gitHubUrl)
                                    },
                                ),
                            ) {
                                append("GitHub")
                            }
                            append(" — it helps other readers find it.")
                        }
                    }
                    Text(
                        text = starText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
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

@Composable
private fun ShareCommunityCard(
    username: String?,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "arrowRotation",
    )
    val name = username?.takeIf { it.isNotBlank() } ?: "reader"

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(
            onClick = { isExpanded = !isExpanded },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse message" else "Expand message",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier
                    .size(24.dp)
                    .rotate(rotation),
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val annotatedText = buildAnnotatedString {
                    append("hey ")
                    pushStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    append("@$name")
                    pop()
                    append(", ngl it takes a lot of effort on my end to make regular updates. how about a small act of kindness and share this with others so it keeps me motivated to keep building.")
                }

                Text(
                    text = annotatedText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(0.92f),
                )

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Check out Wammy - the ultimate Manga & Light Novel reader! https://kainotch.github.io/WammyWeb/",
                            )
                        }
                        val shareIntent = Intent.createChooser(sendIntent, "Share Wammy")
                        context.startActivity(shareIntent)
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "Share",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
