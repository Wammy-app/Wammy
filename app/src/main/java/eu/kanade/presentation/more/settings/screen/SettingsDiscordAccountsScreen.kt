package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.connections.discord.DiscordAccount
import eu.kanade.tachiyomi.ui.setting.connections.DiscordLoginScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object DiscordAccountsScreen : Screen {
    @Suppress("unused")
    private fun readResolve(): Any = DiscordAccountsScreen

    @Composable
    override fun Content() {
        DiscordAccountsScreenContent()
    }
}

data class DiscordAccountsScreenState(
    val accounts: List<DiscordAccount> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscordAccountsScreenContent() {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = remember { DiscordAccountsScreenModel() }
    val state by screenModel.state.collectAsState()

    val noAccountsFoundString = stringResource(MR.strings.no_accounts_found)

    Scaffold(
        topBar = {
            AppBar(
                title = stringResource(MR.strings.discord_accounts),
                navigateUp = navigator::pop,
                actions = {
                    IconButton(
                        onClick = {
                            navigator.push(DiscordLoginScreen())
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(MR.strings.action_add),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            val isLoading = state.isLoading
            val error = state.error
            val accounts = state.accounts
            val context = LocalContext.current

            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                error != null -> {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        items(accounts) { account ->
                            DiscordAccountItem(
                                account = account,
                                onRemove = { screenModel.removeAccount(account.id) },
                                onSetActive = { screenModel.setActiveAccount(account.id, context) },
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        screenModel.setNoAccountsFoundString(noAccountsFoundString)
        screenModel.refreshAccounts()
    }
}

class DiscordAccountsScreenModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val discord = Injekt.get<ConnectionsManager>().discord
    private val connectionsPreferences = Injekt.get<ConnectionsPreferences>()
    private var noAccountsFoundString: String = ""

    private val _state = MutableStateFlow(DiscordAccountsScreenState())
    val state = _state.asStateFlow()

    init {
        scope.launch {
            connectionsPreferences.discordAccounts().changes()
                .collect { loadAccounts() }
        }
        loadAccounts()
    }

    fun setNoAccountsFoundString(value: String) {
        noAccountsFoundString = value
    }

    private fun loadAccounts() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val accounts = discord.getAccounts()
                logcat(LogPriority.DEBUG) { "Debug: Loaded accounts: $accounts" }
                if (accounts.isEmpty()) {
                    _state.update {
                        it.copy(
                            accounts = emptyList(),
                            isLoading = false,
                            error = noAccountsFoundString,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            accounts = accounts,
                            isLoading = false,
                        )
                    }
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Unknown error",
                    )
                }
            }
        }
    }

    fun removeAccount(accountId: String) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                discord.removeAccount(accountId)
                loadAccounts()
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun setActiveAccount(accountId: String, context: Context) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                discord.setActiveAccount(accountId)
                discord.restartRichPresence(context)
                loadAccounts()
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun refreshAccounts() {
        loadAccounts()
    }
}

@Composable
private fun DiscordAccountItem(
    account: DiscordAccount,
    onRemove: () -> Unit,
    onSetActive: () -> Unit,
) {
    Card(
        onClick = onSetActive,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = account.avatarUrl,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = account.username,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (account.isActive) {
                    Text(
                        text = stringResource(MR.strings.active_account),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
