package eu.kanade.tachiyomi.ui.profile

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import cafe.adriel.voyager.navigator.Navigator
import coil3.compose.AsyncImage
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.auth.AuthConstants
import eu.kanade.tachiyomi.data.sync.AuthorizationResult
import eu.kanade.tachiyomi.data.sync.DriveApiHelper
import eu.kanade.tachiyomi.data.sync.DriveSyncWorker
import eu.kanade.tachiyomi.ui.home.HomeScreen
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private fun android.content.Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginBottomSheet(
    navigator: Navigator,
    driveApiHelper: DriveApiHelper
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val basePreferences = remember { Injekt.get<BasePreferences>() }
    val webClientId = AuthConstants.WEB_CLIENT_ID

    // Request notification permission on Android 13+ so sync notifications can be shown
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: best effort request */ }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var isSigningIn by remember { mutableStateOf(false) }
    var authErrorMessage by remember { mutableStateOf<String?>(null) }

    fun dismissAndSkip() {
        basePreferences.hasCompletedInitialLogin.set(true)
        Toast.makeText(context, "You can sign in anytime from the Profile tab.", Toast.LENGTH_SHORT).show()
        if (navigator.size == 1 && navigator.lastItem is HomeScreen) {
            // Already on HomeScreen, no navigation needed
        }
    }

    val driveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        isSigningIn = false
        basePreferences.hasCompletedInitialLogin.set(true)
        val appContext = context.applicationContext
        if (result.resultCode == Activity.RESULT_OK) {
            driveApiHelper.handleAuthorizationResult(result.data)
            Toast.makeText(context, "Google Drive connected! Restoring your library...", Toast.LENGTH_SHORT).show()
            DriveSyncWorker.scheduleRestoreCheck(appContext, immediate = true)
            DriveSyncWorker.schedulePeriodicBackup(appContext)
        } else {
            Toast.makeText(context, "Signed in! You can enable Google Drive sync later in More -> Cloud Sync.", Toast.LENGTH_SHORT).show()
        }
    }

    fun signInWithFirebaseToken(idToken: String) {
        isSigningIn = true
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
        scope.launch {
            try {
                val authResult = withTimeoutOrNull(15_000L) {
                    FirebaseAuth.getInstance().signInWithCredential(firebaseCredential).await()
                }
                if (authResult == null) {
                    isSigningIn = false
                    authErrorMessage = "Connection timed out while authenticating with Firebase.\n\nPlease check your internet connection, or disable any active Private DNS/adblockers and try again."
                    return@launch
                }

                val appContext = context.applicationContext
                try {
                    when (val driveResult = withTimeoutOrNull(10_000L) { driveApiHelper.requestDriveAuthorization() }) {
                        is AuthorizationResult.NeedsConsent -> {
                            val intentSenderRequest = IntentSenderRequest.Builder(driveResult.pendingIntent).build()
                            try {
                                driveConsentLauncher.launch(intentSenderRequest)
                            } catch (e: Exception) {
                                android.util.Log.e("LoginSheet", "Drive Consent Launcher error", e)
                            }
                        }
                        is AuthorizationResult.Success -> {
                            isSigningIn = false
                            basePreferences.hasCompletedInitialLogin.set(true)
                            Toast.makeText(context, "Signed in successfully! Checking for restore...", Toast.LENGTH_SHORT).show()
                            DriveSyncWorker.scheduleRestoreCheck(appContext, immediate = true)
                            DriveSyncWorker.schedulePeriodicBackup(appContext)
                        }
                        is AuthorizationResult.Error -> {
                            isSigningIn = false
                            basePreferences.hasCompletedInitialLogin.set(true)
                            android.util.Log.e("LoginSheet", "Drive Auth Error: ${driveResult.message}")
                            DriveSyncWorker.scheduleRestoreCheck(appContext, immediate = true)
                        }
                        null -> {
                            isSigningIn = false
                            basePreferences.hasCompletedInitialLogin.set(true)
                            Toast.makeText(context, "Signed in! Google Drive sync can be connected in settings.", Toast.LENGTH_SHORT).show()
                            DriveSyncWorker.scheduleRestoreCheck(appContext, immediate = true)
                        }
                    }
                } catch (e: Exception) {
                    isSigningIn = false
                    basePreferences.hasCompletedInitialLogin.set(true)
                    android.util.Log.e("LoginSheet", "Drive Auth Exception", e)
                    DriveSyncWorker.scheduleRestoreCheck(appContext, immediate = true)
                }
            } catch (ex: Exception) {
                isSigningIn = false
                android.util.Log.e("LoginSheet", "Firebase Auth Failed", ex)
                authErrorMessage = "Firebase Authentication Failed:\n\n${ex.localizedMessage ?: ex.message ?: "Unknown Firebase error"}\n\nPlease check your internet connection."
            }
        }
    }

    val legacyGoogleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data != null) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account?.idToken
                if (!idToken.isNullOrBlank()) {
                    signInWithFirebaseToken(idToken)
                } else {
                    isSigningIn = false
                    authErrorMessage = "Sign in failed: Google returned an empty ID token."
                }
            } catch (e: ApiException) {
                isSigningIn = false
                if (e.statusCode == com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
                    android.util.Log.d("LoginSheet", "User cancelled Google Sign-In picker")
                } else {
                    android.util.Log.e("LoginSheet", "GoogleSignIn ApiException: ${e.statusCode}", e)
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
                    authErrorMessage = message
                }
            }
        } else {
            isSigningIn = false
            if (result.resultCode != Activity.RESULT_CANCELED) {
                authErrorMessage = "Sign in failed with result code: ${result.resultCode}"
            }
        }
    }

    fun launchLegacyGoogleSignIn() {
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestEmail()
                .requestScopes(com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/drive.appdata"))
                .build()
            val client = GoogleSignIn.getClient(context, gso)
            // Non-blocking best-effort sign-out so account picker resets without stalling
            try { client.signOut() } catch (_: Exception) {}
            // Launch sign-in picker immediately without waiting on an async callback
            legacyGoogleSignInLauncher.launch(client.signInIntent)
        } catch (e: Exception) {
            isSigningIn = false
            android.util.Log.e("LoginSheet", "Failed to launch legacy Google Sign In", e)
            authErrorMessage = "Google Play Services sign-in unavailable on this device:\n${e.message}"
        }
    }

    fun startGoogleSignIn() {
        if (isSigningIn) return
        isSigningIn = true

        // 1. Pre-flight check: Is Google Play Services installed and operational on this device?
        val gmsAvailability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (gmsAvailability != ConnectionResult.SUCCESS) {
            isSigningIn = false
            val reason = when (gmsAvailability) {
                ConnectionResult.SERVICE_MISSING -> "Google Play Services is not installed on this device."
                ConnectionResult.SERVICE_DISABLED -> "Google Play Services is disabled on this device."
                ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> "Google Play Services requires an update on this device."
                ConnectionResult.SERVICE_INVALID -> "Google Play Services signature verification failed."
                else -> "Google Play Services error (code: $gmsAvailability)."
            }
            authErrorMessage = "$reason\n\nGoogle Sign-In is unavailable on devices without functioning Google services (such as Huawei, Amazon Fire, or custom de-Googled ROMs).\n\nYou can continue using Wammy offline by tapping 'Skip for now'."
            return
        }

        scope.launch {
            var credentialSuccess = false
            try {
                val activity = context.findActivity() ?: (context as? Activity)
                val credentialManager = CredentialManager.create(context)
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(false)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = withTimeoutOrNull(10_000L) {
                    credentialManager.getCredential(activity ?: context, request)
                }

                if (result != null) {
                    val credential = result.credential
                    if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        val idToken = googleIdTokenCredential.idToken
                        if (idToken.isNotBlank()) {
                            credentialSuccess = true
                            signInWithFirebaseToken(idToken)
                        }
                    }
                } else {
                    android.util.Log.w("LoginSheet", "CredentialManager timed out after 10s, falling back to legacy Google Sign-In")
                }
            } catch (e: GetCredentialCancellationException) {
                android.util.Log.d("LoginSheet", "User cancelled credential picker")
                isSigningIn = false
                return@launch
            } catch (e: Exception) {
                android.util.Log.w("LoginSheet", "CredentialManager failed (${e::class.java.simpleName}: ${e.message}), trying legacy fallback...", e)
            }

            if (!credentialSuccess) {
                launchLegacyGoogleSignIn()
            }
        }
    }

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

    ModalBottomSheet(
        onDismissRequest = { dismissAndSkip() },
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Logo
            AsyncImage(
                model = eu.kanade.tachiyomi.R.mipmap.ic_launcher,
                contentDescription = "Wammy Logo",
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Welcome to Wammy",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Sign in with your Google account to sync your library, favorites, and progress across devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp, bottom = 32.dp)
            )

            // Sign In Button
            Surface(
                onClick = { if (!isSigningIn) startGoogleSignIn() },
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(eu.kanade.tachiyomi.R.drawable.ic_google),
                            contentDescription = "Google Logo",
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(24.dp)
                                .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = if (isSigningIn) "Signing in..." else "Sign in with Google",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    if (isSigningIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Filled.ArrowForward,
                            contentDescription = "Forward",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Skip for now button
            TextButton(
                onClick = { dismissAndSkip() },
                enabled = !isSigningIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "Skip for now",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
