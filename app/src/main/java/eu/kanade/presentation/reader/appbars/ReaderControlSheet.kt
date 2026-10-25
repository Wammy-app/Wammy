package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderControlSheet(
    onDismissRequest: () -> Unit,
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cardBackground = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
    val cardShape = RoundedCornerShape(20.dp)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Row 1: 3 rounded square cards (Reading mode, Rotation, Crop borders)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Card 1: Reading Mode
                ReaderSquareCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.viewer),
                    subtitle = stringResource(readingMode.stringRes),
                    cardShape = cardShape,
                    cardBackground = cardBackground,
                    onClick = onClickReadingMode,
                    iconContent = {
                        Icon(
                            painter = painterResource(readingMode.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                        )
                    },
                )

                // Card 2: Rotation / Orientation
                ReaderSquareCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.rotation_type),
                    subtitle = stringResource(orientation.stringRes),
                    cardShape = cardShape,
                    cardBackground = cardBackground,
                    onClick = onClickOrientation,
                    iconContent = {
                        Icon(
                            imageVector = orientation.icon,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                        )
                    },
                )

                // Card 3: Crop borders
                ReaderSquareCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.pref_crop_borders),
                    subtitle = stringResource(if (cropEnabled) MR.strings.on else MR.strings.off),
                    cardShape = cardShape,
                    cardBackground = cardBackground,
                    onClick = onClickCropBorder,
                    iconContent = {
                        Icon(
                            painter = painterResource(if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp),
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                        )
                    },
                )
            }

            // Row 2: 2 wide rounded rectangular cards (Open in WebView, Open in Browser)
            if (onOpenInWebView != null || onOpenInBrowser != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (onOpenInWebView != null) {
                        ReaderRectCard(
                            modifier = Modifier.weight(1f),
                            title = stringResource(MR.strings.action_open_in_web_view),
                            imageVector = Icons.Outlined.Public,
                            cardShape = cardShape,
                            cardBackground = cardBackground,
                            onClick = onOpenInWebView,
                        )
                    }
                    if (onOpenInBrowser != null) {
                        ReaderRectCard(
                            modifier = Modifier.weight(1f),
                            title = stringResource(MR.strings.action_open_in_browser),
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            cardShape = cardShape,
                            cardBackground = cardBackground,
                            onClick = onOpenInBrowser,
                        )
                    }
                }
            }

            // Row 3: Wide Settings banner + Square Share card
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Wide Settings Banner
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(76.dp)
                        .clip(cardShape)
                        .background(cardBackground)
                        .clickable(onClick = onClickSettings)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(MR.strings.action_settings),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Advanced reader options",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Square Share Card
                if (onShare != null) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(cardShape)
                            .background(cardBackground)
                            .clickable(onClick = onShare),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(MR.strings.action_share),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderSquareCard(
    title: String,
    subtitle: String,
    cardShape: RoundedCornerShape,
    cardBackground: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(96.dp)
            .clip(cardShape)
            .background(cardBackground)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            iconContent()
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReaderRectCard(
    title: String,
    imageVector: ImageVector,
    cardShape: RoundedCornerShape,
    cardBackground: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(72.dp)
            .clip(cardShape)
            .background(cardBackground)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
