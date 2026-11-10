package eu.kanade.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMaxBy
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

val DownloadedOnlyBannerBackgroundColor
    @Composable get() = MaterialTheme.colorScheme.tertiary
val IncognitoModeBannerBackgroundColor
    @Composable get() = MaterialTheme.colorScheme.primary
val IndexingBannerBackgroundColor
    @Composable get() = MaterialTheme.colorScheme.secondary

@Composable
fun WarningBanner(
    textRes: StringResource,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(textRes),
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error)
            .padding(16.dp),
        color = MaterialTheme.colorScheme.onError,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}

@Composable
fun AppStateBanners(
    downloadedOnlyMode: Boolean,
    incognitoMode: Boolean,
    indexing: Boolean,
    restoring: BackupNotifier.RestoreProgressState? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val mainInsets = WindowInsets.statusBars
    val mainInsetsTop = mainInsets.getTop(density)
    var lastRestoring by remember { mutableStateOf(restoring) }
    if (restoring != null) {
        lastRestoring = restoring
    }

    SubcomposeLayout(modifier = modifier) { constraints ->
        val restoringPlaceable = subcompose(0) {
            AnimatedVisibility(
                visible = restoring != null,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                lastRestoring?.let {
                    RestoringBackupBanner(
                        restoring = it,
                        modifier = Modifier.windowInsetsPadding(mainInsets),
                    )
                }
            }
        }.fastMap { it.measure(constraints) }
        val restoringHeight = restoringPlaceable.fastMaxBy { it.height }?.height ?: 0

        val indexingPlaceable = subcompose(1) {
            AnimatedVisibility(
                visible = indexing,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                val top = (mainInsetsTop - restoringHeight).coerceAtLeast(0)
                IndexingDownloadBanner(
                    modifier = Modifier.windowInsetsPadding(WindowInsets(top = top)),
                )
            }
        }.fastMap { it.measure(constraints) }
        val indexingHeight = indexingPlaceable.fastMaxBy { it.height }?.height ?: 0

        val downloadedOnlyPlaceable = subcompose(2) {
            AnimatedVisibility(
                visible = downloadedOnlyMode,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                val top = (mainInsetsTop - restoringHeight - indexingHeight).coerceAtLeast(0)
                DownloadedOnlyModeBanner(
                    modifier = Modifier.windowInsetsPadding(WindowInsets(top = top)),
                )
            }
        }.fastMap { it.measure(constraints) }
        val downloadedOnlyHeight = downloadedOnlyPlaceable.fastMaxBy { it.height }?.height ?: 0

        val incognitoPlaceable = subcompose(3) {
            AnimatedVisibility(
                visible = incognitoMode,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                val top = (mainInsetsTop - restoringHeight - indexingHeight - downloadedOnlyHeight).coerceAtLeast(0)
                IncognitoModeBanner(
                    modifier = Modifier.windowInsetsPadding(WindowInsets(top = top)),
                )
            }
        }.fastMap { it.measure(constraints) }
        val incognitoHeight = incognitoPlaceable.fastMaxBy { it.height }?.height ?: 0

        layout(constraints.maxWidth, restoringHeight + indexingHeight + downloadedOnlyHeight + incognitoHeight) {
            restoringPlaceable.fastForEach {
                it.place(0, 0)
            }
            indexingPlaceable.fastForEach {
                it.place(0, restoringHeight)
            }
            downloadedOnlyPlaceable.fastForEach {
                it.place(0, restoringHeight + indexingHeight)
            }
            incognitoPlaceable.fastForEach {
                it.place(0, restoringHeight + indexingHeight + downloadedOnlyHeight)
            }
        }
    }
}

@Composable
private fun DownloadedOnlyModeBanner(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(MR.strings.label_downloaded_only),
        modifier = Modifier
            .background(DownloadedOnlyBannerBackgroundColor)
            .fillMaxWidth()
            .padding(4.dp)
            .then(modifier),
        color = MaterialTheme.colorScheme.onTertiary,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun IncognitoModeBanner(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(MR.strings.pref_incognito_mode),
        modifier = Modifier
            .background(IncognitoModeBannerBackgroundColor)
            .fillMaxWidth()
            .padding(4.dp)
            .then(modifier),
        color = MaterialTheme.colorScheme.onPrimary,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun RestoringBackupBanner(
    restoring: BackupNotifier.RestoreProgressState,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(if (restoring.isSync) MR.strings.syncing_library else MR.strings.restoring_backup)
    LoadingBanner(
        text = formatRestoreBannerText(label, restoring.progress, restoring.maxAmount),
        modifier = modifier,
    )
}

@Composable
private fun IndexingDownloadBanner(modifier: Modifier = Modifier) {
    LoadingBanner(
        text = stringResource(MR.strings.download_notifier_cache_renewal),
        modifier = modifier,
    )
}

@Composable
private fun LoadingBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Row(
        modifier = Modifier
            .background(color = IndexingBannerBackgroundColor)
            .fillMaxWidth()
            .padding(8.dp)
            .then(modifier),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var textHeight by remember { mutableStateOf(0.dp) }
        CircularProgressIndicator(
            modifier = Modifier.requiredSize(textHeight),
            color = MaterialTheme.colorScheme.onSecondary,
            strokeWidth = textHeight / 8,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSecondary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            onTextLayout = {
                with(density) {
                    textHeight = it.size.height.toDp()
                }
            },
        )
    }
}

internal fun formatRestoreBannerText(
    label: String,
    progress: Int,
    maxAmount: Int,
): String {
    return if (progress > 0 && maxAmount > 0) {
        "$label ($progress/$maxAmount)"
    } else {
        "$label..."
    }
}
