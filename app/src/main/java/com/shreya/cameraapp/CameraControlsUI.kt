package com.shreya.cameraapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.widget.Toast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.Icons
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException

import androidx.compose.ui.graphics.vector.ImageVector




import androidx.compose.foundation.layout.PaddingValues






@Composable
fun CameraControlsOverlay(
    cameraManager: CameraManager,
    refreshTrigger: Int = 0,
    onGridToggle: (Boolean) -> Unit = {},
    onTimerPhotoCapture: (Int) -> Unit = {}, // Pass timer duration
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentMode by remember { mutableStateOf(cameraManager.getCurrentMode()) }
    LaunchedEffect(Unit) { currentMode = cameraManager.getCurrentMode() }

    var flashEnabled by remember(refreshTrigger) {
        mutableStateOf(cameraManager.getFlashMode() != ImageCapture.FLASH_MODE_OFF)
    }
    LaunchedEffect(refreshTrigger) {
        flashEnabled = cameraManager.getFlashMode() != ImageCapture.FLASH_MODE_OFF
    }

    var gridEnabled by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var selectedTimer by remember { mutableStateOf(0) }
    var stabilizationEnabled by remember { mutableStateOf(false) }

    Box(modifier = modifier) {

        // 🔹 Top controls bar - Essential only
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left side - Flash
            ControlButton(
                icon = if (flashEnabled) "⚡" else "⚡",
                isActive = flashEnabled,
                tooltip = "Flash",
                onClick = {
                    val newFlashState = cameraManager.toggleFlash()
                    flashEnabled = newFlashState
                }
            )

            // Right side - Settings + Camera flip
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ControlButton(
                    icon = "⚙",
                    isActive = showSettings,
                    tooltip = "Settings",
                    onClick = { showSettings = !showSettings }
                )

                ControlButton(
                    icon = Icons.Filled.FlipCameraAndroid,
                    isActive = false,
                    tooltip = "Switch Camera",
                    onClick = { cameraManager.flipCamera() }
                )
            }
        }

        // 🔹 Enhanced Settings Panel
        if (showSettings) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .width(280.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.9f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Camera Settings",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Grid toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⊞", fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Grid Lines", color = Color.White, fontSize = 14.sp)
                        }
                        Switch(
                            checked = gridEnabled,
                            onCheckedChange = {
                                gridEnabled = cameraManager.toggleGrid()
                                onGridToggle(gridEnabled)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Brightness control
                    Text("☀️ Brightness", color = Color.White, fontSize = 14.sp)
                    var brightness by remember { mutableStateOf(0f) }
                    Slider(
                        value = brightness,
                        onValueChange = {
                            brightness = it
                            cameraManager.setBrightness(it)
                        },
                        valueRange = -4f..7f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Timer selection
                    Text("⏲ Timer", color = Color.White, fontSize = 14.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf(0, 3, 5, 10).forEach { seconds ->
                            TextButton(
                                onClick = {
                                    selectedTimer = seconds
                                    onTimerPhotoCapture(seconds)
                                }
                            ) {
                                Text(
                                    if (seconds == 0) "Off" else "${seconds}s",
                                    color = if (selectedTimer == seconds) Color.Yellow else Color.White,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // AI stabilizer toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📱", fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("AI Stabilizer", color = Color.White, fontSize = 14.sp)
                        }
                        Switch(
                            checked = stabilizationEnabled,
                            onCheckedChange = {
                                stabilizationEnabled = cameraManager.toggleStabilization()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Filters
                    Text("🎨 Filters", color = Color.White, fontSize = 14.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("None", "Warm", "Cool", "Vivid").forEach { filter ->
                            TextButton(
                                onClick = {
                                    cameraManager.applyFilter(filter)
                                    Toast.makeText(context, "Filter: $filter", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text(filter, color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Aspect ratio
                    Text("📐 Aspect Ratio", color = Color.White, fontSize = 14.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("9:16", "3:4", "1:1").forEach { ratio ->
                            TextButton(
                                onClick = {
                                    cameraManager.setAspectRatio(ratio)
                                    Toast.makeText(context, "Ratio: $ratio", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text(ratio, color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }


                    Spacer(modifier = Modifier.height(16.dp))

                    // Close button
                    Button(
                        onClick = { showSettings = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp), // ensures proper height
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White, // purple
                            contentColor = Color.Black          // text color
                        )
                    ) {
                        Text(
                            "Close Settings",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                }
            }
        }

        // 🔹 Keep your existing camera mode selector on the left side

    }
}




@Composable
fun CameraModeButton(
    icon: String,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = if (isActive) Color.Blue.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.3f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = icon, fontSize = 16.sp)
            }
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = 10.sp
        )
    }
}

@Composable
fun BrightnessSlider(cameraManager: CameraManager) {
    var brightness by remember { mutableStateOf(0.0f) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.9f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("Brightness", color = Color.White, fontSize = 12.sp)
            Slider(
                value = brightness,
                onValueChange = {
                    brightness = it
                    cameraManager.setBrightness(it)
                },
                valueRange = -4f..7f,
                modifier = Modifier.width(120.dp)
            )
        }
    }
}


@Composable
fun FilterMenu(cameraManager: CameraManager, onDismiss: () -> Unit) {
    val filters = listOf("None", "Warm", "Cool", "Vivid", "B&W", "Sepia")
    var selectedFilter by remember { mutableStateOf("None") }
    val context = LocalContext.current

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.9f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                "Filters (Preview Only)",
                color = Color.Yellow,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            filters.forEach { filter ->
                TextButton(
                    onClick = {
                        selectedFilter = filter
                        val success = cameraManager.applyFilter(filter)

                        // Provide clear feedback about filter limitations
                        val message = when {
                            success && filter == "None" -> "Filter removed"
                            success -> "Filter '$filter' applied to capture only (not live preview)"
                            else -> "Filter failed - check camera state"
                        }

                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()

                        // Auto-dismiss after selection
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            kotlinx.coroutines.delay(1500)
                            onDismiss()
                        }
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Filter preview icon
                        Text(
                            text = when(filter) {
                                "Warm" -> "🌅"
                                "Cool" -> "❄️"
                                "Vivid" -> "🌈"
                                "B&W" -> "⚫"
                                "Sepia" -> "🍂"
                                else -> "🚫"
                            },
                            fontSize = 16.sp
                        )

                        Text(
                            filter,
                            color = if (selectedFilter == filter) Color.Yellow else Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Close button
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
            ) {
                Text("Close", fontSize = 12.sp)
            }
        }
    }
}
@Composable
fun ControlButton(
    icon: Any,
    isActive: Boolean,
    onClick: () -> Unit,
    tooltip: String
) {
    Surface(
        modifier = Modifier
            .size(40.dp)
            .clickable { onClick() },
        shape = CircleShape,
        color = if (isActive) Color(0x80FF9500) else Color(0x44FFFFFF)
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (icon) {
                is String -> {
                    Text(
                        text = icon,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                }
                is ImageVector -> {
                    Icon(
                        imageVector = icon,
                        contentDescription = tooltip,
                        tint = Color.White // makes it simple black & white
                    )
                }
            }
        }
    }
}
@Composable
fun AspectRatioMenu(cameraManager: CameraManager, onDismiss: () -> Unit) {
    val ratios = listOf(
        "9:16" to "📱 Vertical (Default)",
        "3:4" to "📷 Classic Photo",
        "1:1" to "⬜ Square (Instagram)"
    )
    var selectedRatio by remember { mutableStateOf("9:16") }  // Default to 9:16
    val context = LocalContext.current

    // Get current ratio from camera manager
    LaunchedEffect(Unit) {
        selectedRatio = cameraManager.getCurrentAspectRatio()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.9f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                "Aspect Ratios",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            ratios.forEach { (ratio, description) ->
                TextButton(
                    onClick = {
                        selectedRatio = ratio
                        val success = cameraManager.setAspectRatio(ratio)

                        val message = if (success) {
                            "Camera set to $ratio ratio"
                        } else {
                            "Failed to change ratio"
                        }

                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        Log.d("AspectRatio", "User selected: $ratio, Success: $success")

                        CoroutineScope(Dispatchers.Main).launch {
                            delay(800)
                            onDismiss()
                        }
                    }
                ) {
                    Text(
                        description,
                        color = if (selectedRatio == ratio) Color.Yellow else Color.White,
                        fontSize = 11.sp
                    )
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close", fontSize = 12.sp)
            }
        }
    }
}