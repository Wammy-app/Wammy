package eu.kanade.presentation.more.settings.screen.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.SandboxExtensionUpdateJob
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.system.DeviceUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import logcat.logcat
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class RestoreBackupScreen(
    private val uri: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<RestoreBackupViewModel>(
            factory = RestoreBackupViewModel.Factory,
            extras = CreationExtras {
                set(RestoreBackupViewModel.URI_KEY, uri)
            },
        )
        val state by viewModel.state.collectAsState()
        var showSandboxDialog by remember { mutableStateOf(false) }

        if (showSandboxDialog) {
            AlertDialog(
                onDismissRequest = { showSandboxDialog = false },
                title = { Text("Install in Sandbox mode?") },
                text = {
                    Text(
                        "Sandbox mode installs extensions into Wammy's private storage automatically without repeated system installer prompts.\n\nRecommended when restoring multiple extensions.",
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showSandboxDialog = false
                            viewModel.installMissingExtensions(useSandbox = true)
                        },
                    ) {
                        Text("Use Sandbox Mode")
                    }
                },
                dismissButton = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = { showSandboxDialog = false }) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                        TextButton(
                            onClick = {
                                showSandboxDialog = false
                                viewModel.installMissingExtensions(useSandbox = false)
                            },
                        ) {
                            Text("System Install")
                        }
                    }
                },
            )
        }

        val isInstalling = state.installProgress?.isInstalling == true

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_restore_backup),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumnWithAction(
                contentPadding = contentPadding,
                actionLabel = stringResource(MR.strings.action_restore),
                actionEnabled = state.canRestore && state.options.canRestore() && !isInstalling,
                onClickAction = {
                    viewModel.startRestore()
                    navigator.pop()
                },
            ) {
                if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                    item {
                        WarningBanner(MR.strings.restore_miui_warning)
                    }
                }

                if (state.canRestore) {
                    item {
                        SectionCard {
                            RestoreOptions.options.forEach { option ->
                                LabeledCheckbox(
                                    label = stringResource(option.label),
                                    checked = option.getter(state.options),
                                    onCheckedChange = {
                                        viewModel.toggle(option.setter, it)
                                    },
                                    enabled = option.enabled(state.options) && !isInstalling,
                                )
                            }
                        }
                    }
                }

                if (state.error != null) {
                    errorMessageItem(
                        error = state.error,
                        installableExtensions = state.installableExtensions,
                        installProgress = state.installProgress,
                        onInstallClick = {
                            if (viewModel.isSandboxMode()) {
                                viewModel.installMissingExtensions(useSandbox = true)
                            } else {
                                showSandboxDialog = true
                            }
                        },
                    )
                }
            }
        }
    }

    private fun LazyListScope.errorMessageItem(
        error: Any?,
        installableExtensions: List<Extension.Available>,
        installProgress: RestoreBackupViewModel.ExtensionInstallProgress?,
        onInstallClick: () -> Unit,
    ) {
        item {
            SectionCard {
                Column(
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    val msg = buildAnnotatedString {
                        when (error) {
                            is MissingRestoreComponents -> {
                                appendLine(stringResource(MR.strings.backup_restore_content_full))
                                if (error.sources.isNotEmpty()) {
                                    appendLine()
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                        appendLine(stringResource(MR.strings.backup_restore_missing_sources))
                                    }
                                    error.sources.joinTo(
                                        this,
                                        separator = "\n- ",
                                        prefix = "- ",
                                    )
                                }
                                if (error.trackers.isNotEmpty()) {
                                    appendLine()
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                        appendLine(stringResource(MR.strings.backup_restore_missing_trackers))
                                    }
                                    error.trackers.joinTo(
                                        this,
                                        separator = "\n- ",
                                        prefix = "- ",
                                    )
                                }
                            }

                            is InvalidRestore -> {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    appendLine(stringResource(MR.strings.invalid_backup_file))
                                }
                                appendLine(error.uri.toString())

                                appendLine()

                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    appendLine(stringResource(MR.strings.invalid_backup_file_error))
                                }
                                appendLine(error.message)
                            }

                            else -> {
                                appendLine(error.toString())
                            }
                        }
                    }

                    SelectionContainer {
                        Text(text = msg)
                    }

                    if (error is MissingRestoreComponents) {
                        if (installProgress != null && installProgress.isInstalling) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = MaterialTheme.padding.small),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                            ) {
                                LinearProgressIndicator(
                                    progress = {
                                        if (installProgress.total == 0) 0f else installProgress.current.toFloat() / installProgress.total
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = if (installProgress.currentExtensionName.isNotEmpty()) {
                                        "Installing ${installProgress.currentExtensionName} (${installProgress.current}/${installProgress.total})..."
                                    } else {
                                        "Installing extensions (${installProgress.current}/${installProgress.total})..."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (installableExtensions.isNotEmpty()) {
                            Button(
                                onClick = onInstallClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = MaterialTheme.padding.small),
                            ) {
                                Text(
                                    text = "Install Missing Extensions (${installableExtensions.size})",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

class RestoreBackupViewModel(
    private val context: Context,
    private val uri: String,
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
) : StateViewModel<RestoreBackupViewModel.State>(State()) {

    private var lastMissingSources: List<BackupFileValidator.MissingSource> = emptyList()

    companion object {
        val URI_KEY = CreationExtras.Key<String>()

        val Factory = viewModelFactory {
            initializer {
                RestoreBackupViewModel(
                    context = Injekt.get<Application>(),
                    uri = get(URI_KEY)!!,
                )
            }
        }
    }

    init {
        viewModelScope.launchIO {
            validate(uri.toUri())
        }
        viewModelScope.launchIO {
            extensionManager.availableExtensionsFlow.collect { available ->
                computeInstallableExtensions(available)
            }
        }
    }

    fun isSandboxMode(): Boolean {
        return basePreferences.extensionInstaller.get() == BasePreferences.ExtensionInstaller.PRIVATE
    }

    fun toggle(setter: (RestoreOptions, Boolean) -> RestoreOptions, enabled: Boolean) {
        mutableState.update {
            it.copy(
                options = setter(it.options, enabled),
            )
        }
    }

    fun startRestore() {
        BackupRestoreJob.start(
            context = context,
            uri = uri.toUri(),
            options = state.value.options,
        )
    }

    fun installMissingExtensions(useSandbox: Boolean) {
        val extensionsToInstall = state.value.installableExtensions
        if (extensionsToInstall.isEmpty()) return

        if (useSandbox) {
            basePreferences.extensionInstaller.set(BasePreferences.ExtensionInstaller.PRIVATE)
            basePreferences.sandboxExtensionsEnabled.set(true)
            SandboxExtensionUpdateJob.setupTask(context, true)
        }

        viewModelScope.launchIO {
            mutableState.update {
                it.copy(
                    installProgress = ExtensionInstallProgress(
                        isInstalling = true,
                        current = 0,
                        total = extensionsToInstall.size,
                    ),
                )
            }

            for ((index, ext) in extensionsToInstall.withIndex()) {
                mutableState.update {
                    it.copy(
                        installProgress = ExtensionInstallProgress(
                            isInstalling = true,
                            current = index + 1,
                            total = extensionsToInstall.size,
                            currentExtensionName = ext.name,
                        ),
                    )
                }

                try {
                    withTimeoutOrNull(60_000L) {
                        extensionManager.installExtension(ext)
                            .filter { it == InstallStep.Installed || it == InstallStep.Error }
                            .first()
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Failed to install missing extension ${ext.pkgName}: ${e.message}" }
                }
            }

            delay(500)

            mutableState.update {
                it.copy(installProgress = null)
            }

            validate(uri.toUri())
        }
    }

    private suspend fun validate(uri: Uri) {
        val results = try {
            BackupFileValidator(context).validate(uri)
        } catch (e: Exception) {
            lastMissingSources = emptyList()
            setError(
                error = InvalidRestore(uri, e.message.toString()),
                canRestore = false,
            )
            return
        }

        lastMissingSources = results.missingSourceDetails

        if (results.missingSources.isNotEmpty() || results.missingTrackers.isNotEmpty()) {
            if (extensionManager.availableExtensionsFlow.value.isEmpty()) {
                try {
                    extensionManager.findAvailableExtensions()
                } catch (e: Exception) {
                    logcat(LogPriority.WARN) { "Failed to find available extensions during validation: ${e.message}" }
                }
            }
            computeInstallableExtensions(extensionManager.availableExtensionsFlow.value)
            setError(
                error = MissingRestoreComponents(uri, results.missingSources, results.missingTrackers),
                canRestore = true,
            )
            return
        }

        computeInstallableExtensions(emptyList())
        setError(error = null, canRestore = true)
    }

    private fun computeInstallableExtensions(available: List<Extension.Available>) {
        if (lastMissingSources.isEmpty()) {
            mutableState.update { it.copy(installableExtensions = emptyList()) }
            return
        }
        val installedPkgs = extensionManager.installedExtensionsFlow.value.map { it.pkgName }.toSet()
        val matchingExtensions = mutableSetOf<Extension.Available>()
        for (missing in lastMissingSources) {
            val found = available.find { ext ->
                !installedPkgs.contains(ext.pkgName) && (
                    ext.sources.any { it.id == missing.id } ||
                    ext.sources.any { it.name.equals(missing.name, ignoreCase = true) } ||
                    ext.name.equals(missing.name, ignoreCase = true)
                )
            }
            if (found != null) {
                matchingExtensions.add(found)
            }
        }
        mutableState.update {
            it.copy(installableExtensions = matchingExtensions.toList())
        }
    }

    private fun setError(error: Any?, canRestore: Boolean) {
        mutableState.update {
            it.copy(
                error = error,
                canRestore = canRestore,
            )
        }
    }

    @Immutable
    data class State(
        val error: Any? = null,
        val canRestore: Boolean = false,
        val options: RestoreOptions = RestoreOptions(),
        val installableExtensions: List<Extension.Available> = emptyList(),
        val installProgress: ExtensionInstallProgress? = null,
    )

    data class ExtensionInstallProgress(
        val isInstalling: Boolean = false,
        val current: Int = 0,
        val total: Int = 0,
        val currentExtensionName: String = "",
    )
}

private data class MissingRestoreComponents(
    val uri: Uri,
    val sources: List<String>,
    val trackers: List<String>,
)

private data class InvalidRestore(
    val uri: Uri? = null,
    val message: String,
)
