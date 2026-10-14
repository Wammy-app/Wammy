package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import eu.kanade.presentation.util.LocalBackPress
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

interface SearchableSettings : Screen {

    @Composable
    @ReadOnlyComposable
    fun getTitleRes(): StringResource

    @Composable
    fun getPreferences(): List<Preference>

    /**
     * Whether this screen supports per-screen reset.
     * Override to return true to show a reset button in the app bar.
     */
    val supportsReset: Boolean get() = false

    /**
     * Override to provide additional preferences to reset that aren't automatically
     * detected from the preference list (e.g., slider preferences with no backing
     * preference field, custom preferences, etc.).
     */
    @Composable
    fun getAdditionalResetPreferences(): List<tachiyomi.core.common.preference.Preference<*>> = emptyList()

    @Composable
    fun RowScope.AppBarAction() {
        if (supportsReset) {
            var showResetDialog by remember { mutableStateOf(false) }
            val preferences = getPreferences()
            val additionalPrefs = getAdditionalResetPreferences()
            Surface(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .clickable { showResetDialog = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = "Reset to defaults",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (showResetDialog) {
                AlertDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = { Text("Reset settings") },
                    text = { Text("Reset all settings on this screen to their default values?") },
                    confirmButton = {
                        TextButton(onClick = {
                            resetPreferencesToDefaults(preferences)
                            additionalPrefs.forEach { it.delete() }
                            showResetDialog = false
                        }) {
                            Text("Reset")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetDialog = false }) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }
        }
    }

    @Composable
    override fun Content() {
        val handleBack = LocalBackPress.current
        PreferenceScaffold(
            titleRes = getTitleRes(),
            onBackPressed = if (handleBack != null) handleBack::invoke else null,
            actions = { AppBarAction() },
            itemsProvider = { getPreferences() },
        )
    }

    companion object {
        // HACK: for the background blipping thingy.
        // The title of the target PreferenceItem
        // Set before showing the destination screen and reset after
        // See BasePreferenceWidget.highlightBackground
        var highlightKey: String? = null

        /**
         * Resets all resettable preferences in the given preference list to their default values.
         */
        fun resetPreferencesToDefaults(preferences: List<Preference>) {
            for (pref in preferences) {
                when (pref) {
                    is Preference.PreferenceGroup -> {
                        for (item in pref.preferenceItems) {
                            resetPreferenceItem(item)
                        }
                    }
                    is Preference.PreferenceItem<*, *> -> {
                        resetPreferenceItem(pref)
                    }
                }
            }
        }

        private fun resetPreferenceItem(item: Preference.PreferenceItem<*, *>) {
            when (item) {
                is Preference.PreferenceItem.SwitchPreference -> item.preference.delete()
                is Preference.PreferenceItem.ListPreference<*> -> item.preference.delete()
                is Preference.PreferenceItem.MultiSelectListPreference<*> -> item.preference.delete()
                is Preference.PreferenceItem.EditTextPreference -> item.preference.delete()
                is Preference.PreferenceItem.PromptPreference -> item.preference.delete()
                is Preference.PreferenceItem.SliderPreference -> item.preference?.delete()
                is Preference.PreferenceItem.TextPreference -> {}
                is Preference.PreferenceItem.TrackerPreference -> {}
                is Preference.PreferenceItem.InfoPreference -> {}
                is Preference.PreferenceItem.CustomPreference -> {}
                is Preference.PreferenceItem.BasicListPreference -> {}
            }
        }
    }
}
