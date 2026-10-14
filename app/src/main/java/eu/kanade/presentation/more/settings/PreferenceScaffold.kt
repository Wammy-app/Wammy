package eu.kanade.presentation.more.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PreferenceScaffold(
    titleRes: StringResource,
    actions: @Composable RowScope.() -> Unit = {},
    onBackPressed: (() -> Unit)? = null,
    itemsProvider: @Composable () -> List<Preference>,
) {
    Scaffold(
        topBarScrollBehavior = androidx.compose.material3.TopAppBarDefaults.enterAlwaysScrollBehavior(),
        topBar = { scrollBehavior ->
            androidx.compose.material3.TopAppBar(
                scrollBehavior = scrollBehavior,
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                title = {},
                navigationIcon = {
                    if (onBackPressed != null) {
                        Box(modifier = Modifier.padding(start = 12.dp)) {
                            Surface(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .clickable(onClick = onBackPressed),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = stringResource(MR.strings.action_bar_up_description),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = actions,
                    )
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        content = { contentPadding ->
            PreferenceScreen(
                items = itemsProvider(),
                contentPadding = contentPadding,
                title = stringResource(titleRes),
            )
        },
    )
}


