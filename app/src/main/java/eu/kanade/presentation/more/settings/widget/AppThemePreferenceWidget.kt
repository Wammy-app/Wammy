package eu.kanade.presentation.more.settings.widget

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType

@Composable
internal fun AppThemePreferenceWidget(
    value: AppTheme,
    amoled: Boolean,
    onItemClick: (AppTheme) -> Unit,
) {
    BasePreferenceWidget(
        subcomponent = {
            AppThemesList(
                currentTheme = value,
                amoled = amoled,
                onItemClick = onItemClick,
            )
        },
    )
}

@Composable
private fun AppThemesList(
    currentTheme: AppTheme,
    amoled: Boolean,
    onItemClick: (AppTheme) -> Unit,
) {
    val context = LocalContext.current
    val appThemes = remember {
        AppTheme.entries
            .filterNot { it.titleRes == null || ((it == AppTheme.EXPRESSIVE || it == AppTheme.MONET) && !DeviceUtil.isDynamicColorAvailable) }
    }
    val spacing = MaterialTheme.padding.small
    val horizontalPadding = PrefsHorizontalPadding

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
    ) {
        val itemWidth = remember(maxWidth) {
            val available = maxWidth - (horizontalPadding * 2) - (spacing * 2)
            (available / 3).coerceAtLeast(88.dp)
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(
                items = appThemes,
                key = { it.name },
            ) { appTheme ->
                Column(
                    modifier = Modifier
                        .width(itemWidth)
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TachiyomiTheme(
                        appTheme = appTheme,
                        amoled = amoled,
                    ) {
                        AppThemePreviewItem(
                            selected = currentTheme == appTheme,
                            onClick = {
                                onItemClick(appTheme)
                                (context as? Activity)?.let { ActivityCompat.recreate(it) }
                            },
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = stringResource(appTheme.titleRes!!),
                        modifier = Modifier
                            .fillMaxWidth()
                            .secondaryItemAlpha(),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
fun AppThemePreviewItem(
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    DividerDefaults.color
                },
                shape = RoundedCornerShape(16.dp),
            )
            .padding(3.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick),
    ) {
        // App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .height(9.dp)
                    .weight(0.7f)
                    .padding(end = 4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        shape = MaterialTheme.shapes.extraSmall,
                    ),
            )

            Box(
                modifier = Modifier.weight(0.3f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = stringResource(MR.strings.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Cover / accent block
        Box(
            modifier = Modifier
                .padding(start = 8.dp, top = 2.dp)
                .size(width = 36.dp, height = 26.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraSmall,
                ),
        ) {
            Row(
                modifier = Modifier
                    .padding(3.dp)
                    .size(width = 20.dp, height = 12.dp)
                    .clip(RoundedCornerShape(3.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.secondary),
                )
            }
        }

        // Bottom bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier
                        .height(20.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .alpha(0.6f)
                            .height(9.dp)
                            .weight(1f)
                            .background(
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = MaterialTheme.shapes.extraSmall,
                            ),
                    )
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun AppThemesListPreview() {
    var appTheme by remember { mutableStateOf(AppTheme.DEFAULT) }
    Injekt.addSingleton(fullType<UiPreferences>(), UiPreferences(InMemoryPreferenceStore()))
    TachiyomiTheme(appTheme = appTheme, seedColor = null) {
        Surface {
            AppThemesList(
                currentTheme = appTheme,
                amoled = false,
                onItemClick = { appTheme = it },
            )
        }
    }
}
