package eu.kanade.tachiyomi.ui.profile

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkManager
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.google.firebase.auth.FirebaseAuth
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.auth.AuthManager
import eu.kanade.tachiyomi.data.sync.AuthorizationResult
import eu.kanade.tachiyomi.data.sync.DriveApiHelper
import eu.kanade.tachiyomi.data.sync.DriveSyncManager
import eu.kanade.tachiyomi.data.sync.DriveSyncWorker
import eu.kanade.tachiyomi.data.sync.SyncResult
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
        val driveSyncManager: DriveSyncManager = Injekt.get()
        val driveApiHelper: DriveApiHelper = Injekt.get()
        val authManager: AuthManager = remember { Injekt.get() }

        val currentUser by authManager.currentUser.collectAsState()

        var isSyncing by remember { mutableStateOf(false) }
        var isDeleting by remember { mutableStateOf(false) }
        var isEmailVisible by remember { mutableStateOf(true) }
        var pendingAction by remember { mutableStateOf<String?>(null) }
        var lastErrorMessage by remember { mutableStateOf<String?>(null) }
        var showRestoreConfirmDialog by remember { mutableStateOf(false) }

        val syncPrefs = remember {
            context.getSharedPreferences(DriveSyncWorker.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        }

        var lastSyncTimestamp by remember {
            mutableStateOf(syncPrefs.getLong(DriveSyncWorker.KEY_LAST_SYNC_TIME, 0L))
        }

        var wifiOnly by remember {
            mutableStateOf(syncPrefs.getBoolean(DriveSyncWorker.KEY_WIFI_ONLY, false))
        }

        var syncIntervalHours by remember {
            mutableStateOf(syncPrefs.getLong(DriveSyncWorker.KEY_SYNC_INTERVAL_HOURS, 6L))
        }

        var showSignOutDialog by remember { mutableStateOf(false) }
        var showDeleteDialog by remember { mutableStateOf(false) }
        var showWhatToSyncDialog by remember { mutableStateOf(false) }
        var showIntervalDialog by remember { mutableStateOf(false) }

        fun refreshSyncTimestamp() {
            lastSyncTimestamp = syncPrefs.getLong(DriveSyncWorker.KEY_LAST_SYNC_TIME, 0L)
        }

        val consentLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                driveApiHelper.handleAuthorizationResult(result.data)
                scope.launch {
                    isSyncing = true
                    val syncResult = if (pendingAction == "backup") {
                        driveSyncManager.backup()
                    } else {
                        driveSyncManager.restore()
                    }
                    when (syncResult) {
                        is SyncResult.Success -> {
                            refreshSyncTimestamp()
                            Toast.makeText(context, syncResult.message, Toast.LENGTH_SHORT).show()
                        }
                        is SyncResult.Error -> {
                            lastErrorMessage = syncResult.message
                        }
                    }
                    isSyncing = false
                    pendingAction = null
                }
            } else {
                isSyncing = false
                pendingAction = null
                Toast.makeText(context, "Google Drive authorization was cancelled.", Toast.LENGTH_SHORT).show()
            }
        }

        fun launchDriveAction(action: String) {
            scope.launch {
                isSyncing = true
                pendingAction = action

                when (val authResult = driveApiHelper.requestDriveAuthorization()) {
                    is AuthorizationResult.Success -> {
                        val syncResult = if (action == "backup") {
                            driveSyncManager.backup()
                        } else {
                            driveSyncManager.restore()
                        }
                        when (syncResult) {
                            is SyncResult.Success -> {
                                refreshSyncTimestamp()
                                Toast.makeText(context, syncResult.message, Toast.LENGTH_SHORT).show()
                            }
                            is SyncResult.Error -> {
                                lastErrorMessage = syncResult.message
                            }
                        }
                        isSyncing = false
                        pendingAction = null
                    }
                    is AuthorizationResult.NeedsConsent -> {
                        isSyncing = false
                        try {
                            val intentSenderRequest = IntentSenderRequest.Builder(authResult.pendingIntent).build()
                            consentLauncher.launch(intentSenderRequest)
                        } catch (e: Exception) {
                            lastErrorMessage = "Could not open consent screen: ${e.message}"
                            pendingAction = null
                        }
                    }
                    is AuthorizationResult.Error -> {
                        lastErrorMessage = "Google Drive Authorization Failed:\n\n${authResult.message}"
                        isSyncing = false
                        pendingAction = null
                    }
                }
            }
        }

        fun handleDeleteSyncData() {
            scope.launch {
                isDeleting = true
                val result = driveSyncManager.deleteCloudBackup()
                when (result) {
                    is SyncResult.Success -> {
                        refreshSyncTimestamp()
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    }
                    is SyncResult.Error -> {
                        lastErrorMessage = result.message
                    }
                }
                isDeleting = false
            }
        }

        fun handleSignOut() {
            Injekt.get<eu.kanade.domain.base.BasePreferences>()
                .hasCompletedInitialLogin.set(true)
            authManager.signOut()
            WorkManager.getInstance(context).cancelUniqueWork("DriveSyncPeriodic")
            WorkManager.getInstance(context).cancelUniqueWork("DriveSyncBackup")
            WorkManager.getInstance(context).cancelUniqueWork("DriveSyncRestoreCheck")
            context.getSharedPreferences(DriveSyncWorker.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit().clear().apply()
            Toast.makeText(context, "Signed out of Google Drive", Toast.LENGTH_SHORT).show()
            navigator.pop()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Cloud Sync",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color.White
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF000000),
                        titleContentColor = Color.White
                    )
                )
            },
            containerColor = Color(0xFF000000)
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // 1. Google Account Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1C1C1E)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                            color = Color(0xFF2C2C2E)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_google_drive),
                                    contentDescription = "Google Drive",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            val rawName = currentUser?.displayName?.takeIf { it.isNotBlank() }
                                ?: currentUser?.email?.substringBefore("@")
                                ?: "Google Account"

                            val rawEmail = currentUser?.email ?: "Not connected"
                            val maskedEmail = if (isEmailVisible) {
                                rawEmail
                            } else {
                                if (rawEmail.contains("@")) {
                                    val prefix = rawEmail.substringBefore("@")
                                    val domain = rawEmail.substringAfter("@")
                                    val masked = prefix.take(1) + "***" + prefix.takeLast(1)
                                    "$masked@$domain"
                                } else {
                                    "••••••••"
                                }
                            }

                            Text(
                                text = rawName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = maskedEmail,
                                fontSize = 13.sp,
                                color = Color(0xFF8E8E93),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(onClick = { isEmailVisible = !isEmailVisible }) {
                            Icon(
                                imageVector = if (isEmailVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                contentDescription = "Toggle Email Visibility",
                                tint = Color(0xFF8E8E93),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. Sign Out Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { showSignOutDialog = true },
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1C1C1E)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = "Sign out",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "Sign out",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 3. Section Header: SYNC
                Text(
                    text = "SYNC",
                    color = Color(0xFF5AC8FA),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                )

                // 4. Sync Status Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(enabled = !isSyncing) {
                            launchDriveAction("backup")
                        },
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1C1C1E)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sync Status",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(2.dp))

                            val statusText = if (isSyncing) {
                                "Syncing with Google Drive..."
                            } else if (lastSyncTimestamp > 0L) {
                                val elapsed = System.currentTimeMillis() - lastSyncTimestamp
                                val timeAgo = when {
                                    elapsed < 60_000 -> "just now"
                                    elapsed < 3_600_000 -> "${elapsed / 60_000}m ago"
                                    elapsed < 86_400_000 -> "${elapsed / 3_600_000}h ago"
                                    else -> "${elapsed / 86_400_000}d ago"
                                }
                                "Last synced: $timeAgo"
                            } else {
                                "Never synced • Tap to sync now"
                            }

                            Text(
                                text = statusText,
                                fontSize = 13.sp,
                                color = Color(0xFF8E8E93)
                            )
                        }

                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFF34A853),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Synced",
                                tint = Color(0xFF34A853),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 4b. Restore from Cloud Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(enabled = !isSyncing) {
                            showRestoreConfirmDialog = true
                        },
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1C1C1E)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudDownload,
                            contentDescription = "Restore",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Restore from Cloud",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Restore your library and progress from Google Drive",
                                fontSize = 13.sp,
                                color = Color(0xFF8E8E93)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 5. What to sync Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { showWhatToSyncDialog = true },
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1C1C1E)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(22.dp)
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "What to sync",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Favorites & categories, History & reading progress, Bookmarks, Feed (new chapters), Tracking (MAL, AniList...), Reading statistics, Settings, sources & extension stores, Custom covers & overrides",
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = Color(0xFF8E8E93)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Explanatory note below "What to sync"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = Color(0xFF8E8E93),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "What you choose above is saved in your Google Drive and kept the same on all your devices. Sign in on a new device to get it all back. Sync runs by itself whenever you open or leave the app.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = Color(0xFF8E8E93)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 6. Section Header: ADVANCED
                Text(
                    text = "ADVANCED",
                    color = Color(0xFF5AC8FA),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                )

                // 7. ADVANCED Grouped Container Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1C1C1E)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Advanced Item 1: Background sync
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showIntervalDialog = true }
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AccessTime,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Background sync",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val intervalLabel = if (syncIntervalHours <= 0L) {
                                    "Off"
                                } else {
                                    "Every $syncIntervalHours hours"
                                }
                                Text(
                                    text = intervalLabel,
                                    fontSize = 13.sp,
                                    color = Color(0xFF8E8E93)
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 18.dp),
                            thickness = 0.7.dp,
                            color = Color(0xFF2C2C2E)
                        )

                        // Advanced Item 2: Sync only over Wi-Fi
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newWifi = !wifiOnly
                                    wifiOnly = newWifi
                                    syncPrefs.edit()
                                        .putBoolean(DriveSyncWorker.KEY_WIFI_ONLY, newWifi)
                                        .apply()
                                    DriveSyncWorker.schedulePeriodicBackup(context, syncIntervalHours)
                                }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Wifi,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = "Sync only over Wi-Fi",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = wifiOnly,
                                onCheckedChange = { checked ->
                                    wifiOnly = checked
                                    syncPrefs.edit()
                                        .putBoolean(DriveSyncWorker.KEY_WIFI_ONLY, checked)
                                        .apply()
                                    DriveSyncWorker.schedulePeriodicBackup(context, syncIntervalHours)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF007AFF),
                                    uncheckedThumbColor = Color(0xFFE5E5EA),
                                    uncheckedTrackColor = Color(0xFF39393D),
                                    uncheckedBorderColor = Color.Transparent
                                )
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 18.dp),
                            thickness = 0.7.dp,
                            color = Color(0xFF2C2C2E)
                        )

                        // Advanced Item 3: Delete all sync data
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isDeleting) { showDeleteDialog = true }
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isDeleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = Color(0xFFFF453A),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Delete all sync data",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Remove the synced data from your Google Drive",
                                    fontSize = 13.sp,
                                    color = Color(0xFF8E8E93)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        // --- Dialogs ---

        // 1. Sign out confirmation dialog
        if (showSignOutDialog) {
            AlertDialog(
                onDismissRequest = { showSignOutDialog = false },
                title = { Text("Sign out of Cloud Sync", fontWeight = FontWeight.Bold, color = Color.White) },
                text = {
                    Text(
                        "Your local library remains untouched on this device. Background syncing to Google Drive will be paused until you sign in again.",
                        color = Color(0xFFCCCCCC)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showSignOutDialog = false
                            handleSignOut()
                        }
                    ) {
                        Text("Sign out", color = Color(0xFFFF453A), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSignOutDialog = false }) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = Color(0xFF242426)
            )
        }

        // 2. What to sync details dialog
        if (showWhatToSyncDialog) {
            AlertDialog(
                onDismissRequest = { showWhatToSyncDialog = false },
                title = { Text("Synced Data", fontWeight = FontWeight.Bold, color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Wammy securely packages your complete library into an encrypted cloud snapshot:", color = Color.White)
                        Text("• Manga, Novels & Categories", color = Color(0xFF8E8E93))
                        Text("• Reading progress & Chapter bookmarks", color = Color(0xFF8E8E93))
                        Text("• MAL, AniList & tracker bindings", color = Color(0xFF8E8E93))
                        Text("• Source settings & Extension stores", color = Color(0xFF8E8E93))
                        Text("• Reader settings & custom covers", color = Color(0xFF8E8E93))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "The backup is stored in your private Google Drive AppData folder, so only Wammy can access it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5AC8FA)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showWhatToSyncDialog = false }) {
                        Text("Got it", color = Color(0xFF5AC8FA), fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF242426)
            )
        }

        // 3. Background Sync Interval Picker
        if (showIntervalDialog) {
            val intervals = listOf(
                2L to "Every 2 hours",
                6L to "Every 6 hours",
                12L to "Every 12 hours",
                24L to "Every 24 hours",
                0L to "Off (Manual only)"
            )
            AlertDialog(
                onDismissRequest = { showIntervalDialog = false },
                title = { Text("Background sync frequency", fontWeight = FontWeight.Bold, color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        intervals.forEach { (hours, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        syncIntervalHours = hours
                                        syncPrefs.edit()
                                            .putLong(DriveSyncWorker.KEY_SYNC_INTERVAL_HOURS, hours)
                                            .putBoolean(DriveSyncWorker.KEY_AUTO_SYNC_ENABLED, hours > 0L)
                                            .apply()
                                        DriveSyncWorker.schedulePeriodicBackup(context, hours)
                                        showIntervalDialog = false
                                    }
                                    .padding(horizontal = 6.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = syncIntervalHours == hours,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFF007AFF),
                                        unselectedColor = Color(0xFF8E8E93)
                                    )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = label,
                                    fontSize = 15.sp,
                                    color = Color.White,
                                    fontWeight = if (syncIntervalHours == hours) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showIntervalDialog = false }) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = Color(0xFF242426)
            )
        }

        // 4. Delete Cloud Data Confirmation
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Delete all sync data?", fontWeight = FontWeight.Bold, color = Color.White) },
                text = {
                    Text(
                        "This will permanently delete your sync backup file from Google Drive. Your local library on this device will not be deleted.",
                        color = Color(0xFFCCCCCC)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteDialog = false
                            handleDeleteSyncData()
                        }
                    ) {
                        Text("Delete", color = Color(0xFFFF453A), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = Color(0xFF242426)
            )
        }

        // 5. Restore Confirmation Dialog
        if (showRestoreConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showRestoreConfirmDialog = false },
                title = { Text("Restore from Google Drive", fontWeight = FontWeight.Bold, color = Color.White) },
                text = {
                    Text(
                        "Download and restore your library from the cloud? This will sync your manga, reading progress, and settings with the latest cloud backup.",
                        color = Color(0xFFCCCCCC)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRestoreConfirmDialog = false
                            launchDriveAction("restore")
                        }
                    ) {
                        Text("Restore", color = Color(0xFF5AC8FA), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRestoreConfirmDialog = false }) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = Color(0xFF242426)
            )
        }

        // 6. Error Details Dialog
        if (lastErrorMessage != null) {
            val errorText = lastErrorMessage!!
            AlertDialog(
                onDismissRequest = { lastErrorMessage = null },
                title = { Text("Cloud Sync Status", fontWeight = FontWeight.Bold, color = Color.White) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = errorText,
                            color = Color(0xFFCCCCCC),
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { lastErrorMessage = null }) {
                        Text("OK", color = Color(0xFF5AC8FA), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    if (errorText.contains("http", ignoreCase = true)) {
                        TextButton(
                            onClick = {
                                try {
                                    val urlRegex = """https?://[^\s]+""".toRegex()
                                    val match = urlRegex.find(errorText)?.value ?: "https://console.developers.google.com"
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(match))
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                        ) {
                            Text("Open Link", color = Color(0xFF34A853), fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                containerColor = Color(0xFF242426)
            )
        }
    }
}
