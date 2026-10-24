package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.connection.components.ConnectionLogoIcon
import eu.kanade.presentation.more.settings.LocalPreferenceHighlighted
import eu.kanade.tachiyomi.data.connections.ConnectionsService
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

@Composable
fun ConnectionPreferenceWidget(
    service: ConnectionsService,
    checked: Boolean,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val highlighted = LocalPreferenceHighlighted.current
    Box(modifier = Modifier.highlightBackground(highlighted)) {
        Row(
            modifier = modifier
                .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
                .fillMaxWidth()
                .padding(horizontal = PrefsHorizontalPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionLogoIcon(service)
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(service.nameStrRes()),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    maxLines = 1,
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = TitleFontSize,
                    fontWeight = FontWeight.Medium,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .secondaryItemAlpha(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (checked) {
                Icon(
                    imageVector = Icons.Outlined.Done,
                    modifier = Modifier
                        .padding(4.dp)
                        .size(32.dp),
                    tint = Color(0xFF4CAF50),
                    contentDescription = stringResource(MR.strings.login_success),
                )
            }
        }
    }
}
