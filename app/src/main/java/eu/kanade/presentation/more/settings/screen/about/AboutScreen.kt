package eu.kanade.presentation.more.settings.screen.about

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.SettingItem
import eu.kanade.presentation.more.SettingsGroup
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.data.updater.RELEASE_URL
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.lang.toDateTimestampString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.isNightlyBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logcat.LogPriority
import org.jsoup.Jsoup
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.icons.CustomIcons
import tachiyomi.presentation.core.icons.Discord
import tachiyomi.presentation.core.icons.Github
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Instant

object AboutScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val handleBack = LocalBackPress.current
        val navigator = LocalNavigator.currentOrThrow
        var isCheckingUpdates by remember { mutableStateOf(false) }

        val fullVersionString = remember { getVersionName(withBuildDate = true) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "About",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (handleBack != null) handleBack() else navigator.pop()
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { paddingValues ->
            val bannerBgColor = MaterialTheme.colorScheme.primaryContainer
            val blobColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            val bannerContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            val chipBgColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                // 1. Top Decorative Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(bannerBgColor),
                ) {
                    // Decorative organic blob background
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier.matchParentSize(),
                    ) {
                        // Top-left curved droplet
                        val topLeftPath = Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width * 0.35f, 0f)
                            cubicTo(
                                size.width * 0.32f, size.height * 0.32f,
                                size.width * 0.16f, size.height * 0.44f,
                                0f, size.height * 0.36f,
                            )
                            close()
                        }
                        drawPath(topLeftPath, blobColor)

                        // Bottom-left organic wave
                        val bottomLeftPath = Path().apply {
                            moveTo(0f, size.height * 0.62f)
                            cubicTo(
                                size.width * 0.18f, size.height * 0.58f,
                                size.width * 0.32f, size.height * 0.82f,
                                size.width * 0.26f, size.height,
                            )
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(bottomLeftPath, blobColor)

                        // Right side cloud/flower scalloped shape
                        val rightCenter = Offset(size.width * 0.96f, size.height * 0.50f)
                        drawCircle(color = blobColor, radius = size.width * 0.22f, center = rightCenter)
                        drawCircle(color = blobColor, radius = size.width * 0.15f, center = Offset(size.width * 0.88f, size.height * 0.24f))
                        drawCircle(color = blobColor, radius = size.width * 0.15f, center = Offset(size.width * 0.88f, size.height * 0.76f))
                        drawCircle(color = blobColor, radius = size.width * 0.14f, center = Offset(size.width * 0.74f, size.height * 0.48f))
                    }

                    // Content inside banner
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // App squircle icon (Square with rounded edges)
                        Surface(
                            modifier = Modifier.size(76.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 4.dp,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.wammy_logo_more),
                                contentDescription = "Wammy App Icon",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(20.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // App Name
                        Text(
                            text = "Wammy",
                            color = bannerContentColor,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp,
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Horizontal stadium chips
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Chip 1: Version Pill
                            Surface(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        val deviceInfo = CrashLogUtil(context).getDebugInfo()
                                        context.copyToClipboard("Debug information", deviceInfo)
                                        context.toast("Copied device & debug info!")
                                    },
                                shape = CircleShape,
                                color = chipBgColor,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Info,
                                        contentDescription = null,
                                        tint = bannerContentColor.copy(alpha = 0.9f),
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "v${BuildConfig.VERSION_NAME.ifBlank { "0.9.9" }}",
                                        color = bannerContentColor,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }

                            // Chip 2: Author / GitHub Pill
                            Surface(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        uriHandler.openUri("https://github.com/kainotch")
                                    },
                                shape = CircleShape,
                                color = chipBgColor,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = CustomIcons.Github,
                                        contentDescription = null,
                                        tint = bannerContentColor.copy(alpha = 0.9f),
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "kainotch",
                                        color = bannerContentColor,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2. Section: UPDATES
                SettingsGroup(title = "Updates") {
                    item { pos ->
                        SettingItem(
                            title = "Check for updates",
                            subtitle = "Version ${BuildConfig.VERSION_NAME.ifBlank { "0.9.9" }}",
                            icon = Icons.Outlined.SystemUpdate,
                            shape = pos.shape,
                            onClick = if (!isCheckingUpdates) {
                                {
                                    scope.launch {
                                        isCheckingUpdates = true
                                        checkVersion(
                                            context = context,
                                            onAvailableUpdate = { result ->
                                                val updateScreen = NewUpdateScreen(
                                                    versionName = result.release.version,
                                                    changelogInfo = result.release.info,
                                                    releaseLink = result.release.releaseLink,
                                                    downloadLink = result.release.downloadLink,
                                                )
                                                navigator.push(updateScreen)
                                            },
                                            onFinish = {
                                                isCheckingUpdates = false
                                            },
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            trailing = if (isCheckingUpdates) {
                                {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.dp,
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                    item { pos ->
                        SettingItem(
                            title = "Changelog",
                            subtitle = "Changes history for recently released versions",
                            icon = Icons.Outlined.History,
                            shape = pos.shape,
                            onClick = { uriHandler.openUri(RELEASE_URL) },
                        )
                    }
                }

                // 3. Section: LINKS
                SettingsGroup(title = "Links") {
                    item { pos ->
                        SettingItem(
                            title = "User manual",
                            subtitle = "https://kainotch.github.io/WammyWeb/",
                            icon = Icons.Outlined.MenuBook,
                            shape = pos.shape,
                            onClick = { uriHandler.openUri("https://kainotch.github.io/WammyWeb/") },
                        )
                    }
                    item { pos ->
                        SettingItem(
                            title = "Source code",
                            subtitle = "https://github.com/Wammy-app/Wammy",
                            icon = Icons.Outlined.OpenInNew,
                            shape = pos.shape,
                            onClick = { uriHandler.openUri("https://github.com/Wammy-app/Wammy") },
                        )
                    }
                    item { pos ->
                        SettingItem(
                            title = "Discord",
                            subtitle = "https://discord.gg/qybFSATs7Q",
                            icon = CustomIcons.Discord,
                            shape = pos.shape,
                            onClick = { uriHandler.openUri("https://discord.gg/qybFSATs7Q") },
                        )
                    }
                }

                // 4. Section: CREATORS
                SettingsGroup(title = "Creators") {
                    item { pos ->
                        InstagramCardRow(
                            name = "Developer",
                            username = "kainotch",
                            shape = pos.shape,
                            onClick = { uriHandler.openUri("https://instagram.com/kainotch") },
                        )
                    }
                    item { pos ->
                        InstagramCardRow(
                            name = "Partner",
                            username = "xo._kiwikaffine",
                            shape = pos.shape,
                            onClick = { uriHandler.openUri("https://instagram.com/xo._kiwikaffine") },
                        )
                    }
                }

                // 5. Section: DIAGNOSTICS
                SettingsGroup(title = "Diagnostics") {
                    item { pos ->
                        SettingItem(
                            title = "Debug information",
                            subtitle = "Copy build & device specs ($fullVersionString)",
                            icon = Icons.Outlined.BugReport,
                            shape = pos.shape,
                            onClick = {
                                val deviceInfo = CrashLogUtil(context).getDebugInfo()
                                context.copyToClipboard("Debug information", deviceInfo)
                                context.toast("Debug information copied to clipboard!")
                            },
                        )
                    }
                    item { pos ->
                        SettingItem(
                            title = "Open source licenses",
                            subtitle = "Third-party libraries used in Wammy",
                            icon = Icons.Outlined.Article,
                            shape = pos.shape,
                            onClick = { navigator.push(OpenSourceLicensesScreen()) },
                        )
                    }
                    item { pos ->
                        SettingItem(
                            title = "Privacy policy",
                            subtitle = "Read how your library & cloud data are safeguarded",
                            icon = Icons.Outlined.Shield,
                            shape = pos.shape,
                            onClick = { uriHandler.openUri("https://kainotch.github.io/WammyWeb/") },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    /**
     * Checks version and shows a user prompt if an update is available.
     */
    private suspend fun checkVersion(
        context: Context,
        onAvailableUpdate: (GetApplicationRelease.Result.NewUpdate) -> Unit,
        onFinish: () -> Unit,
    ) {
        val updateChecker = AppUpdateChecker()
        withUIContext {
            try {
                when (val result = withIOContext { updateChecker.checkForUpdate(forceCheck = true) }) {
                    is GetApplicationRelease.Result.NewUpdate -> {
                        onAvailableUpdate(result)
                    }
                    is GetApplicationRelease.Result.NoNewUpdate -> {
                        context.toast(MR.strings.update_check_no_new_updates)
                    }
                    is GetApplicationRelease.Result.OsTooOld -> {
                        context.toast(MR.strings.update_check_eol)
                    }
                }
            } catch (e: Exception) {
                context.toast(e.message)
                logcat(LogPriority.ERROR, e)
            } finally {
                onFinish()
            }
        }
    }

    fun getVersionName(withBuildDate: Boolean): String {
        return when {
            BuildConfig.DEBUG -> {
                "Debug ${BuildConfig.COMMIT_SHA}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
            isNightlyBuildType -> {
                "Nightly r${BuildConfig.COMMIT_COUNT}".let {
                    if (withBuildDate) {
                        "$it (${BuildConfig.COMMIT_SHA}, ${getFormattedBuildTime()})"
                    } else {
                        "$it (${BuildConfig.COMMIT_SHA})"
                    }
                }
            }
            isPreviewBuildType -> {
                "Beta r${BuildConfig.COMMIT_COUNT}".let {
                    if (withBuildDate) {
                        "$it (${BuildConfig.COMMIT_SHA}, ${getFormattedBuildTime()})"
                    } else {
                        "$it (${BuildConfig.COMMIT_SHA})"
                    }
                }
            }
            else -> {
                "Stable ${BuildConfig.VERSION_NAME}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
        }
    }

    internal fun getFormattedBuildTime(): String {
        return try {
            Instant.parse(BuildConfig.BUILD_TIME)
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .toDateTimestampString(
                    UiPreferences.dateFormat(
                        Injekt.get<UiPreferences>().dateFormat.get(),
                    ),
                )
        } catch (_: Exception) {
            BuildConfig.BUILD_TIME
        }
    }
}

@Composable
fun InstagramCardRow(
    name: String,
    username: String,
    shape: Shape = RoundedCornerShape(4.dp),
    onClick: () -> Unit,
) {
    val imageUrl = remember { mutableStateOf("") }

    LaunchedEffect(username) {
        withContext(Dispatchers.IO) {
            try {
                val doc = Jsoup.connect("https://www.instagram.com/$username/").userAgent("WhatsApp/2.21.12.21 A").get()
                val url = doc.select("meta[property=og:image]").attr("content")
                if (url.isNotEmpty()) {
                    imageUrl.value = url.replace("&amp;", "&")
                }
            } catch (e: Exception) {
                // Silently fallback
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (imageUrl.value.isNotEmpty()) {
                AsyncImage(
                    model = imageUrl.value,
                    contentDescription = null,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = name.take(1),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "Instagram (@$username)",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

