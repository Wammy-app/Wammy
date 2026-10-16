package eu.kanade.tachiyomi.ui.more

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.MoreScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.SandboxExtensionUpdateJob
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.stats.StatsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*

data object MoreTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_more_enter)
            return TabOptions(
                index = 6u,
                title = stringResource(MR.strings.label_more),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(SettingsScreen())
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<MoreViewModel>()
        val downloadQueueState by viewModel.downloadQueueState.collectAsState()
        val migrationState by viewModel.migrationState.collectAsState()

        MoreScreen(
            downloadQueueStateProvider = { downloadQueueState },
            downloadedOnly = viewModel.downloadedOnly,
            onDownloadedOnlyChange = { viewModel.downloadedOnly = it },
            incognitoMode = viewModel.incognitoMode,
            onIncognitoModeChange = { viewModel.incognitoMode = it },
            sandboxExtensionsEnabled = viewModel.sandboxExtensionsEnabled,
            onSandboxExtensionsChange = { enabled ->
                viewModel.sandboxExtensionsEnabled = enabled
                viewModel.setSandboxInstaller(enabled)
                if (enabled) viewModel.checkAndPrepareMigration()
            },
            onClickDownloadQueue = { navigator.push(DownloadQueueScreen()) },
            onClickUpdates = { navigator.push(eu.kanade.tachiyomi.ui.updates.UpdatesTab) },
            onClickCategories = { navigator.push(CategoryScreen()) },
            onClickStats = { navigator.push(StatsScreen()) },
            onClickDataAndStorage = { navigator.push(SettingsScreen(SettingsScreen.Destination.DataAndStorage)) },
            onClickSettings = { navigator.push(SettingsScreen()) },
            onClickSupport = { navigator.push(SettingsScreen()) },
            onClickAbout = { navigator.push(SettingsScreen(SettingsScreen.Destination.About)) },
        )

        // Migration dialog — shown after enabling sandbox if system-installed extensions exist
        when (val state = migrationState) {
            is MigrationState.Available -> {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissMigration() },
                    title = { Text("Migrate existing extensions?") },
                    text = {
                        Text(
                            "You have ${state.count} extension${if (state.count == 1) "" else "s"} installed via the system.\n\n" +
                                "Migrate them to sandbox mode so they update silently in the future? " +
                                "They will be re-downloaded in the background.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.migrateToSandbox() }) {
                            Text("Migrate")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissMigration() }) {
                            Text("Skip")
                        }
                    },
                )
            }
            is MigrationState.InProgress -> {
                AlertDialog(
                    onDismissRequest = { /* block dismiss during migration */ },
                    title = { Text("Migrating extensions…") },
                    text = {
                        Column {
                            Text("${state.done} / ${state.total} done")
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    confirmButton = {},
                )
            }
            is MigrationState.Done -> {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissMigration() },
                    title = { Text("Migration complete") },
                    text = { Text("All extensions are now in sandbox mode. They will update automatically in the background.") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.dismissMigration() }) { Text("OK") }
                    },
                )
            }
            MigrationState.None -> { /* nothing */ }
        }
    }
}

class MoreViewModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    private val preferences: BasePreferences = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
) : ViewModel() {

    var downloadedOnly by preferences.downloadedOnly.asState(viewModelScope)
    var incognitoMode by preferences.incognitoMode.asState(viewModelScope)
    var sandboxExtensionsEnabled by preferences.sandboxExtensionsEnabled.asState(viewModelScope)

    // ─── Migration state ──────────────────────────────────────────────────────
    private val _migrationState = MutableStateFlow<MigrationState>(MigrationState.None)
    val migrationState: StateFlow<MigrationState> = _migrationState.asStateFlow()

    fun setSandboxInstaller(enabled: Boolean) {
        if (enabled) {
            preferences.extensionInstaller.set(BasePreferences.ExtensionInstaller.PRIVATE)
        } else {
            preferences.extensionInstaller.set(BasePreferences.ExtensionInstaller.PACKAGEINSTALLER)
        }
        // Schedule or cancel the daily background auto-update worker
        SandboxExtensionUpdateJob.setupTask(preferences.context, enabled)
    }

    /** Check how many system-installed extensions exist and offer migration. */
    fun checkAndPrepareMigration() {
        viewModelScope.launchIO {
            val count = extensionManager.installedExtensionsFlow.value.count { it.isShared }
            if (count > 0) {
                _migrationState.value = MigrationState.Available(count)
            }
        }
    }

    /** Re-download all system-installed extensions as sandbox (private) files. */
    fun migrateToSandbox() {
        viewModelScope.launchIO {
            val sharedExts = extensionManager.installedExtensionsFlow.value.filter { it.isShared }
            _migrationState.value = MigrationState.InProgress(done = 0, total = sharedExts.size)

            // Ensure the available extensions list is loaded (needed for download URLs)
            extensionManager.findAvailableExtensions()

            val availableExts = extensionManager.availableExtensionsFlow.value.associateBy { it.pkgName }
            var done = 0

            for (installedExt in sharedExts) {
                val availableExt = availableExts[installedExt.pkgName]
                if (availableExt != null) {
                    // installExtension() uses the current installer pref (PRIVATE) → no popup
                    extensionManager.installExtension(availableExt).collect { /* silent */ }
                }
                done++
                _migrationState.value = MigrationState.InProgress(done = done, total = sharedExts.size)
            }

            _migrationState.value = MigrationState.Done
        }
    }

    fun dismissMigration() {
        _migrationState.value = MigrationState.None
    }

    // ─── Download queue state ─────────────────────────────────────────────────
    private var _downloadQueueState: MutableStateFlow<DownloadQueueState> = MutableStateFlow(DownloadQueueState.Stopped)
    val downloadQueueState: StateFlow<DownloadQueueState> = _downloadQueueState.asStateFlow()

    init {
        // Handle running/paused status change and queue progress updating
        viewModelScope.launchIO {
            combine(
                downloadManager.isDownloaderRunning,
                downloadManager.queueState,
            ) { isRunning, downloadQueue -> Pair(isRunning, downloadQueue.size) }
                .collectLatest { (isDownloading, downloadQueueSize) ->
                    val pendingDownloadExists = downloadQueueSize != 0
                    _downloadQueueState.value = when {
                        !pendingDownloadExists -> DownloadQueueState.Stopped
                        !isDownloading -> DownloadQueueState.Paused(downloadQueueSize)
                        else -> DownloadQueueState.Downloading(downloadQueueSize)
                    }
                }
        }
    }
}

sealed interface MigrationState {
    data object None : MigrationState
    data class Available(val count: Int) : MigrationState
    data class InProgress(val done: Int, val total: Int) : MigrationState
    data object Done : MigrationState
}

sealed interface DownloadQueueState {
    data object Stopped : DownloadQueueState
    data class Paused(val pending: Int) : DownloadQueueState
    data class Downloading(val pending: Int) : DownloadQueueState
}
