package eu.kanade.tachiyomi.ui.profile

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import coil3.compose.AsyncImage
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import eu.kanade.tachiyomi.data.sync.DriveApiHelper
import eu.kanade.tachiyomi.data.sync.AuthorizationResult
import kotlinx.coroutines.flow.first
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
    val webClientId = eu.kanade.tachiyomi.data.auth.AuthConstants.WEB_CLIENT_ID

    val driveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            eu.kanade.tachiyomi.data.sync.DriveSyncWorker.scheduleRestoreCheck(context)
            Toast.makeText(context, "Google Drive connected! Checking for restore...", Toast.LENGTH_SHORT).show()
        }
        if (navigator.size == 1) navigator.replaceAll(eu.kanade.tachiyomi.ui.home.HomeScreen)
    }

    ModalBottomSheet(
        onDismissRequest = { /* Prevent dismiss, mandatory login */ },
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != androidx.compose.material3.SheetValue.Hidden })
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 24.dp),
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
            
            Spacer(modifier = Modifier.height(24.dp))
            
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
                modifier = Modifier.padding(top = 12.dp, bottom = 40.dp)
            )
            
            // Sign In Button
            Surface(
                onClick = {
                    scope.launch {
                        try {
                            val credentialManager = CredentialManager.create(context)
                            val request1: GetCredentialRequest = GetCredentialRequest.Builder()
                                .addCredentialOption(GetGoogleIdOption.Builder()
                                    .setFilterByAuthorizedAccounts(true)
                                    .setServerClientId(webClientId)
                                    .build())
                                .build()
                                
                            val result = try {
                                credentialManager.getCredential(context, request1)
                            } catch (e: androidx.credentials.exceptions.NoCredentialException) {
                                val request2 = GetCredentialRequest.Builder()
                                    .addCredentialOption(GetGoogleIdOption.Builder()
                                        .setFilterByAuthorizedAccounts(false)
                                        .setServerClientId(webClientId)
                                        .build())
                                    .build()
                                credentialManager.getCredential(context, request2)
                            }
                            val credential = result.credential
                            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                                FirebaseAuth.getInstance().signInWithCredential(firebaseCredential).addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        uy.kohesive.injekt.Injekt.get<eu.kanade.domain.base.BasePreferences>()
                                            .hasCompletedInitialLogin.set(true)
                                        Toast.makeText(context, "Signed in successfully!", Toast.LENGTH_SHORT).show()
                                        
                                        scope.launch {
                                            when (val authResult = driveApiHelper.requestDriveAuthorization()) {
                                                is AuthorizationResult.NeedsConsent -> {
                                                    val intentSenderRequest = androidx.activity.result.IntentSenderRequest.Builder(authResult.pendingIntent).build()
                                                    driveConsentLauncher.launch(intentSenderRequest)
                                                }
                                                is AuthorizationResult.Success -> {
                                                    eu.kanade.tachiyomi.data.sync.DriveSyncWorker.scheduleRestoreCheck(context)
                                                    if (navigator.size == 1) navigator.replaceAll(eu.kanade.tachiyomi.ui.home.HomeScreen)
                                                }
                                                is AuthorizationResult.Error -> {
                                                    android.util.Log.e("LoginSheet", "Drive Auth Error: ${authResult.message}")
                                                    if (navigator.size == 1) navigator.replaceAll(eu.kanade.tachiyomi.ui.home.HomeScreen)
                                                }
                                            }
                                        }
                                    } else {
                                        Toast.makeText(context, "Auth Failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Sign in failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
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
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
