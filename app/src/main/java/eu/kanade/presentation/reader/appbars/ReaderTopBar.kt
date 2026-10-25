package eu.kanade.presentation.reader.appbars

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderTopBar(
    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showControlSheet by remember { mutableStateOf(false) }

    AppBar(
        modifier = modifier,
        backgroundColor = Color.Transparent,
        title = mangaTitle,
        subtitle = chapterTitle,
        navigateUp = navigateUp,
        actions = {
            AppBarActions(
                actions = listOf(
                    AppBar.Action(
                        title = stringResource(
                            if (bookmarked) {
                                MR.strings.action_remove_bookmark
                            } else {
                                MR.strings.action_bookmark
                            },
                        ),
                        icon = if (bookmarked) {
                            Icons.Outlined.Bookmark
                        } else {
                            Icons.Outlined.BookmarkBorder
                        },
                        onClick = onToggleBookmarked,
                    ),
                    AppBar.Action(
                        title = stringResource(MR.strings.action_menu_overflow_description),
                        icon = Icons.Outlined.MoreVert,
                        onClick = { showControlSheet = true },
                    ),
                ),
            )
        },
    )

    if (showControlSheet) {
        ReaderControlSheet(
            onDismissRequest = { showControlSheet = false },
            readingMode = readingMode,
            onClickReadingMode = {
                showControlSheet = false
                onClickReadingMode()
            },
            orientation = orientation,
            onClickOrientation = {
                showControlSheet = false
                onClickOrientation()
            },
            cropEnabled = cropEnabled,
            onClickCropBorder = onClickCropBorder,
            onClickSettings = {
                showControlSheet = false
                onClickSettings()
            },
            onOpenInWebView = onOpenInWebView?.let { action ->
                {
                    showControlSheet = false
                    action()
                }
            },
            onOpenInBrowser = onOpenInBrowser?.let { action ->
                {
                    showControlSheet = false
                    action()
                }
            },
            onShare = onShare?.let { action ->
                {
                    showControlSheet = false
                    action()
                }
            },
        )
    }
}
