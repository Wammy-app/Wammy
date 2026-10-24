package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.connections.ConnectionsService
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private data class LogoutConnectionDialog(val service: ConnectionsService)

object SettingsDiscordScreen : SearchableSettings {
    @Suppress("unused")
    private fun readResolve(): Any = SettingsDiscordScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_connections

    @Composable
    override fun RowScope.AppBarAction() {
        val uriHandler = LocalUriHandler.current
        IconButton(onClick = { uriHandler.openUri("https://kainotch.github.io/WammyWeb/") }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = stringResource(MR.strings.tracking_guide),
            )
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val navigator = LocalNavigator.currentOrThrow
        val connectionsPreferences = remember { Injekt.get<ConnectionsPreferences>() }
        val connectionsManager = remember { Injekt.get<ConnectionsManager>() }
        val enableDRPCPref = connectionsPreferences.enableDiscordRPC()
        val useChapterTitlesPref = connectionsPreferences.useChapterTitles()
        val discordRPCStatus = connectionsPreferences.discordRPCStatus()
        val customMessagePref = connectionsPreferences.discordCustomMessage()
        val showProgressPref = connectionsPreferences.discordShowProgress()
        val showTimestampPref = connectionsPreferences.discordShowTimestamp()
        val showButtonsPref = connectionsPreferences.discordShowButtons()
        val showDownloadButtonPref = connectionsPreferences.discordShowDownloadButton()
        val showDiscordButtonPref = connectionsPreferences.discordShowDiscordButton()
        val showProgressEnabled by showProgressPref.collectAsState()

        val enableDRPC by enableDRPCPref.collectAsState()
        val showButtons by showButtonsPref.collectAsState()

        var dialog by remember { mutableStateOf<Any?>(null) }
        dialog?.run {
            when (this) {
                is LogoutConnectionDialog -> {
                    ConnectionsLogoutDialog(
                        serviceName = stringResource(service.nameStrRes()),
                        onConfirmation = {
                            enableDRPCPref.set(false)
                            service.logout()
                            navigator.pop()
                        },
                        onDismissRequest = {
                            dialog = null
                        },
                    )
                }
            }
        }

        var showCustomMessageDialog by rememberSaveable { mutableStateOf(false) }
        var tempCustomMessage by rememberSaveable { mutableStateOf(customMessagePref.get()) }

        if (showCustomMessageDialog) {
            AlertDialog(
                onDismissRequest = {
                    showCustomMessageDialog = false
                    tempCustomMessage = customMessagePref.get()
                },
                title = { Text(stringResource(MR.strings.pref_discord_custom_message)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = tempCustomMessage,
                            onValueChange = { tempCustomMessage = it },
                            label = { Text(stringResource(MR.strings.pref_discord_custom_message_summary)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        TextButton(
                            onClick = {
                                customMessagePref.delete()
                                tempCustomMessage = ""
                            },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(MR.strings.action_reset))
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            customMessagePref.set(tempCustomMessage)
                            showCustomMessageDialog = false
                        },
                    ) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showCustomMessageDialog = false
                            tempCustomMessage = customMessagePref.get()
                        },
                    ) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.discord_accounts),
                onClick = { navigator.push(DiscordAccountsScreen) },
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.connections_discord),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = enableDRPCPref,
                        title = stringResource(MR.strings.pref_enable_discord_rpc),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = discordRPCStatus,
                        title = stringResource(MR.strings.pref_discord_status),
                        entries = persistentMapOf(
                            -1 to stringResource(MR.strings.pref_discord_dnd),
                            0 to stringResource(MR.strings.pref_discord_idle),
                            1 to stringResource(MR.strings.pref_discord_online),
                        ),
                        enabled = enableDRPC,
                    ),
                ),
            ),
            getRPCIncognitoGroup(
                connectionsPreferences = connectionsPreferences,
                enabled = enableDRPC,
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_discord_customization),
                enabled = enableDRPC,
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_discord_custom_message),
                        subtitle = stringResource(MR.strings.pref_discord_custom_message_summary),
                        onClick = { showCustomMessageDialog = true },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = showProgressPref,
                        title = stringResource(MR.strings.pref_discord_show_progress),
                        subtitle = stringResource(MR.strings.pref_discord_show_progress_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = useChapterTitlesPref,
                        title = stringResource(MR.strings.show_chapters_titles_title),
                        subtitle = stringResource(MR.strings.show_chapters_titles_subtitle),
                        enabled = showProgressEnabled,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = showTimestampPref,
                        title = stringResource(MR.strings.pref_discord_show_timestamp),
                        subtitle = stringResource(MR.strings.pref_discord_show_timestamp_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = showButtonsPref,
                        title = stringResource(MR.strings.pref_discord_show_buttons),
                        subtitle = stringResource(MR.strings.pref_discord_show_buttons_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = showDownloadButtonPref,
                        title = stringResource(MR.strings.pref_discord_show_download_button),
                        subtitle = stringResource(MR.strings.pref_discord_show_download_button_summary),
                        enabled = showButtons,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = showDiscordButtonPref,
                        title = stringResource(MR.strings.pref_discord_show_discord_button),
                        subtitle = stringResource(MR.strings.pref_discord_show_discord_button_summary),
                        enabled = showButtons,
                    ),
                ),
            ),
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(MR.strings.logout),
                content = {
                    TextPreferenceWidget(
                        modifier = Modifier
                            .padding(horizontal = MaterialTheme.padding.large),
                        title = stringResource(MR.strings.logout),
                        icon = Icons.AutoMirrored.Filled.Logout,
                        iconTint = MaterialTheme.colorScheme.error,
                        onPreferenceClick = {
                            dialog = LogoutConnectionDialog(connectionsManager.discord)
                        },
                    )
                },
            ),
        )
    }

    @Composable
    private fun getRPCIncognitoGroup(
        connectionsPreferences: ConnectionsPreferences,
        enabled: Boolean,
    ): Preference.PreferenceGroup {
        val getCategories = remember { Injekt.get<GetCategories>() }
        val allCategories by getCategories.subscribe().collectAsState(initial = emptyList())

        val discordRPCIncognitoPref = connectionsPreferences.discordRPCIncognito()
        val discordRPCIncognitoCategoriesPref = connectionsPreferences.discordRPCIncognitoCategories()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.categories),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = discordRPCIncognitoPref,
                    title = stringResource(MR.strings.pref_discord_incognito),
                    subtitle = stringResource(MR.strings.pref_discord_incognito_summary),
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = discordRPCIncognitoCategoriesPref,
                    entries = allCategories
                        .associate { it.id.toString() to it.visualName }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.categories),
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(MR.strings.pref_discord_incognito_categories_details),
                ),
            ),
            enabled = enabled,
        )
    }
}

@Composable
internal fun ConnectionsLogoutDialog(
    serviceName: String,
    onConfirmation: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(MR.strings.logout_title, serviceName),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onDismissRequest,
                ) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onConfirmation()
                        onDismissRequest()
                        context.toast(MR.strings.logout_success)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(text = stringResource(MR.strings.logout))
                }
            }
        },
    )
}
