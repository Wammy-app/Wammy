package eu.kanade.tachiyomi.ui.profile

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import eu.kanade.tachiyomi.data.sync.replica.*
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CloudSyncScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()

        val syncSettings = remember { Injekt.get<SyncSettings>() }
        val syncRepository = remember { Injekt.get<GoogleDriveSyncRepository>() }
        val auth = remember { Injekt.get<GoogleDriveAuth>() }

        val isSyncing by syncRepository.isSyncing.collectAsState()

        var accountEmail by remember { mutableStateOf(syncSettings.accountEmail) }
        var accountName by remember { mutableStateOf(syncSettings.accountName) }
        var accountPhotoUrl by remember { mutableStateOf(syncSettings.accountPhotoUrl) }
        var isEmailHidden by remember { mutableStateOf(syncSettings.isEmailHidden) }
        var intervalMinutes by remember { mutableIntStateOf(syncSettings.intervalMinutes) }
        var isWifiOnly by remember { mutableStateOf(syncSettings.isWifiOnly) }
        var enabledContent by remember { mutableStateOf(syncSettings.enabledContent) }
        var lastSyncTimestamp by remember { mutableLongStateOf(syncSettings.lastSyncTimestamp) }
        var lastError by remember { mutableStateOf(syncSettings.lastSyncError) }

        var showDeleteConfirm by remember { mutableStateOf(false) }

        // Setup account info from Firebase if available and not yet set
        LaunchedEffect(Unit) {
            val fbUser = FirebaseAuth.getInstance().currentUser
            if (fbUser != null && syncSettings.accountEmail == null) {
                syncSettings.accountEmail = fbUser.email
                syncSettings.accountName = fbUser.displayName
                syncSettings.accountPhotoUrl = fbUser.photoUrl?.toString()
                accountEmail = syncSettings.accountEmail
                accountName = syncSettings.accountName
                accountPhotoUrl = syncSettings.accountPhotoUrl
            }
        }

        fun refreshState() {
            accountEmail = syncSettings.accountEmail
            accountName = syncSettings.accountName
            accountPhotoUrl = syncSettings.accountPhotoUrl
            isEmailHidden = syncSettings.isEmailHidden
            intervalMinutes = syncSettings.intervalMinutes
            isWifiOnly = syncSettings.isWifiOnly
            enabledContent = syncSettings.enabledContent
            lastSyncTimestamp = syncSettings.lastSyncTimestamp
            lastError = syncSettings.lastSyncError
        }

        val consentLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                scope.launch {
                    val syncRes = syncRepository.sync(force = true)
                    refreshState()
                    if (syncRes is SyncResult.Success) {
                        Toast.makeText(context, "Google Drive sync completed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        fun runSync() {
            scope.launch {
                when (val authRes = auth.requestAuthorization()) {
                    is AuthResult.Success -> {
                        val res = syncRepository.sync(force = true)
                        refreshState()
                        when (res) {
                            is SyncResult.Success -> Toast.makeText(context, "Sync completed", Toast.LENGTH_SHORT).show()
                            is SyncResult.SignInRequired -> Toast.makeText(context, "Sign-in required", Toast.LENGTH_LONG).show()
                            is SyncResult.Error -> Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                        }
                    }
                    is AuthResult.NeedsConsent -> {
                        try {
                            consentLauncher.launch(IntentSenderRequest.Builder(authRes.pendingIntent).build())
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not launch consent: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    is AuthResult.Error -> {
                        Toast.makeText(context, authRes.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Google Drive Sync") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 1. Account Section
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Avatar
                            if (!accountPhotoUrl.isNullOrEmpty()) {
                                AsyncImage(
                                    model = accountPhotoUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Surface(
                                    modifier = Modifier.size(48.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Outlined.CloudSync,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = accountName ?: (if (accountEmail != null) "Connected" else "Not connected"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = if (isEmailHidden) Modifier.blur(7.dp) else Modifier,
                                )
                                if (!accountEmail.isNullOrBlank()) {
                                    Text(
                                        text = accountEmail!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = if (isEmailHidden) Modifier.blur(7.dp) else Modifier,
                                    )
                                } else {
                                    Text(
                                        text = "Sign in to keep devices in sync",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (!accountEmail.isNullOrBlank()) {
                                IconButton(onClick = {
                                    isEmailHidden = !isEmailHidden
                                    syncSettings.isEmailHidden = isEmailHidden
                                }) {
                                    Icon(
                                        if (isEmailHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = "Toggle privacy",
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Sync Status & Sync Now Button
                item {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isSyncing) { runSync() },
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Sync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp),
                            )

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                val title = when {
                                    isSyncing -> "Syncing with Google Drive…"
                                    lastError != null -> "Sync error"
                                    lastSyncTimestamp > 0L -> "Up to date"
                                    else -> "Never synced"
                                }
                                val subtitle = when {
                                    isSyncing -> "Uploading & merging replicas…"
                                    lastError != null -> "Tap to retry: $lastError"
                                    lastSyncTimestamp > 0L -> "Last synced ${DateUtils.getRelativeTimeSpanString(lastSyncTimestamp)}"
                                    else -> "Tap to sync now"
                                }

                                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            }
                        }
                    }
                }

                // 3. What to Sync Section
                item {
                    Text(
                        text = "What to sync",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }

                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            SyncContent.entries.forEach { content ->
                                val isChecked = content.key in enabledContent
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val newSet = if (isChecked) {
                                                enabledContent - content.key
                                            } else {
                                                enabledContent + content.key
                                            }
                                            enabledContent = newSet
                                            syncSettings.enabledContent = newSet
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val title = when (content) {
                                        SyncContent.FAVOURITES -> "Favourites (Manga & Novels)"
                                        SyncContent.HISTORY -> "Reading History & Progress"
                                        SyncContent.BOOKMARKS -> "Page Bookmarks"
                                        SyncContent.TRACKING -> "Tracker Progress (AniList / MAL)"
                                        SyncContent.SETTINGS -> "Preferences & Reading Modes"
                                    }
                                    Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            val newSet = if (checked) enabledContent + content.key else enabledContent - content.key
                                            enabledContent = newSet
                                            syncSettings.enabledContent = newSet
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Advanced Options
                item {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }

                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Wi-Fi Only Switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Wi-Fi only", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Only sync when connected to Wi-Fi",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = isWifiOnly,
                                    onCheckedChange = { checked ->
                                        isWifiOnly = checked
                                        syncSettings.isWifiOnly = checked
                                        SyncWorker.Scheduler.schedule(context)
                                    },
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            // Delete Data Button
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showDeleteConfirm = true },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Delete all cloud data", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                                    Text("Erase all device replicas from Google Drive", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete all cloud data?") },
                text = { Text("This will permanently remove all device replicas stored in your Google Drive appdata folder. Local data on your devices will remain.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirm = false
                            scope.launch {
                                val res = syncRepository.deleteRemoteData()
                                refreshState()
                                if (res is SyncResult.Success) {
                                    Toast.makeText(context, "Cloud replicas deleted", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                },
            )
        }
    }
}
