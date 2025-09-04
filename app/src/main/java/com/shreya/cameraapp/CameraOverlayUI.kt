package com.shreya.cameraapp

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.CircleShape
import android.widget.Toast
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException



/**
 * NEW Compact Camera Overlay UI - Shows minimal, actionable suggestions that don't block camera view
 */
@Composable
fun CameraOverlayUI(
    suggestions: List<AIEducationHelper.CameraSuggestion>,
    isEducationMode: Boolean,
    showGrid: Boolean = false,
    cameraManager: CameraManager? = null,
    aiEducationHelper: AIEducationHelper, // ADD THIS PARAMETER
    showApplyAllButton: Boolean = false,
    onApplyAll: (String) -> Unit = {},
    onCameraStateChanged: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Filter actionable suggestions
    val actionableSuggestions = suggestions.filter { suggestion ->
        // Define which suggestions are actionable based on title/content
        suggestion.title.contains("Flash", ignoreCase = true) ||
                suggestion.title.contains("Zoom", ignoreCase = true) ||
                suggestion.title.contains("Grid", ignoreCase = true) ||
                suggestion.title.contains("Portrait", ignoreCase = true) ||
                suggestion.title.contains("Night", ignoreCase = true) ||
                suggestion.title.contains("Camera", ignoreCase = true)
    }

    Box(modifier = modifier) {
        // Show grid overlay when enabled
        if (showGrid) {
            CameraGridOverlay(modifier = Modifier.fillMaxSize())
        }

        // Show COMPACT suggestions only in education mode
        if (isEducationMode && suggestions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Apply All button
                AnimatedVisibility(
                    visible = showApplyAllButton && actionableSuggestions.isNotEmpty(),
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .clickable {
                                // FIXED: Direct call without lambda wrapper
                                applyAllSuggestions(
                                    actionableSuggestions, // Use filtered suggestions
                                    cameraManager,
                                    aiEducationHelper, // This parameter is now available
                                    onApplyAll,
                                    onCameraStateChanged
                                )
                            },
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.Black.copy(alpha = 0.9f)
                        )
                    ) {
                        Text(
                            text = " Apply ${actionableSuggestions.size} Suggestions",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Individual suggestion chips below
                CompactSuggestionBar(
                    suggestions = suggestions,
                    cameraManager = cameraManager,
                    onActionApplied = { action, message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        onCameraStateChanged()
                    }
                )
            }
        }

        if (isEducationMode) {
            RealTimeQualityIndicators(
                frameAnalysis = null, // You'll need to pass this from MainActivity
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

/**
 * Compact suggestion bar that doesn't block camera view
 */
@Composable
fun CompactSuggestionBar(
    suggestions: List<AIEducationHelper.CameraSuggestion>,
    cameraManager: CameraManager?,
    onActionApplied: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Show only the top 2 most important suggestions
    val topSuggestions = suggestions.sortedBy { it.priority }.take(4)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 16.dp),

                shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.8f)  // Change from Color(0xDD1A1A1A) to black
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // First row - 2 suggestions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                topSuggestions.take(2).forEach { suggestion ->
                    CompactActionChip(
                        suggestion = suggestion,
                        cameraManager = cameraManager,
                        onActionApplied = onActionApplied,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Second row - remaining suggestions
            if (topSuggestions.size > 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    topSuggestions.drop(2).forEach { suggestion ->
                        CompactActionChip(
                            suggestion = suggestion,
                            cameraManager = cameraManager,
                            onActionApplied = onActionApplied,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RealTimeQualityIndicators(
    frameAnalysis: RealTimeVisionAnalyzer.FrameAnalysis?,
    modifier: Modifier = Modifier
) {
    if (frameAnalysis?.analysisSuccess != true) return

    Column(
        modifier = modifier
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Brightness indicator
        QualityBar("Brightness", frameAnalysis.brightness / 255.0, Color.Yellow)

        // Sharpness indicator
        QualityBar("Sharpness", (frameAnalysis.blurLevel / 300.0).coerceIn(0.0, 1.0), Color.Green)

        // Composition score
        QualityBar("Composition", frameAnalysis.compositionScore, Color.Blue)
    }
}

@Composable
fun QualityBar(label: String, value: Double, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.width(120.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color.White,
            modifier = Modifier.width(60.dp)
        )

        LinearProgressIndicator(
            progress = value.toFloat().coerceIn(0f, 1f),
            color = color,
            modifier = Modifier
                .height(4.dp)
                .weight(1f)
        )
    }
}

/**
 * One-tap action chip for each suggestion
 */
@Composable
fun CompactActionChip(
    suggestion: AIEducationHelper.CameraSuggestion,
    cameraManager: CameraManager?,
    onActionApplied: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .clickable {
                isPressed = true

                // Apply the action
                applyQuickAction(suggestion, cameraManager) { action, message ->
                    onActionApplied(action, message)

                    // ADD THIS: Force camera state refresh after 500ms
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(500)
                        // This delay allows camera to update its internal state
                        // The periodic AI loop in MainActivity will pick up changes
                    }
                }
            }
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPressed) {
                Color.White.copy(alpha = 0.9f)  // White when pressed
            } else {
                Color.Black.copy(alpha = 0.8f)  // Black background
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 100.dp, max = 120.dp)
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = suggestion.icon,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )

            Text(
                text = suggestion.title,
                color = if (isPressed) Color.Black else Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            Text(
                text = suggestion.description,
                color = if (isPressed) Color.Black.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.9f),
                fontSize = 7.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                lineHeight = 10.sp
            )
        }
    }

    // Reset pressed state after animation
    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(200)
            isPressed = false
        }
    }
}

