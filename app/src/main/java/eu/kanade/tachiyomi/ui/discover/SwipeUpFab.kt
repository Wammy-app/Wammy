package eu.kanade.tachiyomi.ui.discover

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.library.service.LibraryPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.util.isTabletUi
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun SwipeUpFab(
    onSlideUpTriggered: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
    val initialHasShown = remember { libraryPreferences.hasShownSwipeUpFabTutorial.get() }
    var hasShownTutorial by remember { mutableStateOf(initialHasShown) }
    var showTutorialOverlay by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    val maxDragPx = with(LocalDensity.current) { -72.dp.toPx() }
    val clampPx = with(LocalDensity.current) { -80.dp.toPx() }
    val context = LocalContext.current
    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val fabSizeDp by uiPreferences.fabSizeDp.collectAsState()
    val animatedOffset by animateFloatAsState(targetValue = dragOffset)

    if (showTutorialOverlay) {
        var tutorialStep by remember { mutableIntStateOf(1) }
        
        val fakeFabScale by animateFloatAsState(
            targetValue = if (tutorialStep == 2) 1.2f else 1.0f,
            animationSpec = tween(400, easing = FastOutSlowInEasing)
        )
        
        val fakeFabOffsetY by animateFloatAsState(
            targetValue = if (tutorialStep == 3) -150f else 0f,
            animationSpec = tween(600, easing = FastOutSlowInEasing)
        )
        
        val fakeFabAlpha by animateFloatAsState(
            targetValue = if (tutorialStep == 4) 0f else 1f,
            animationSpec = tween(300)
        )

        Dialog(
            onDismissRequest = {
                showTutorialOverlay = false
                hasShownTutorial = true
                libraryPreferences.hasShownSwipeUpFabTutorial.set(true)
            },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {
                        if (tutorialStep < 4) {
                            tutorialStep++
                        }
                    }
            ) {
                
                // Left Text 
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 90.dp, end = 90.dp) // Left of the FAB
                ) {
                    androidx.compose.animation.Crossfade(
                        targetState = tutorialStep,
                        animationSpec = tween(300)
                    ) { step ->
                        val text = when (step) {
                            1 -> "This button switches between\nManga and Novel worlds."
                            2 -> "First, press and\nhold the button..."
                            3 -> "...then simply swipe up!"
                            else -> ""
                        }
                        if (text.isNotEmpty()) {
                            Text(
                                text = text,
                                color = Color.White,
                                fontSize = 18.sp,
                                lineHeight = 24.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                modifier = Modifier.padding(bottom = 16.dp, end = 16.dp)
                            )
                        }
                    }
                }
                
                // Center text for Step 4
                if (tutorialStep == 4) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "That's how you change\nfrom Manga to Novel!",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(onClick = {
                            showTutorialOverlay = false
                            hasShownTutorial = true
                            libraryPreferences.hasShownSwipeUpFabTutorial.set(true)
                        }) {
                            Text("Got it!")
                        }
                    }
                }

                // Tap anywhere blinking text at bottom
                if (tutorialStep < 4) {
                    val infiniteTransition = rememberInfiniteTransition()
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800),
                            repeatMode = RepeatMode.Reverse
                        )
                    )
                    Text(
                        text = "Tap anywhere to continue",
                        color = Color.White.copy(alpha = alpha),
                        fontSize = 14.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                    )
                }

                // Spotlight Fake FAB in bottom right
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .padding(bottom = 80.dp) // Approximate padding above bottom nav
                        .offset { IntOffset(0, fakeFabOffsetY.toInt()) }
                        .graphicsLayer {
                            scaleX = fakeFabScale
                            scaleY = fakeFabScale
                            alpha = fakeFabAlpha
                        }
                ) {
                    val customImageRes = eu.kanade.tachiyomi.R.drawable.devil_fruit

                    Box(
                        modifier = Modifier
                            .size(fabSizeDp.dp) // slightly larger to compensate for no background padding
                    ) {
                        Image(
                            painter = painterResource(id = customImageRes),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }

    val bottomPadding = if (isTabletUi()) 0.dp else 80.dp

    Box(
        modifier = modifier
            .padding(bottom = bottomPadding)
            .offset { IntOffset(0, animatedOffset.toInt()) }
            .pointerInput(hasShownTutorial) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    if (!hasShownTutorial) {
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })

                        showTutorialOverlay = true
                        return@awaitEachGesture
                    }

                    val longPress = awaitLongPressOrCancellation(down.id)

                    if (longPress != null) {
                        // Gesture activated! Fire the initial tick.
                        if (vibrator.hasVibrator()) {
                            vibrator.vibrate(VibrationEffect.createOneShot(50L, VibrationEffect.DEFAULT_AMPLITUDE))
                        }

                        var triggered = false

                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull()
                            if (change != null && change.pressed) {
                                val dy = change.position.y - down.position.y
                                if (dy < 0) {
                                    // Clamp the drag offset so it doesn't fly off screen
                                    dragOffset = dy.coerceAtLeast(clampPx)

                                    if (dragOffset <= maxDragPx && !triggered) {
                                        triggered = true
                                        if (vibrator.hasVibrator()) {
                                            vibrator.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
                                        }
                                        onSlideUpTriggered()
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        // Force reset on release unconditionally
                        dragOffset = 0f
                    }
                }
            }
    ) {
        val customImageRes = eu.kanade.tachiyomi.R.drawable.devil_fruit

        // --- VISUAL OPTIONS FOR FAB ---
        // Option A (Standard FAB with inside image):
        /*
        FloatingActionButton(
            onClick = {
                if (!hasShownTutorial) {
                    showTutorialOverlay = true
                }
            },
            modifier = Modifier.size(56.dp)
        ) {
            Image(
                painter = painterResource(id = customImageRes),
                contentDescription = "Swipe up",
                contentScale = ContentScale.Inside,
                modifier = Modifier.padding(8.dp)
            )
        }
        */

        // Option B (Freeform Image without background/shadow):
        FloatingActionButton(
            onClick = {
                if (!hasShownTutorial) {
                    showTutorialOverlay = true
                }
            },
            modifier = Modifier.size(fabSizeDp.dp),
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            containerColor = Color.Transparent,
            contentColor = Color.Unspecified
        ) {
            Image(
                painter = painterResource(id = customImageRes),
                contentDescription = "Swipe up",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
