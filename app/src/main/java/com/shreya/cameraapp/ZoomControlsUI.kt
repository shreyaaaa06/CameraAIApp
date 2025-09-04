package com.shreya.cameraapp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay


@Composable
fun ZoomControls(
    cameraManager: CameraManager,
    modifier: Modifier = Modifier
) {
    var currentZoom by remember { mutableStateOf(cameraManager.getCurrentZoom()) }
    // Sync zoom value periodically
    LaunchedEffect(Unit) {
        while (true) {
            currentZoom = cameraManager.getCurrentZoom()
            delay(500) // Check every 500ms
        }
    }
    var showZoomValue by remember { mutableStateOf(false) }

    // Pinch to zoom gesture
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val newZoom = (currentZoom * zoom).coerceIn(0.5f, 10f)
                    currentZoom = cameraManager.setZoomRatio(newZoom)
                    showZoomValue = true
                }
            }
    ) {
        // Zoom value display
        if (showZoomValue || currentZoom != 1f) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = "${String.format("%.1f", currentZoom)}x",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp)
                )
            }

            // Hide zoom display after 2 seconds
            LaunchedEffect(currentZoom) {
                kotlinx.coroutines.delay(2000)
                showZoomValue = false
            }
        }

        // Zoom buttons (bottom right)


        // Zoom presets (bottom center)
        // Compact Zoom presets (above capture button)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 90.dp)
                .background(
                    Color.Black.copy(alpha = 0.4f),
                    RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(0.5f, 1f, 2f, 5f).forEach { zoomLevel ->
                Button(
                    onClick = {
                        currentZoom = cameraManager.setZoomRatio(zoomLevel)
                        showZoomValue = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (kotlin.math.abs(currentZoom - zoomLevel) < 0.1f)
                            Color.White.copy(alpha = 0.9f)
                        else
                            Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier
                        .height(28.dp)
                        .width(40.dp),
                    contentPadding = PaddingValues(2.dp)
                ) {
                    Text(
                        text = "${zoomLevel}x",
                        fontSize = 10.sp,
                        color = Color.Black,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