/**
 * Apply the suggested action when user taps the chip
 */
fun applyQuickAction(
    suggestion: AIEducationHelper.CameraSuggestion,
    cameraManager: CameraManager?,
    onActionApplied: (String, String) -> Unit
) {
    if (cameraManager == null) {
        onActionApplied("ERROR", "Camera not ready")
        return
    }

    try {
        val zoomMatch = Regex("""(\d+\.?\d*)x""").find(suggestion.title)
        if (zoomMatch != null) {
            val targetZoom = zoomMatch.groupValues[1].toFloat()
            val newZoom = cameraManager.setZoomRatio(targetZoom)
            onActionApplied("ZOOM", "Zoom set to ${String.format("%.1f", newZoom)}x")
            return
        }

        when {
            suggestion.title.contains("Flash", ignoreCase = true) -> {
                val flashEnabled = cameraManager.toggleFlash()
                val message = if (flashEnabled) "Flash turned ON" else "Flash turned OFF"
                onActionApplied("FLASH", message)
                CoroutineScope(Dispatchers.Main).launch {
                    delay(500)
                    // trigger refresh callback here if needed
                }
            }

            suggestion.title.contains("1.2x", ignoreCase = true) -> {
                val newZoom = cameraManager.setZoomRatio(1.2f)
                onActionApplied("ZOOM", "Set zoom to 1.2x")
                CoroutineScope(Dispatchers.Main).launch {
                    delay(500)
                    // trigger refresh callback here if needed
                }
            }

            suggestion.title.contains("1.5x", ignoreCase = true) -> {
                val newZoom = cameraManager.setZoomRatio(1.5f)
                onActionApplied("ZOOM", "Set zoom to 1.5x")
                CoroutineScope(Dispatchers.Main).launch {
                    delay(500)
                    // trigger refresh callback here if needed
                }
            }

            suggestion.title.contains("Zoom In", ignoreCase = true) -> {
                val newZoom = cameraManager.zoomIn()
                onActionApplied("ZOOM", "Zoomed in to ${String.format("%.1f", newZoom)}x")
                CoroutineScope(Dispatchers.Main).launch {
                    delay(500)
                    // trigger refresh callback here if needed
                }
            }

            suggestion.title.contains("Camera", ignoreCase = true) -> {
                val flipped = cameraManager.flipCamera()
                val message = if (flipped) {
                    "Switched to ${if (cameraManager.isUsingFrontCamera()) "front" else "back"} camera"
                } else {
                    "Camera switch failed"
                }
                onActionApplied("CAMERA", message)
                CoroutineScope(Dispatchers.Main).launch {
                    delay(500)
                    // trigger refresh callback here if needed
                }
            }

            suggestion.title.contains("Night", ignoreCase = true) -> {
                cameraManager.switchCameraMode(CameraManager.CameraMode.NIGHT)
                onActionApplied("MODE", "Night mode enabled")
                CoroutineScope(Dispatchers.Main).launch {
                    delay(500)
                    // trigger refresh callback here if needed
                }
            }

            suggestion.title.contains("Grid", ignoreCase = true) -> {
                val gridEnabled = cameraManager.toggleGrid()
                val message = if (gridEnabled) "Grid enabled" else "Grid disabled"
                onActionApplied("GRID", message)
                CoroutineScope(Dispatchers.Main).launch {
                    delay(500)
                    // trigger refresh callback here if needed
                }
            }

            suggestion.title.contains("Portrait", ignoreCase = true) -> {
                cameraManager.switchCameraMode(CameraManager.CameraMode.PORTRAIT)
                onActionApplied("MODE", "Portrait mode enabled - better for people")
            }

            suggestion.title.contains("Stabilization", ignoreCase = true) -> {
                val enabled = cameraManager.toggleStabilization()
                onActionApplied("STABILIZATION", if (enabled) "AI stabilization ON" else "Stabilization OFF")
            }

            suggestion.title.contains("Warm", ignoreCase = true) -> {
                cameraManager.applyFilter("warm")
                onActionApplied("FILTER", "Warm filter applied")
            }

            suggestion.title.contains("Vivid", ignoreCase = true) -> {
                cameraManager.applyFilter("vivid")
                onActionApplied("FILTER", "Vivid filter applied - colors boosted")
            }

            suggestion.title.contains("B&W", ignoreCase = true) -> {
                cameraManager.applyFilter("b&w")
                onActionApplied("FILTER", "Black & White filter applied")
            }

            suggestion.title.contains("Step Back", ignoreCase = true) -> {
                onActionApplied("MOVEMENT", "Take 2-3 steps backwards, then try the shot")
            }

            suggestion.title.contains("Move Closer", ignoreCase = true) -> {
                onActionApplied("MOVEMENT", "Take 1-2 steps closer to your subject")
            }

            suggestion.title.contains("Hold Higher", ignoreCase = true) -> {
                onActionApplied("POSITION", "Lift the phone up to eye level or slightly above")
            }

            suggestion.title.contains("Hold Lower", ignoreCase = true) -> {
                onActionApplied("POSITION", "Lower the phone to chest level for better angle")
            }

            suggestion.title.contains("Window", ignoreCase = true) -> {
                onActionApplied("LIGHTING", "Move closer to a window for natural light")
            }

            suggestion.title.contains("Turn Around", ignoreCase = true) -> {
                onActionApplied("LIGHTING", "Face the other direction - light is behind you")
            }

            suggestion.title.contains("Both Hands", ignoreCase = true) -> {
                onActionApplied("STABILITY", "Hold phone with both hands and brace your elbows")
            }

            else -> {
                // Generic advice (no action to perform)
                onActionApplied("TIP", suggestion.description)
            }
        }

        Log.d("CameraOverlayUI", "Applied action: ${suggestion.title}")

    } catch (e: Exception) {
        Log.e("CameraOverlayUI", "Action failed: ${e.message}")
        onActionApplied("ERROR", "Action failed: ${e.message}")
    }
}

