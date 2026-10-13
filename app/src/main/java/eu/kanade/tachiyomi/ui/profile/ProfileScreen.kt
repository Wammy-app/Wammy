package eu.kanade.tachiyomi.ui.profile

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import java.io.File
import java.io.FileOutputStream
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.foundation.clickable
import eu.kanade.presentation.more.SettingItem
import eu.kanade.presentation.more.SettingsGroup
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MultipartBody
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import eu.kanade.tachiyomi.data.sync.DriveApiHelper
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import eu.kanade.tachiyomi.data.auth.AuthManager
import eu.kanade.tachiyomi.data.sync.AuthorizationResult
import eu.kanade.tachiyomi.data.sync.DriveSyncManager
import eu.kanade.tachiyomi.data.sync.SyncResult
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

class ProfileScreen : Screen {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val authManager: AuthManager = Injekt.get()
        val user by authManager.currentUser.collectAsState()

        
        var cloudUser by remember { mutableStateOf<eu.kanade.tachiyomi.data.sync.CloudUser?>(null) }
        val repo = remember { eu.kanade.tachiyomi.data.sync.FirestoreUserRepository() }
        val driveApiHelper: DriveApiHelper = remember { Injekt.get() }

        // Top 5 Favorites state
        var topManga by remember { mutableStateOf<List<eu.kanade.tachiyomi.data.sync.FavoriteManga>>(emptyList()) }
        var topNovels by remember { mutableStateOf<List<eu.kanade.tachiyomi.data.sync.FavoriteManga>>(emptyList()) }
        var favoritesLoaded by remember { mutableStateOf(false) }
        val sourceManager: tachiyomi.domain.source.service.SourceManager = remember { Injekt.get() }

        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { /* Just recording that they answered */ }

