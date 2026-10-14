package eu.kanade.presentation.more.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachIndexed
import eu.kanade.presentation.more.groupItemShape
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import kotlinx.coroutines.delay
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import kotlin.time.Duration.Companion.seconds

/**
 * Preference Screen composable which contains a list of [Preference] items
 * @param items [Preference] items which should be displayed on the preference screen. An item can be a single [PreferenceItem] or a group ([Preference.PreferenceGroup])
 * @param modifier [Modifier] to be applied to the preferenceScreen layout
 */
@Composable
fun PreferenceScreen(
    items: List<Preference>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    title: String? = null,
) {
    val state = rememberLazyListState()
    val highlightKey = SearchableSettings.highlightKey
    if (highlightKey != null) {
        LaunchedEffect(Unit) {
            val i = items.findHighlightedIndex(highlightKey)
            if (i >= 0) {
                delay(0.5.seconds)
                state.animateScrollToItem(i)
            }
            SearchableSettings.highlightKey = null
        }
    }

    ScrollbarLazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
            start = 16.dp,
            end = 16.dp,
        ),
    ) {
        if (title != null) {
            item(key = "pref_screen_header") {
                Text(
                    text = title,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 12.dp),
                )
            }
        }

        items.fastForEachIndexed { i, preference ->
            when (preference) {
                // Create Preference Group
                is Preference.PreferenceGroup -> {
                    if (!preference.enabled) return@fastForEachIndexed
                    val enabledItems = preference.preferenceItems.filter { it.enabled }
                    if (enabledItems.isEmpty()) return@fastForEachIndexed

                    item {
                        Column {
                            PreferenceGroupHeader(title = preference.title)
                        }
                    }
                    itemsIndexed(enabledItems) { itemIndex, item ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = groupItemShape(itemIndex, enabledItems.size),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            PreferenceItem(
                                item = item,
                                highlightKey = highlightKey,
                            )
                        }
                        if (itemIndex < enabledItems.lastIndex) {
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                    }
                    item {
                        if (i < items.lastIndex) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }

                // Create Preference Item
                is Preference.PreferenceItem<*, *> -> {
                    if (!preference.enabled) return@fastForEachIndexed

                    // Determine position among consecutive top-level enabled PreferenceItems
                    var startIdx = i
                    while (startIdx > 0 && items[startIdx - 1] is Preference.PreferenceItem<*, *> && items[startIdx - 1].enabled) {
                        startIdx--
                    }
                    var endIdx = i
                    while (endIdx < items.lastIndex && items[endIdx + 1] is Preference.PreferenceItem<*, *> && items[endIdx + 1].enabled) {
                        endIdx++
                    }
                    val runTotal = endIdx - startIdx + 1
                    val runIndex = i - startIdx

                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = groupItemShape(runIndex, runTotal),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            PreferenceItem(
                                item = preference,
                                highlightKey = highlightKey,
                            )
                        }
                        if (runIndex < runTotal - 1) {
                            Spacer(modifier = Modifier.height(2.dp))
                        } else if (i < items.lastIndex) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun List<Preference>.findHighlightedIndex(highlightKey: String): Int {
    return flatMap {
        if (it is Preference.PreferenceGroup) {
            buildList<String?> {
                add(null) // Header
                addAll(it.preferenceItems.filter { item -> item.enabled }.map { groupItem -> groupItem.title })
                add(null) // Spacer
            }
        } else {
            listOf(it.title)
        }
    }.indexOfFirst { it == highlightKey }
}

