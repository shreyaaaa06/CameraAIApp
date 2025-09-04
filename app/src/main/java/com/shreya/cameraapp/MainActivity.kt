package com.shreya.cameraapp

import kotlin.math.*
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.opencv.core.Mat
import org.opencv.core.CvType
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.android.OpenCVLoader
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
// For dp and sp units
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// For clickable
import androidx.compose.foundation.clickable

// For BorderStroke
import androidx.compose.foundation.BorderStroke

// For CircleShape
import androidx.compose.foundation.shape.CircleShape
import android.os.Handler
import android.os.Looper







class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    lateinit var cameraManager: CameraManager
    private lateinit var aiEducationHelper: AIEducationHelper
    private lateinit var geminiAI: GeminiAIService
    private lateinit var realtimeAnalyzer: RealTimeVisionAnalyzer
    private var currentFrameAnalysis: RealTimeVisionAnalyzer.FrameAnalysis? = null
    private var lastAnalysisTime = 0L

    // Fixed: Add missing variables
    private var isEducationActive by mutableStateOf(false)
    private var analysisJob: Job? = null
    private val analysisScope = CoroutineScope(Dispatchers.Main + Job())

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val storageGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: true

        if (cameraGranted && audioGranted) {
            Toast.makeText(this, "Camera and audio permissions granted", Toast.LENGTH_SHORT).show()
            if (::cameraManager.isInitialized) {
                cameraManager.startCamera()
            }
        } else {
            Toast.makeText(this, "Camera and audio permissions required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize OpenCV with comprehensive testing
        initializeOpenCV()

        // Initialize AI services after OpenCV
        aiEducationHelper = AIEducationHelper(this)
        geminiAI = GeminiAIService(this)
        realtimeAnalyzer = RealTimeVisionAnalyzer()

        setContent {
            CameraAppTheme {
                MainScreen()
            }
        }
    }

    // OpenCV initialization with testing
    private fun initializeOpenCV() {
        Log.d("MainActivity", "Initializing OpenCV...")

        if (OpenCVLoader.initDebug()) {
            Log.d("MainActivity", "✅ OpenCV loaded successfully")

            // Run comprehensive tests
            if (testOpenCVFunctionality()) {
                Toast.makeText(this, "✅ OpenCV working perfectly!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "❌ OpenCV tests failed", Toast.LENGTH_LONG).show()
            }
        } else {
            Log.e("MainActivity", "❌ OpenCV initialization failed")
            Toast.makeText(this, "❌ OpenCV failed to load", Toast.LENGTH_LONG).show()
        }
    }

    // Comprehensive OpenCV functionality test
    private fun testOpenCVFunctionality(): Boolean {
        Log.d("OpenCVTest", "=== Starting Comprehensive OpenCV Test ===")

        try {
            // Test 1: Basic Mat operations
            Log.d("OpenCVTest", "Test 1: Basic Mat Creation")
            val testMat = Mat(100, 100, CvType.CV_8UC3)
            Log.d("OpenCVTest", "✅ Mat created: ${testMat.rows()}x${testMat.cols()}, channels: ${testMat.channels()}")

            // Test 2: Scalar operations
            Log.d("OpenCVTest", "Test 2: Scalar Operations")
            val blueColor = Scalar(255.0, 0.0, 0.0) // BGR format
            testMat.setTo(blueColor)
            Log.d("OpenCVTest", "✅ Mat filled with blue color")

            // Test 3: Image processing operations
            Log.d("OpenCVTest", "Test 3: Image Processing")
            val grayMat = Mat()
            Imgproc.cvtColor(testMat, grayMat, Imgproc.COLOR_BGR2GRAY)
            Log.d("OpenCVTest", "✅ Color conversion successful: ${grayMat.channels()} channel")

            // Test 4: Blur operation
            Log.d("OpenCVTest", "Test 4: Blur Operation")
            val blurredMat = Mat()
            Imgproc.GaussianBlur(testMat, blurredMat, Size(15.0, 15.0), 0.0)
            Log.d("OpenCVTest", "✅ Gaussian blur applied successfully")

            // Test 5: Face detection cascade (check if file exists)
            Log.d("OpenCVTest", "Test 5: Cascade Classifier Test")
            try {
                val cascadeFile = org.opencv.objdetect.CascadeClassifier()
                Log.d("OpenCVTest", "✅ Cascade classifier created (will need XML file for full functionality)")
            } catch (e: Exception) {
                Log.w("OpenCVTest", "⚠️ Cascade classifier needs XML file: ${e.message}")
            }

            // Test 6: Core operations
            Log.d("OpenCVTest", "Test 6: Core Operations")
            val meanValue = Core.mean(testMat)
            Log.d("OpenCVTest", "✅ Mean calculation: ${meanValue.`val`[0]}, ${meanValue.`val`[1]}, ${meanValue.`val`[2]}")

            // Test 7: Memory cleanup
            testMat.release()
            grayMat.release()
            blurredMat.release()
            Log.d("OpenCVTest", "✅ Memory cleanup completed")

            Log.d("OpenCVTest", "=== ALL OPENCV TESTS PASSED SUCCESSFULLY ===")
            return true

        } catch (e: Exception) {
            Log.e("OpenCVTest", "❌ OpenCV test failed", e)
            return false
        }
    }

    // Test OpenCV with real bitmap
    private fun testOpenCVWithBitmap(bitmap: android.graphics.Bitmap): Boolean {
        Log.d("OpenCVBitmapTest", "=== Testing OpenCV with Real Bitmap ===")

        try {
            // Convert Android Bitmap to OpenCV Mat
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            Log.d("OpenCVBitmapTest", "✅ Bitmap to Mat conversion: ${mat.rows()}x${mat.cols()}")

            // Test brightness analysis
            val grayMat = Mat()
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)
            val meanBrightness = Core.mean(grayMat).`val`[0]
            Log.d("OpenCVBitmapTest", "✅ Average brightness: $meanBrightness")

            // Test blur detection (Laplacian variance)
            val laplacian = Mat()
            Imgproc.Laplacian(grayMat, laplacian, CvType.CV_64F)
            val meanStdDev = org.opencv.core.MatOfDouble()
            Core.meanStdDev(laplacian, org.opencv.core.MatOfDouble(), meanStdDev)
            val blurScore = meanStdDev.get(0, 0)[0] * meanStdDev.get(0, 0)[0]
            Log.d("OpenCVBitmapTest", "✅ Blur detection score: $blurScore (higher = sharper)")

            // Test edge detection
            val edges = Mat()
            Imgproc.Canny(grayMat, edges, 50.0, 150.0)
            val edgeCount = Core.countNonZero(edges)
            Log.d("OpenCVBitmapTest", "✅ Edge pixels detected: $edgeCount")

            // Cleanup
            mat.release()
            grayMat.release()
            laplacian.release()
            edges.release()

            Log.d("OpenCVBitmapTest", "=== BITMAP PROCESSING TESTS PASSED ===")
            return true

        } catch (e: Exception) {
            Log.e("OpenCVBitmapTest", "❌ Bitmap processing test failed", e)
            return false
        }
    }

    @Composable
    fun MainScreen() {
        var isEducationMode by remember { mutableStateOf(false) }
        var aiSuggestions by remember { mutableStateOf<List<AIEducationHelper.CameraSuggestion>>(emptyList()) }
        var isLoadingSuggestions by remember { mutableStateOf(false) }
        var showGrid by remember { mutableStateOf(false) }
        var activeTimerSeconds by remember { mutableStateOf(0) }
        // ADD THESE MISSING STATE VARIABLES:
        var currentZoom by remember { mutableStateOf(1.0f) }
        var currentFlashMode by remember { mutableStateOf(false) }
        var currentGridMode by remember { mutableStateOf(false) }
        var currentCameraMode by remember { mutableStateOf(CameraManager.CameraMode.PHOTO) }
        // Add these state variables in MainScreen() function (around line 65)
        var refreshTrigger by remember { mutableStateOf(0) }
        LaunchedEffect(refreshTrigger) {
            if (::cameraManager.isInitialized) {
                currentZoom = cameraManager.getCurrentZoom()
                currentFlashMode = cameraManager.getFlashMode() != ImageCapture.FLASH_MODE_OFF
                currentGridMode = cameraManager.isGridEnabled()
                currentCameraMode = cameraManager.getCurrentMode()
                currentCameraMode = cameraManager.getCurrentMode()
                showGrid = currentGridMode
            }
        }
        val onCameraStateChanged = {
            refreshTrigger += 1 // Trigger state refresh
        }


        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        Scaffold(
            bottomBar = {
                CameraBottomBar(
                    isEducationMode = isEducationMode,
                    isLoadingSuggestions = isLoadingSuggestions,
                    onCaptureClick = { handleCaptureClick(context, scope, activeTimerSeconds) },
                    onEducationModeToggle = {
                        handleEducationModeToggle(
                            isEducationMode,
                            scope,
                            context,
                            { isEducationMode = !isEducationMode },
                            { isLoadingSuggestions = it },
                            { aiSuggestions = it }
                        )
                    },
                    onGalleryClick = {
                        val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                        context.startActivity(intent)
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Camera Preview
                CameraPreview()

                // OpenCV Test Button (top-right corner)


                // Zoom Controls
                if (::cameraManager.isInitialized) {
                    ZoomControls(
                        cameraManager = cameraManager,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Camera controls overlay
                if (::cameraManager.isInitialized) {
                    CameraControlsOverlay(
                        cameraManager = cameraManager,
                        refreshTrigger = refreshTrigger,
                        onGridToggle = { gridState -> showGrid = gridState
                            onCameraStateChanged() },
                        onTimerPhotoCapture = { timerSeconds ->
                            activeTimerSeconds = timerSeconds
                            val message = if (timerSeconds > 0) {
                                "Timer set to ${timerSeconds}s - Press capture to start countdown"
                            } else {
                                "Timer turned off"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxSize().padding(bottom = 180.dp)
                    )
                }
                // Camera Mode Carousel - NEW HORIZONTAL LAYOUT
                if (::cameraManager.isInitialized) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 20.dp, start = 24.dp, end = 24.dp)
                            .background(
                                Color.Black.copy(alpha = 0.4f),
                                RoundedCornerShape(24.dp)
                            )
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Photo Mode
                        Text(
                            text = "PHOTO",
                            color = if (currentCameraMode == CameraManager.CameraMode.PHOTO)
                                Color(0xFFFF9500) else Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            fontWeight = if (currentCameraMode == CameraManager.CameraMode.PHOTO)
                                FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable {
                                currentCameraMode = CameraManager.CameraMode.PHOTO
                                cameraManager.switchCameraMode(currentCameraMode)
                                onCameraStateChanged()
                            }
                        )

                        // Video Mode
                        Text(
                            text = "VIDEO",
                            color = if (currentCameraMode == CameraManager.CameraMode.VIDEO)
                                Color(0xFFFF9500) else Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            fontWeight = if (currentCameraMode == CameraManager.CameraMode.VIDEO)
                                FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable {
                                currentCameraMode = CameraManager.CameraMode.VIDEO
                                cameraManager.switchCameraMode(currentCameraMode)
                                onCameraStateChanged()
                            }
                        )

                        // Portrait Mode
                        Text(
                            text = "PORTRAIT",
                            color = if (currentCameraMode == CameraManager.CameraMode.PORTRAIT)
                                Color(0xFFFF9500) else Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            fontWeight = if (currentCameraMode == CameraManager.CameraMode.PORTRAIT)
                                FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable {
                                currentCameraMode = CameraManager.CameraMode.PORTRAIT
                                cameraManager.switchCameraMode(currentCameraMode)
                                Toast.makeText(context, "Portrait mode activated", Toast.LENGTH_SHORT).show()
                                onCameraStateChanged()
                            }
                        )

                        // Night Mode
                        Text(
                            text = "NIGHT",
                            color = if (currentCameraMode == CameraManager.CameraMode.NIGHT)
                                Color(0xFFFF9500) else Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            fontWeight = if (currentCameraMode == CameraManager.CameraMode.NIGHT)
                                FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable {
                                currentCameraMode = CameraManager.CameraMode.NIGHT
                                cameraManager.switchCameraMode(currentCameraMode)
                                onCameraStateChanged()
                            }
                        )
                    }
                }

                // Education Mode Overlay - FIXED: Removed non-existent parameters
                CameraOverlayUI(
                    suggestions = aiSuggestions,
                    isEducationMode = isEducationMode,
                    showGrid = showGrid,
                    cameraManager = if (::cameraManager.isInitialized) cameraManager else null,
                    aiEducationHelper = aiEducationHelper,
                    showApplyAllButton = aiSuggestions.isNotEmpty() && isEducationMode,
                    onApplyAll = { appliedActions ->
                        Toast.makeText(context, "Applied: $appliedActions", Toast.LENGTH_LONG).show()

                        // Force UI refresh after applying actions
                        Handler(Looper.getMainLooper()).postDelayed({
                            onCameraStateChanged()
                        }, 500)  // Wait for camera operations to complete
                    },
                    onCameraStateChanged = onCameraStateChanged,
                    modifier = Modifier.fillMaxSize()
                )

                // Loading indicator
                if (isLoadingSuggestions) {
                    CompactLoadingOverlay()
                }
            }
        }
    }

    // Fixed OpenCV test button with proper modifier parameter


    @Composable
    private fun CameraPreview() {
        var currentRatio by remember { mutableStateOf("9:16") }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black), // ADD THIS LINE
            contentAlignment = Alignment.Center
        )  {
            AndroidView(
                factory = { ctx ->
                    previewView = PreviewView(ctx).apply {
                        // Set scale type to crop center to ensure proper aspect ratio display
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }

                    cameraManager = CameraManager(this@MainActivity, previewView) { ratio ->
                        currentRatio = ratio // Callback to update UI
                    }

                    // Check permissions and start camera (your existing code)
                    val permissions = mutableListOf<String>().apply {
                        add(Manifest.permission.CAMERA)
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }

                    val allGranted = permissions.all { permission ->
                        ContextCompat.checkSelfPermission(this@MainActivity, permission) == PackageManager.PERMISSION_GRANTED
                    }

                    if (allGranted) {
                        cameraManager.startCamera()
                    } else {
                        requestPermissionLauncher.launch(permissions.toTypedArray())
                    }

                    previewView
                },
                update = { view ->
                    if (::cameraManager.isInitialized) {
                        val newRatio = cameraManager.getCurrentAspectRatio()
                        if (newRatio != currentRatio) {
                            currentRatio = newRatio
                            Log.d("CameraPreview", "UI ratio updated to: $currentRatio")
                        }
                    }
                },
                modifier = when (currentRatio) {
                    "1:1" -> Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f) // This creates a square container
                        .background(Color.Black)
                    "3:4" -> Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f/4f)
                        .background(Color.Black)
                    "9:16" -> Modifier.fillMaxSize()
                    else -> Modifier.fillMaxSize()
                }
            )

            // Add visual indicator for cropped areas (optional - for debugging)
            if (currentRatio == "1:1") {
                // This helps visualize the square area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(Color.Transparent)
                )
            }
        }
    }

    private fun handleCaptureClick(
        context: android.content.Context,
        scope: kotlinx.coroutines.CoroutineScope,
        activeTimerSeconds: Int
    ) {
        if (::cameraManager.isInitialized && cameraManager.isCameraReady()) {
            if (cameraManager.getCurrentMode() == CameraManager.CameraMode.VIDEO) {
                cameraManager.startStopVideoRecording { _, message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            } else {
                // Photo capture - check timer setting
                if (activeTimerSeconds > 0) {
                    Toast.makeText(context, "Timer: ${activeTimerSeconds}s countdown starting...", Toast.LENGTH_SHORT).show()
                    scope.launch {
                        for (i in activeTimerSeconds downTo 1) {
                            Toast.makeText(context, "⏰ $i", Toast.LENGTH_SHORT).show()
                            delay(1000)
                        }
                        Toast.makeText(context, "📸 Taking photo now!", Toast.LENGTH_SHORT).show()
                        cameraManager.capturePhoto { success ->
                            val message = if (success) {
                                "✅ Timer photo (${activeTimerSeconds}s) saved to gallery!"
                            } else {
                                "❌ Timer photo failed"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    cameraManager.capturePhoto { success ->
                        val message = if (success) "📸 Photo saved!" else "❌ Photo failed"
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(context, "Camera not ready", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleEducationModeToggle(
        isEducationMode: Boolean,
        scope: kotlinx.coroutines.CoroutineScope,
        context: android.content.Context,
        toggleEducationMode: () -> Unit,
        setLoadingSuggestions: (Boolean) -> Unit,
        setSuggestions: (List<AIEducationHelper.CameraSuggestion>) -> Unit
    ) {
        toggleEducationMode()
        val newEducationMode = !isEducationMode

        // Update isEducationActive properly
        isEducationActive = newEducationMode

        if (newEducationMode) {
            setLoadingSuggestions(true)
            startContinuousAnalysis()
            scope.launch {
                try {
                    Log.d("MainActivity", " Starting ACTIONABLE AI analysis...")
                    val detailedCameraInfo = if (::cameraManager.isInitialized && cameraManager.isCameraReady()) {
                        cameraManager.getDetailedCameraInfo()
                    } else {
                        DetailedCameraInfo()
                    }

                    val previewBitmap = try {
                        cameraManager.capturePreviewBitmap()
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Preview capture failed, using camera info only")
                        null
                    }

                    val actionableSuggestions = aiEducationHelper.getActionableSuggestions(
                        detailedCameraInfo, previewBitmap
                    )

                    val suggestions = actionableSuggestions.map { actionable ->
                        AIEducationHelper.CameraSuggestion(
                            title = actionable.title,
                            description = actionable.description,
                            type = when (actionable.action) {
                                AIEducationHelper.QuickAction.FLASH_ON, AIEducationHelper.QuickAction.FLASH_OFF -> AIEducationHelper.SuggestionType.LIGHTING
                                AIEducationHelper.QuickAction.ENABLE_NIGHT, AIEducationHelper.QuickAction.DISABLE_NIGHT -> AIEducationHelper.SuggestionType.SETTINGS
                                AIEducationHelper.QuickAction.ZOOM_IN, AIEducationHelper.QuickAction.ZOOM_OUT -> AIEducationHelper.SuggestionType.TECHNIQUE
                                AIEducationHelper.QuickAction.SWITCH_CAMERA -> AIEducationHelper.SuggestionType.SETTINGS
                                AIEducationHelper.QuickAction.ENABLE_GRID -> AIEducationHelper.SuggestionType.COMPOSITION
                                else -> AIEducationHelper.SuggestionType.TECHNIQUE
                            },
                            icon = actionable.icon,
                            priority = actionable.priority
                        )
                    }

                    setSuggestions(suggestions)

                    val message = if (suggestions.isNotEmpty()) {
                        " AI found ${suggestions.size} ways to improve your shot - tap suggestions to apply!"
                    } else {
                        "📸 Camera conditions look good - ready to shoot!"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Actionable AI analysis failed", e)
                    setSuggestions(aiEducationHelper.getBeginnerTips())
                    Toast.makeText(context, "Using basic photography tips", Toast.LENGTH_SHORT).show()
                } finally {
                    setLoadingSuggestions(false)
                }
            }

            // Periodic re-analysis
            scope.launch {
                while (isEducationActive) {
                    delay(3000) // Check every 2 seconds

                    if (::cameraManager.isInitialized && cameraManager.isCameraReady()) {
                        try {
                            val currentCameraInfo = cameraManager.getDetailedCameraInfo()
                            val previewBitmap = try {
                                cameraManager.capturePreviewBitmap()
                            } catch (e: Exception) { null }

                            val frameAnalysis = if (previewBitmap != null) {
                                aiEducationHelper.visionAnalyzer.analyzeFrame(previewBitmap)
                            } else null

                            val newActionableSuggestions = geminiAI.getSmartActionableSuggestions(currentCameraInfo, frameAnalysis)

                            if (newActionableSuggestions.isNotEmpty()) {
                                val convertedSuggestions = newActionableSuggestions.map { actionable ->
                                    AIEducationHelper.CameraSuggestion(
                                        title = actionable.title,
                                        description = actionable.description,
                                        type = AIEducationHelper.SuggestionType.TECHNIQUE,
                                        icon = actionable.icon,
                                        priority = actionable.priority
                                    )
                                }
                                setSuggestions(convertedSuggestions)
                                Log.d("MainActivity", "Updated suggestions: ${convertedSuggestions.size}")
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Periodic AI analysis failed", e)
                        }
                    }
                }
            }

        } else {
            // Cancel analysis job when turning off education mode
            analysisJob?.cancel()
            setSuggestions(emptyList())
        }
    }

    // Fixed startContinuousAnalysis function
    private fun startContinuousAnalysis() {
        analysisJob?.cancel() // Cancel any existing job
        analysisJob = analysisScope.launch {
            while (isEducationActive) {
                delay(2000) // Analyze every 5 seconds

                if (::cameraManager.isInitialized && cameraManager.isCameraReady()) {
                    try {
                        val previewBitmap = cameraManager.capturePreviewBitmap()
                        if (previewBitmap != null) {
                            // Make analyzeFrame call properly in coroutine
                            currentFrameAnalysis = realtimeAnalyzer.analyzeFrame(previewBitmap)
                            lastAnalysisTime = System.currentTimeMillis()

                            // Trigger UI update with new analysis
                            Log.d("MainActivity", "Frame analyzed: brightness=${currentFrameAnalysis?.brightness?.toInt()}")
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Continuous analysis failed", e)
                    }
                }
            }
        }
    }

    @Composable
    fun CompactLoadingOverlay() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentSize(Alignment.Center)
        ) {
            Card(
                modifier = Modifier.padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "AI analyzing camera...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    @Composable
    fun CameraBottomBar(
        isEducationMode: Boolean,
        isLoadingSuggestions: Boolean,
        onCaptureClick: () -> Unit,
        onEducationModeToggle: () -> Unit,
        onGalleryClick: () -> Unit
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.Black, // Semi-transparent black
            shadowElevation = 0.dp // Remove shadow for cleaner look
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail placeholder (left)
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable { onGalleryClick() },
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0x44FFFFFF)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("🖼", fontSize = 20.sp)
                    }
                }

                // Capture Button (center) - Make it prominent
                Box(contentAlignment = Alignment.Center) {
                    // Outer ring
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        border = BorderStroke(3.dp, Color(0xCCFFFFFF))
                    ) {}

                    // Inner button
                    FloatingActionButton(
                        onClick = onCaptureClick,
                        containerColor = Color.White,
                        modifier = Modifier.size(56.dp)
                    ) {
                        // Empty - the white circle is the button
                    }
                }

                // AI Help Button (right)
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable { onEducationModeToggle() },
                    shape = CircleShape,
                    color = if (isEducationMode) Color(0xFF6C5CE7) else Color(0x44FFFFFF)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isLoadingSuggestions) "⏳" else "AI",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Proper cleanup
        analysisJob?.cancel()
        analysisScope.cancel()
        if (::cameraManager.isInitialized) {
            cameraManager.cleanup()
        }
        if (::aiEducationHelper.isInitialized) {
            aiEducationHelper.cleanup()
        }
    }
}

@Composable
fun CameraAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(),
        content = content
    )
}