        val driveConsentLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                // If they successfully authorized Drive, trigger the restore check!
                eu.kanade.tachiyomi.data.sync.DriveSyncWorker.scheduleRestoreCheck(context)
                Toast.makeText(context, "Google Drive connected! Checking for restore...", Toast.LENGTH_SHORT).show()
            }
            if (navigator.size == 1) navigator.replaceAll(eu.kanade.tachiyomi.ui.home.HomeScreen)
        }
        
        LaunchedEffect(user) {
            if (user != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            // Check if logged-in user has a Firestore profile (username)
            // If not, redirect to Gatekeeper
            if (user != null) {
                val uid = user?.uid
                if (uid != null) {
                    val hasProfile = repo.hasProfile(uid)
                    if (hasProfile == false) {
                        navigator.push(UsernamePickerScreen())
                    }
                }
            }
        }

        // Load Top 5 Favorites from Firestore
        LaunchedEffect(user) {
            val uid = user?.uid
            if (uid != null) {
                topManga = repo.getTopManga(uid)
                topNovels = repo.getTopNovels(uid)
                favoritesLoaded = true
            }
        }

        DisposableEffect(user) {
            var listener: com.google.firebase.firestore.ListenerRegistration? = null
            if (user != null) {
                val uid = user!!.uid
                listener = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection(eu.kanade.tachiyomi.data.sync.CloudUser.COLLECTION)
                    .document(uid)
                    .addSnapshotListener { snapshot, error ->
                        if (error == null && snapshot != null && snapshot.exists()) {
                            cloudUser = snapshot.toObject(eu.kanade.tachiyomi.data.sync.CloudUser::class.java)
                        }
                    }
            }
            onDispose {
                listener?.remove()
            }
        }

        var isSigningIn by remember { mutableStateOf(false) }
        var authErrorMessage by remember { mutableStateOf<String?>(null) }

        val webClientId = eu.kanade.tachiyomi.data.auth.AuthConstants.WEB_CLIENT_ID

        fun signInWithFirebaseToken(idToken: String) {
            isSigningIn = true
            val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
            FirebaseAuth.getInstance().signInWithCredential(firebaseCredential).addOnCompleteListener { authTask ->
                isSigningIn = false
                if (authTask.isSuccessful) {
                    Injekt.get<eu.kanade.domain.base.BasePreferences>().hasCompletedInitialLogin.set(true)
                    Toast.makeText(context, "Signed in successfully!", Toast.LENGTH_SHORT).show()

                    val currentUid = authTask.result?.user?.uid
                    if (currentUid != null) {
                        scope.launch {
                            val hasProfile = repo.hasProfile(currentUid)
                            if (hasProfile == false) {
                                navigator.push(UsernamePickerScreen())
                            }
                        }
                    }

                    scope.launch {
                        try {
                            when (val authResult = driveApiHelper.requestDriveAuthorization()) {
                                is AuthorizationResult.NeedsConsent -> {
                                    val intentSenderRequest = IntentSenderRequest.Builder(authResult.pendingIntent).build()
                                    driveConsentLauncher.launch(intentSenderRequest)
                                }
                                is AuthorizationResult.Success -> {
                                    eu.kanade.tachiyomi.data.sync.DriveSyncWorker.scheduleRestoreCheck(context)
                                }
                                is AuthorizationResult.Error -> {
                                    android.util.Log.e("ProfileScreen", "Drive Auth Error: ${authResult.message}")
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ProfileScreen", "Drive Auth Exception", e)
                        }
                    }
                } else {
                    val ex = authTask.exception
                    android.util.Log.e("ProfileScreen", "Firebase Auth Failed", ex)
                    authErrorMessage = "Firebase Authentication Failed:\n\n${ex?.localizedMessage ?: ex?.message ?: "Unknown Firebase error"}\n\nPlease check Firebase Console -> Authentication -> Sign-in method -> Google."
                }
            }
        }

        val legacyGoogleSignInLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            if (data != null) {
                val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(data)
                try {
                    val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                    val idToken = account?.idToken
                    if (!idToken.isNullOrBlank()) {
                        signInWithFirebaseToken(idToken)
                    } else {
                        isSigningIn = false
                        authErrorMessage = "Sign in failed: Google returned an empty ID token."
                    }
                } catch (e: com.google.android.gms.common.api.ApiException) {
                    isSigningIn = false
                    if (e.statusCode == com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
                        android.util.Log.d("ProfileScreen", "User cancelled Google Sign-In picker")
                    } else {
                        val message = when (e.statusCode) {
                            10 -> "Developer Error (Code 10):\n\n" +
                                  "The signing SHA-1/SHA-256 fingerprint or package name is not registered in Firebase Console.\n\n" +
                                  "• App Package: ${context.packageName}\n" +
                                  "• Action: Open Firebase Console -> Project Settings -> Your Apps and verify SHA-1 and SHA-256 fingerprints."
                            12500 -> "Sign in failed (Code 12500):\n\nGoogle Play Services could not authenticate this account. Make sure your Google account is properly logged in on this device."
                            7 -> "Network error (Code 7):\n\nCannot reach Google authentication servers. Please check your internet connection."
                            16 -> "Sign in failed (Code 16):\n\nAccount cannot be accessed or credentials mismatch."
                            else -> "Google Sign-In failed (Code ${e.statusCode}):\n\n${e.message.orEmpty()}"
                        }
                        android.util.Log.e("ProfileScreen", message, e)
                        authErrorMessage = message
                    }
                }
            } else {
                isSigningIn = false
                if (result.resultCode != android.app.Activity.RESULT_CANCELED) {
                    authErrorMessage = "Sign in failed with result code: ${result.resultCode}"
                }
            }
        }

        fun launchLegacyGoogleSignIn() {
            try {
                val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(webClientId)
                    .requestEmail()
                    .build()
                val client = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, gso)
                client.signOut().addOnCompleteListener {
                    legacyGoogleSignInLauncher.launch(client.signInIntent)
                }
            } catch (e: Exception) {
                isSigningIn = false
                android.util.Log.e("ProfileScreen", "Failed to launch legacy Google Sign In", e)
                authErrorMessage = "Google Play Services sign-in unavailable:\n${e.message}"
            }
        }

        if (user != null) {
            val highResPhotoUrl = user?.photoUrl?.toString()?.replace("s96-c", "s800-c")

            val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .verticalScroll(rememberScrollState())
            ) {
                AsyncImage(
                    model = highResPhotoUrl,
                    contentDescription = "Profile Background",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(screenHeight)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(screenHeight)
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Transparent,
                                0.15f to Color.Black.copy(alpha = 0.6f),
                                0.35f to Color.Black,
                                1.0f to Color.Black
                            )
                        )
                ) {}

                var showEditDialog by remember { mutableStateOf(false) }

                @OptIn(ExperimentalMaterial3Api::class)
                var showBottomSheet by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp, start = 16.dp, end = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = { if (!navigator.pop()) (context as? android.app.Activity)?.finish() },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                        
                        Box {
                            IconButton(
                                onClick = { showBottomSheet = true },
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                            }
                        }
                    }
                    
                    if (showBottomSheet) {
                        @OptIn(ExperimentalMaterial3Api::class)
                        ModalBottomSheet(
                            onDismissRequest = { showBottomSheet = false },
                            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                            containerColor = MaterialTheme.colorScheme.background,
                            dragHandle = {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 14.dp, bottom = 6.dp)
                                        .size(width = 36.dp, height = 4.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                                )
                            },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp, bottom = 32.dp),
                            ) {
                                SettingsGroup {
                                    item { pos ->
                                        SettingItem(
                                            title = "Settings",
                                            subtitle = "Appearance, library, reader, and preferences",
                                            icon = Icons.Outlined.Settings,
                                            shape = pos.shape,
                                            onClick = {
                                                showBottomSheet = false
                                                navigator.push(eu.kanade.tachiyomi.ui.setting.SettingsScreen())
                                            },
                                        )
                                    }
                                    item { pos ->
                                        SettingItem(
                                            title = "Cloud Sync",
                                            subtitle = "Backup and restore your data with Google Drive",
                                            icon = Icons.Outlined.Sync,
                                            shape = pos.shape,
                                            onClick = {
                                                showBottomSheet = false
                                                navigator.push(CloudSyncScreen())
                                            },
                                        )
                                    }
                                    item { pos ->
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = pos.shape,
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        showBottomSheet = false
                                                        Injekt.get<eu.kanade.domain.base.BasePreferences>()
                                                            .hasCompletedInitialLogin.set(true)
                                                        authManager.signOut()
                                                        navigator.pop()
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Outlined.Logout,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(22.dp),
                                                )
                                                Spacer(Modifier.width(16.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Sign Out",
                                                        fontSize = 15.5.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = MaterialTheme.colorScheme.error,
                                                    )
                                                    Spacer(Modifier.height(3.dp))
                                                    Text(
                                                        text = "Disconnect your account from this device",
                                                        fontSize = 12.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        lineHeight = 17.sp,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(36.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        AsyncImage(
                            model = user?.photoUrl?.toString()?.replace("s96-c", "s192-c"),
                            contentDescription = "Profile Picture",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(80.dp).clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(
                                    text = user?.displayName ?: "Unknown User",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                if (cloudUser?.username != null) {
                                    Text(
                                        text = "@${cloudUser!!.username}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Surface(
                                color = Color.White.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(16.dp),
                                onClick = { showEditDialog = true }
                            ) {
                                Text(
                                    text = "Edit",
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                        if (showEditDialog) {
                            var newName by remember { mutableStateOf(user?.displayName ?: "") }
                            var newUsername by remember(cloudUser) { mutableStateOf(cloudUser?.username ?: "") }
                            var newPhotoUrl by remember { mutableStateOf(user?.photoUrl?.toString() ?: "") }
                            var usernameError by remember { mutableStateOf<String?>(null) }
                            var isUpdating by remember { mutableStateOf(false) }
                            
                            var isOnCooldown = false
                            var cooldownDaysLeft = 0
                            cloudUser?.usernameChangedAt?.let { changedAt ->
                                val elapsedMs = System.currentTimeMillis() - (changedAt.seconds * 1000L)
                                val cooldownMs = eu.kanade.tachiyomi.data.sync.CloudUser.USERNAME_CHANGE_COOLDOWN_MS
                                if (elapsedMs < cooldownMs) {
                                    isOnCooldown = true
                                    cooldownDaysLeft = ((cooldownMs - elapsedMs) / (1000L * 60 * 60 * 24)).toInt().coerceAtLeast(1)
                                }
                            }
                            
                            val photoPickerLauncher = rememberLauncherForActivityResult(
                                contract = ActivityResultContracts.PickVisualMedia()
                            ) { uri ->
                                if (uri != null) {
                                    try {
                                        val inputStream = context.contentResolver.openInputStream(uri)
                                        val file = File(context.filesDir, "profile_pic_${System.currentTimeMillis()}.jpg")
                                        val outputStream = FileOutputStream(file)
                                        inputStream?.copyTo(outputStream)
                                        inputStream?.close()
                                        outputStream.close()
                                        newPhotoUrl = "file://${file.absolutePath}"
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            
                            AlertDialog(
                                onDismissRequest = { if (!isUpdating) showEditDialog = false },
                                title = { Text("Edit Profile") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        OutlinedTextField(
                                            value = newName,
                                            onValueChange = { newName = it },
                                            label = { Text("Display Name") },
                                            singleLine = true
                                        )
                                        OutlinedTextField(
                                            value = newUsername,
                                            onValueChange = { 
                                                val cleaned = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }
                                                newUsername = cleaned
                                                usernameError = eu.kanade.tachiyomi.data.sync.CloudUser.validateUsername(cleaned)
                                            },
                                            label = { Text("Username") },
                                            singleLine = true,
                                            enabled = !isOnCooldown,
                                            isError = usernameError != null,
                                            supportingText = { 
                                                if (isOnCooldown) {
                                                    Text("You can change your username in $cooldownDaysLeft days", color = MaterialTheme.colorScheme.error)
                                                } else {
                                                    usernameError?.let { msg -> Text(msg) }
                                                }
                                            }
                                        )
                                        
                                        Column {
                                            Text("Profile Picture", style = MaterialTheme.typography.labelMedium)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            if (newPhotoUrl.isNotEmpty()) {
                                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                                    AsyncImage(
                                                        model = newPhotoUrl,
                                                        contentDescription = "Preview",
                                                        modifier = Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(16.dp))
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    photoPickerLauncher.launch(
                                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                    )
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Choose from Gallery")
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            isUpdating = true
                                            
                                            // Helper function to update Firestore after Auth succeeds
                                            fun updateFirestoreAndFinish(finalPhotoUrl: String) {
                                                scope.launch {
                                                    val uid = user?.uid
                                                    if (uid != null) {
                                                        repo.updateProfile(uid, displayName = newName, avatarUrl = finalPhotoUrl.ifEmpty { null })
                                                        if (cloudUser?.username != newUsername && newUsername.isNotEmpty()) {
                                                            val renameResult = repo.renameUsername(uid, cloudUser?.username ?: "", newUsername)
                                                            if (renameResult.isFailure) {
                                                                Toast.makeText(context, "Username error: ${renameResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                                            }
                                                        }
                                                        cloudUser = repo.getUser(uid)
                                                    }
                                                    isUpdating = false
                                                    showEditDialog = false
                                                }
                                            }

                                            if (newPhotoUrl.startsWith("file://")) {
                                                val fileUri = android.net.Uri.parse(newPhotoUrl)
                                                val file = java.io.File(fileUri.path!!)
                                                val client = Injekt.get<NetworkHelper>().client
                                                
                                                scope.launch(Dispatchers.IO) {
                                                    try {
                                                        val requestBody = MultipartBody.Builder()
                                                            .setType(MultipartBody.FORM)
                                                            .addFormDataPart("reqtype", "fileupload")
                                                            .addFormDataPart("fileToUpload", file.name, file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
                                                            .build()
                                                            
                                                        val request = Request.Builder()
                                                            .url("https://catbox.moe/user/api.php")
                                                            .post(requestBody)
                                                            .build()
                                                            
                                                        val response = client.newCall(request).execute()
                                                        val responseUrl = response.body?.string()?.trim() ?: ""
                                                        
                                                        withContext(Dispatchers.Main) {
                                                            if (response.isSuccessful && responseUrl.startsWith("http")) {
                                                                val profileUpdates = com.google.firebase.auth.userProfileChangeRequest {
                                                                    displayName = newName
                                                                    photoUri = android.net.Uri.parse(responseUrl)
                                                                }
                                                                user?.updateProfile(profileUpdates)?.addOnCompleteListener { task ->
                                                                    if (task.isSuccessful) {
                                                                        updateFirestoreAndFinish(responseUrl)
                                                                    } else {
                                                                        isUpdating = false
                                                                        showEditDialog = false
                                                                        Toast.makeText(context, "Failed to update profile", Toast.LENGTH_SHORT).show()
                                                                    }
                                                                }
                                                            } else {
                                                                isUpdating = false
                                                                Toast.makeText(context, "Failed to upload image to cloud", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        withContext(Dispatchers.Main) {
                                                            isUpdating = false
                                                            Toast.makeText(context, "Upload error: ${e.message}", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            } else {
                                                val profileUpdates = com.google.firebase.auth.userProfileChangeRequest {
                                                    displayName = newName
                                                    if (newPhotoUrl.isNotEmpty()) {
                                                        photoUri = android.net.Uri.parse(newPhotoUrl)
                                                    }
                                                }
                                                user?.updateProfile(profileUpdates)?.addOnCompleteListener { task ->
                                                    if (task.isSuccessful) {
                                                        updateFirestoreAndFinish(newPhotoUrl)
                                                    } else {
                                                        isUpdating = false
                                                        showEditDialog = false
                                                        Toast.makeText(context, "Failed to update profile", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !isUpdating && usernameError == null
                                    ) {
                                        if (isUpdating) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Saving...")
                                        } else {
                                            Text("Save")
                                        }
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = { showEditDialog = false },
                                        enabled = !isUpdating
                                    ) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }
                    Spacer(modifier = Modifier.height(24.dp))

                    // ── Active Trackers Section ──
                    var showTrackerPicker by remember { mutableStateOf(false) }
                    val trackerManager: eu.kanade.tachiyomi.data.track.TrackerManager = remember { uy.kohesive.injekt.Injekt.get() }
                    val activeTrackers by trackerManager.loggedInTrackersFlow().collectAsState(initial = trackerManager.loggedInTrackers())

                    if (activeTrackers.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            activeTrackers.forEach { tracker ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White.copy(alpha = 0.05f),
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        androidx.compose.foundation.Image(
                                            painter = androidx.compose.ui.res.painterResource(id = tracker.getLogo()),
                                            contentDescription = tracker.name,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            }
                            
                            // + Icon at the end
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable { showTrackerPicker = true }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add Tracker",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        // NO ACTIVE TRACKERS -> Show rounded square with [+ Add trackers]
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .height(64.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .clickable { showTrackerPicker = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add Tracker",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Add trackers",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    
                    if (showTrackerPicker) {
                        androidx.compose.material3.ModalBottomSheet(
                            onDismissRequest = { showTrackerPicker = false },
                            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                            containerColor = MaterialTheme.colorScheme.background,
                            dragHandle = {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 14.dp, bottom = 6.dp)
                                        .size(width = 36.dp, height = 4.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                                )
                            },
                            contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0) } 
                        ) {
                            eu.kanade.presentation.more.settings.PreferenceScreen(
                                items = eu.kanade.presentation.more.settings.screen.SettingsTrackingScreen.getPreferences(),
                                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // ── Top 5 Favorites Section ──
                    var selectedFavTab by remember { mutableIntStateOf(0) }
                    val favTabs = listOf("Manga", "Novels")
                    val currentFavList = if (selectedFavTab == 0) topManga else topNovels

                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Top 5 Favorites",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                            TextButton(onClick = {
                                navigator.push(FavoritePickerScreen(isNovel = selectedFavTab == 1, existing = currentFavList))
                            }) {
                                Text("Edit", color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            favTabs.forEachIndexed { index, title ->
                                FilterChip(
                                    selected = selectedFavTab == index,
                                    onClick = { selectedFavTab = index },
                                    label = { Text(title) },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (currentFavList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "No favorites yet.\nTap Edit to add your Top 5!",
                                    color = Color.White.copy(alpha = 0.5f),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            // Bottom sheet state for manga info
                            var selectedFav by remember { mutableStateOf<eu.kanade.tachiyomi.data.sync.FavoriteManga?>(null) }

                            androidx.compose.foundation.lazy.LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(items = currentFavList, key = { it.title }) { fav ->
                                    val index = currentFavList.indexOf(fav)
                                    Box(
                                        modifier = Modifier
                                            .width(110.dp)
                                            .height(160.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { selectedFav = fav },
                                    ) {
                                        AsyncImage(
                                            model = tachiyomi.domain.manga.model.MangaCover(mangaId = fav.mangaUrl.hashCode().toLong(), sourceId = fav.sourceId, isMangaFavorite = false, url = fav.thumbnailUrl, lastModified = 0L),
                                            contentDescription = fav.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                        // Gradient overlay
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .align(Alignment.BottomCenter)
                                                .background(
                                                    brush = Brush.verticalGradient(
                                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                                                    ),
                                                )
                                                .padding(6.dp),
                                        ) {
                                            Text(
                                                text = fav.title,
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 2,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            )
                                        }
                                        // Rank badge
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(4.dp)
                                                .size(22.dp)
                                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "${index + 1}",
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }

                            // Info Bottom Sheet
                            if (selectedFav != null) {
                                val fav = selectedFav!!
                                val hasExtension = sourceManager.get(fav.sourceId) != null

                                androidx.compose.ui.window.Dialog(onDismissRequest = { selectedFav = null }) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color.Black,
                                        modifier = Modifier.fillMaxWidth().wrapContentHeight()
                                    ) {
                                        Box {
                                            // Background Image
                                            AsyncImage(
                                                model = tachiyomi.domain.manga.model.MangaCover(mangaId = fav.mangaUrl.hashCode().toLong(), sourceId = fav.sourceId, isMangaFavorite = false, url = fav.thumbnailUrl, lastModified = 0L),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxWidth().height(250.dp)
                                            )
                                            // Gradient Overlay
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(250.dp)
                                                    .background(
                                                        Brush.verticalGradient(
                                                            0.0f to Color.Transparent,
                                                            0.5f to Color.Black.copy(alpha = 0.5f),
                                                            1.0f to Color.Black
                                                        )
                                                    )
                                            )
                                            
                                            // Content
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(24.dp)
                                            ) {
                                                Spacer(modifier = Modifier.height(100.dp)) // push content down a bit to show the background
                                                
                                                Text(
                                                    text = fav.title,
                                                    style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                if (fav.author.isNotEmpty()) {
                                                    Text("by ${fav.author}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                                                }
                                                Text("Source: ${fav.sourceName}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                                                if (fav.genres.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(
                                                        fav.genres.take(3).joinToString(" • "),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                                if (fav.description.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.height(16.dp))
                                                    Text(
                                                        fav.description.take(200) + if (fav.description.length > 200) "..." else "",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color.White.copy(alpha = 0.8f)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(24.dp))
                                                
                                                // Buttons
                                                if (hasExtension) {
                                                    Button(
                                                        onClick = {
                                                            selectedFav = null
                                                            // Navigate to MangaScreen by first finding the local manga
                                                            scope.launch {
                                                                val mangaRepo: tachiyomi.domain.manga.repository.MangaRepository = Injekt.get()
                                                                val localManga = withContext(Dispatchers.IO) {
                                                                    mangaRepo.getMangaByUrlAndSourceId(fav.mangaUrl, fav.sourceId)
                                                                }
                                                                if (localManga != null) {
                                                                    navigator.push(eu.kanade.tachiyomi.ui.manga.MangaScreen(localManga.id, fromSource = true))
                                                                } else {
                                                                    // Manga not in local DB => browse to it from source
                                                                    navigator.push(eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen(fav.sourceId, fav.mangaUrl))
                                                                }
                                                            }
                                                        },
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Text("Read")
                                                    }
                                                } else {
                                                    Text(
                                                        "Extension '${fav.sourceName}' is not installed.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.error,
                                                    )
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    OutlinedButton(
                                                        onClick = {
                                                            selectedFav = null
                                                            navigator.push(eu.kanade.tachiyomi.ui.browse.BrowseTab)
                                                        },
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Text("Get Extension")
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(8.dp))
                                                TextButton(
                                                    onClick = { selectedFav = null },
                                                    modifier = Modifier.align(Alignment.End)
                                                ) {
                                                    Text("Close", color = Color.White)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                // Background Image
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(eu.kanade.tachiyomi.R.drawable.login_bg),
                    contentDescription = "Background",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                
                // Theme-aware Overlay
                val overlayColor = MaterialTheme.colorScheme.background.copy(alpha = 0.85f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(overlayColor)
                )

                // Top Bar for Back Button (Transparent)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    IconButton(onClick = { if (!navigator.pop()) (context as? android.app.Activity)?.finish() }) {
                        Icon(Icons.Default.Close, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
                    }
                }

                // Main Content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // App Logo (rounded square)
                    AsyncImage(
                        model = eu.kanade.tachiyomi.R.mipmap.ic_launcher,
                        contentDescription = "Wammy Logo",
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(24.dp))
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // App Title
                    Text(
                        text = "Wammy",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    
                    // App Subtitle
                    Text(
                        text = "Manga • Manhwa • Novels",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(64.dp))
                    
                    // Welcome Text
                    Text(
                        text = "Welcome",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    
                    // Welcome Subtitle
                    Text(
                        text = "Sign in with your Google account\nto continue to Wammy.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp, bottom = 48.dp)
                    )
                    
                    if (authErrorMessage != null) {
                        AlertDialog(
                            onDismissRequest = { authErrorMessage = null },
                            title = { Text("Sign-In Status") },
                            text = { Text(authErrorMessage!!) },
                            confirmButton = {
                                TextButton(onClick = { authErrorMessage = null }) {
                                    Text("OK")
                                }
                            }
                        )
                    }

                    // Sign In Button
                    Surface(
                        onClick = {
                            if (isSigningIn) return@Surface
                            isSigningIn = true
                            scope.launch {
                                var credentialSuccess = false
                                try {
                                    val activity = context.findActivity() ?: (context as? android.app.Activity)
                                    val credentialManager = CredentialManager.create(context)
                                    val request = GetCredentialRequest.Builder()
                                        .addCredentialOption(GetGoogleIdOption.Builder()
                                            .setFilterByAuthorizedAccounts(false)
                                            .setServerClientId(webClientId)
                                            .setAutoSelectEnabled(false)
                                            .build())
                                        .build()

                                    val result = credentialManager.getCredential(activity ?: context, request)
                                    val credential = result.credential
                                    if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                                        val idToken = googleIdTokenCredential.idToken
                                        if (idToken.isNotBlank()) {
                                            credentialSuccess = true
                                            signInWithFirebaseToken(idToken)
                                        }
                                    }
                                } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                                    android.util.Log.d("ProfileScreen", "User cancelled credential picker")
                                    isSigningIn = false
                                    return@launch
                                } catch (e: Exception) {
                                    android.util.Log.w("ProfileScreen", "CredentialManager failed (${e::class.java.simpleName}: ${e.message}), trying legacy fallback...", e)
                                }

                                if (!credentialSuccess) {
                                    launchLegacyGoogleSignIn()
                                }
                            }
                        },
                        shape = RoundedCornerShape(percent = 50),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Google Icon & Text
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(eu.kanade.tachiyomi.R.drawable.ic_google),
                                    contentDescription = "Google Logo",
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(24.dp)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = if (isSigningIn) "Signing in..." else "Sign in with Google",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            if (isSigningIn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    androidx.compose.material.icons.Icons.Filled.ArrowForward,
                                    contentDescription = "Forward",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatItem(value: String, label: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = value, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
        }
    }
}