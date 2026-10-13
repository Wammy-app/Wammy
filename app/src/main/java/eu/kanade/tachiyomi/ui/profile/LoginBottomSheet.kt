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
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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

    fun dismissAndSkip() {
        basePreferences.hasCompletedInitialLogin.set(true)
        Toast.makeText(context, "You can sign in anytime from the Profile tab.", Toast.LENGTH_SHORT).show()
        if (navigator.size == 1) navigator.replaceAll(HomeScreen)
    }

    val driveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            DriveSyncWorker.scheduleRestoreCheck(context)
            Toast.makeText(context, "Google Drive connected! Checking for restore...", Toast.LENGTH_SHORT).show()
        }
        if (navigator.size == 1) navigator.replaceAll(HomeScreen)
    }

    fun signInWithFirebaseToken(idToken: String) {
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
        FirebaseAuth.getInstance().signInWithCredential(firebaseCredential).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                basePreferences.hasCompletedInitialLogin.set(true)
                Toast.makeText(context, "Signed in successfully!", Toast.LENGTH_SHORT).show()

                scope.launch {
                    when (val authResult = driveApiHelper.requestDriveAuthorization()) {
                        is AuthorizationResult.NeedsConsent -> {
                            val intentSenderRequest = IntentSenderRequest.Builder(authResult.pendingIntent).build()
                            driveConsentLauncher.launch(intentSenderRequest)
                        }
                        is AuthorizationResult.Success -> {
                            DriveSyncWorker.scheduleRestoreCheck(context)
                            if (navigator.size == 1) navigator.replaceAll(HomeScreen)
                        }
                        is AuthorizationResult.Error -> {
                            android.util.Log.e("LoginSheet", "Drive Auth Error: ${authResult.message}")
                            if (navigator.size == 1) navigator.replaceAll(HomeScreen)
                        }
                    }
                }
            } else {
                val ex = task.exception
                android.util.Log.e("LoginSheet", "Firebase Auth Failed", ex)
                Toast.makeText(context, "Auth Failed: ${ex?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val legacyGoogleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account?.idToken
                if (!idToken.isNullOrBlank()) {
                    signInWithFirebaseToken(idToken)
                } else {
                    Toast.makeText(context, "Sign in failed: ID token is null", Toast.LENGTH_LONG).show()
                }
            } catch (e: ApiException) {
                android.util.Log.e("LoginSheet", "GoogleSignIn ApiException: ${e.statusCode}", e)
                val message = when (e.statusCode) {
                    10 -> "Developer error (10): Ensure keystore SHA-1 is added in Firebase console."
                    12500 -> "Sign in failed (12500): Check Google Play Services account."
                    else -> "Sign in failed: ${e.statusCode} ${e.message.orEmpty()}"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        } else if (result.resultCode != Activity.RESULT_CANCELED) {
            Toast.makeText(context, "Sign in cancelled or failed (${result.resultCode})", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchLegacyGoogleSignIn() {
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestEmail()
                .build()
            val client = GoogleSignIn.getClient(context, gso)
            legacyGoogleSignInLauncher.launch(client.signInIntent)
        } catch (e: Exception) {
            android.util.Log.e("LoginSheet", "Failed to launch legacy Google Sign In", e)
            Toast.makeText(context, "Google Play Services sign-in unavailable: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun startGoogleSignIn() {
        scope.launch {
            var credentialSuccess = false
            try {
                val credentialManager = CredentialManager.create(context)
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(false)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleIdTokenCredential.idToken
                    if (idToken.isNotBlank()) {
                        credentialSuccess = true
                        signInWithFirebaseToken(idToken)
                    }
                }
            } catch (e: GetCredentialCancellationException) {
                // User intentionally cancelled the picker
                android.util.Log.d("LoginSheet", "User cancelled credential picker")
                return@launch
            } catch (e: Exception) {
                android.util.Log.w("LoginSheet", "CredentialManager failed (${e::class.java.simpleName}: ${e.message}), falling back to GoogleSignInClient", e)
            }

            if (!credentialSuccess) {
                launchLegacyGoogleSignIn()
            }
        }
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
                onClick = { startGoogleSignIn() },
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
                            text = "Sign in with Google",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Icon(
                        Icons.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Skip for now button
            TextButton(
                onClick = { dismissAndSkip() },
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