/**
 * Apply all actionable suggestions at once
 */
fun applyAllSuggestions(
    suggestions: List<AIEducationHelper.CameraSuggestion>,
    cameraManager: CameraManager?,
    aiEducationHelper: AIEducationHelper,
    onApplyAll: (String) -> Unit,
    onCameraStateChanged: () -> Unit // ADD THIS PARAMETER
) {
    if (cameraManager == null) {
        onApplyAll("Camera not ready")
        return
    }

    try {
        val actionableSuggestions = suggestions.mapNotNull { suggestion ->
            val action = when {
                suggestion.title.contains("Flash ON", ignoreCase = true) -> AIEducationHelper.QuickAction.FLASH_ON
                suggestion.title.contains("Flash", ignoreCase = true) && suggestion.title.contains("OFF", ignoreCase = true) -> AIEducationHelper.QuickAction.FLASH_OFF
                suggestion.title.contains("Turn on Flash", ignoreCase = true) -> AIEducationHelper.QuickAction.FLASH_ON
                suggestion.title.contains("Turn Flash On", ignoreCase = true) -> AIEducationHelper.QuickAction.FLASH_ON
                suggestion.title.contains("Zoom", ignoreCase = true) && suggestion.title.contains("In", ignoreCase = true) -> AIEducationHelper.QuickAction.ZOOM_IN
                suggestion.title.contains("Zoom", ignoreCase = true) && suggestion.title.contains("Out", ignoreCase = true) -> AIEducationHelper.QuickAction.ZOOM_OUT
                suggestion.title.contains("Grid", ignoreCase = true) -> AIEducationHelper.QuickAction.ENABLE_GRID
                suggestion.title.contains("Portrait", ignoreCase = true) -> AIEducationHelper.QuickAction.ENABLE_PORTRAIT
                suggestion.title.contains("Night", ignoreCase = true) -> AIEducationHelper.QuickAction.ENABLE_NIGHT
                suggestion.title.contains("Camera", ignoreCase = true) -> AIEducationHelper.QuickAction.SWITCH_CAMERA
                else -> null
            }

            action?.let {
                AIEducationHelper.ActionableSuggestion(
                    title = suggestion.title,
                    description = suggestion.description,
                    action = it,
                    icon = suggestion.icon,
                    priority = suggestion.priority
                )
            }
        }

        Log.d("ApplyAll", "Converting ${suggestions.size} suggestions to ${actionableSuggestions.size} actionable ones")
        actionableSuggestions.forEach {
            Log.d("ApplyAll", "Will apply: ${it.action} - ${it.title}")
        }

        // Apply the actions
        val appliedMessage = aiEducationHelper.applyMultipleActions(actionableSuggestions, cameraManager)

        // CRITICAL: Trigger UI state refresh after applying
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            kotlinx.coroutines.delay(800) // Give camera time to update
            onCameraStateChanged() // This will refresh all UI state
        }

        onApplyAll("Applied: $appliedMessage")

        Log.d("ApplyAll", "Actions applied: $appliedMessage")

    } catch (e: Exception) {
        Log.e("ApplyAll", "Failed to apply suggestions", e)
        onApplyAll("Some actions failed: ${e.message}")
    }
}

/**
 * Camera grid overlay for rule of thirds
 */
@Composable
fun CameraGridOverlay(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.dp.toPx()
        val gridColor = Color.White.copy(alpha = 0.3f)

        // Vertical lines (rule of thirds)
        val verticalLine1 = size.width / 3
        val verticalLine2 = size.width * 2 / 3

        drawLine(
            color = gridColor,
            start = androidx.compose.ui.geometry.Offset(verticalLine1, 0f),
            end = androidx.compose.ui.geometry.Offset(verticalLine1, size.height),
            strokeWidth = strokeWidth
        )

        drawLine(
            color = gridColor,
            start = androidx.compose.ui.geometry.Offset(verticalLine2, 0f),
            end = androidx.compose.ui.geometry.Offset(verticalLine2, size.height),
            strokeWidth = strokeWidth
        )

        // Horizontal lines (rule of thirds)
        val horizontalLine1 = size.height / 3
        val horizontalLine2 = size.height * 2 / 3

        drawLine(
            color = gridColor,
            start = androidx.compose.ui.geometry.Offset(0f, horizontalLine1),
            end = androidx.compose.ui.geometry.Offset(size.width, horizontalLine1),
            strokeWidth = strokeWidth
        )

        drawLine(
            color = gridColor,
            start = androidx.compose.ui.geometry.Offset(0f, horizontalLine2),
            end = androidx.compose.ui.geometry.Offset(size.width, horizontalLine2),
            strokeWidth = strokeWidth
        )
    }
}