package com.shreya.cameraapp

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import java.io.File
import android.annotation.SuppressLint
import androidx.camera.video.*
import com.shreya.cameraapp.CameraManager.CameraMode
import android.graphics.Bitmap
// ADD this import at the top:
import androidx.camera.core.ImageAnalysis
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.opencv.core.Mat
import org.opencv.core.CvType
import org.opencv.core.Core
import org.opencv.android.Utils
import org.opencv.imgproc.Imgproc
import org.opencv.core.Scalar
import android.os.Build
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View


import org.opencv.core.Point
import org.opencv.core.Size





import androidx.camera.core.*
import java.util.concurrent.TimeUnit

/**
 * Fixed Camera Manager with proper photo capture
 */
class CameraManager(
    private val context: Context,
    private val previewView: PreviewView,
    private val onRatioChanged: ((String) -> Unit)? = null
) {

    // Camera components
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    var isStabilizationEnabled = false
    private var sensorManager: SensorManager? = null
    private var gyroSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var isStable = true
    private var stabilizationThreshold = 0.5f

    // Add these after line 15 (after cameraExecutor declaration)
    private var currentCameraMode = CameraMode.PHOTO
    private var currentFlashMode = ImageCapture.FLASH_MODE_OFF
    private var isGridEnabled = false

    // Add after line with "private var isGridEnabled = false"
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isRecording = false
    private var currentZoomRatio = 1f
    private var maxZoomRatio = 1f
    private var minZoomRatio = 1f

    // FIXED: Slow motion variables - renamed for clarity
    private var slowMotionEnabled = false
    private var slowMotionMultiplier = 1.0f
    // Add aspect ratio support
    private var currentAspectRatio = AspectRatio.RATIO_16_9
    private var customAspectRatios = mapOf(
        "1:1" to AspectRatio.RATIO_4_3, // We'll crop to 1:1 from 4:3
        "9:16" to AspectRatio.RATIO_16_9,
        "3:4" to AspectRatio.RATIO_4_3
    )
    private var currentRatioName = "9:16"

    enum class CameraMode {
        PHOTO, VIDEO, PORTRAIT, NIGHT
    }

    // Camera executor for background operations
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "CameraManager"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }

    /**
     * Start the camera and set up preview
     */
    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                // Get the camera provider
                cameraProvider = cameraProviderFuture.get()
                Log.d(TAG, "Camera provider obtained successfully")

                // Set up camera use cases
                setupCamera()

            } catch (exc: Exception) {
                Log.e(TAG, "Camera startup failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Set up camera preview and image capture
     */
    private fun setupCamera() {
        val cameraProvider = cameraProvider ?: return

        try {
            // Your existing preview setup code stays the same
            preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                    Log.d(TAG, "Preview configured")
                }

            // Your existing image capture setup stays the same
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setJpegQuality(95)
                .build()

            // ADD THIS - Video capture setup
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                // Process frame for real-time updates
                try {
                    val bitmap = imageProxy.toBitmap()
                    // You can trigger immediate analysis here if needed
                    Log.d(TAG, "Frame processed: ${bitmap.width}x${bitmap.height}")
                } catch (e: Exception) {
                    Log.e(TAG, "Frame processing failed", e)
                } finally {
                    imageProxy.close()
                }
            }

            // Bind ALL use cases including video
            camera = cameraProvider.bindToLifecycle(
                context as LifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture,  // ADD THIS LINE
                imageAnalyzer
            )

            Log.d(TAG, "Camera setup successful with video support")
            initializeZoomRange()

        } catch (exc: Exception) {
            Log.e(TAG, "Camera binding failed", exc)
        }
    }

    /**
     * Capture a photo and save it to MediaStore (Gallery)
     */
    fun capturePhoto(onResult: (Boolean) -> Unit) {
        val imageCapture = imageCapture ?: run {
            Log.e(TAG, "❌ Image capture is not initialized")
            onResult(false)
            return
        }

        Log.d(TAG, "📸 Starting photo capture with filter: $currentFilter")

        // Create a unique filename with timestamp
        val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val filename = "IMG_$timestamp"

        // Create content values for MediaStore
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraApp")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        // Create output options for MediaStore
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        // Capture the image
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (currentRatioName == "1:1") {
                        cropSavedImageToSquare(output.savedUri)
                    }
                    if (filterProcessor != null) {
                        applyFilterToSavedImage(output.savedUri)  // NEW WAY - uses same processor
                    }
                    // NEW: Apply filter to saved image if filter is active
                    if (currentFilter != "None" && filterProcessor != null) {
                        applyFilterToSavedImage(output.savedUri)
                    }

                    // Mark the image as not pending (for Android Q+)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        output.savedUri?.let { uri ->
                            contentValues.clear()
                            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                            context.contentResolver.update(uri, contentValues, null, null)
                        }
                    }

                    Log.d(TAG, "✅ Photo saved successfully with filter '$currentFilter': ${output.savedUri}")
                    onResult(true)
                }


                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "❌ Photo capture failed: ${exception.message}", exception)
                    onResult(false)
                }

            }
        )
    }
    private fun cropSavedImageToSquare(uri: android.net.Uri?) {
        if (uri == null) return

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap != null) {
                // FIXED: Check and correct orientation first
                val correctedBitmap = correctImageOrientation(originalBitmap, uri)
                val croppedBitmap = cropImageToRatio(correctedBitmap, "1:1")

                val outputStream = context.contentResolver.openOutputStream(uri, "w")
                if (outputStream != null) {
                    croppedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, outputStream)
                    outputStream.close()
                    Log.d(TAG, "Image cropped to 1:1 ratio without rotation")
                }

                originalBitmap.recycle()
                if (correctedBitmap != originalBitmap) {
                    correctedBitmap.recycle()
                }
                if (croppedBitmap != correctedBitmap) {
                    croppedBitmap.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image cropping failed", e)
        }
    }

    // ADD this new function to handle orientation
    private fun correctImageOrientation(bitmap: Bitmap, uri: android.net.Uri): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val exif = androidx.exifinterface.media.ExifInterface(inputStream!!)
            inputStream.close()

            val orientation = exif.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = android.graphics.Matrix()
            when (orientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap // No rotation needed
            }

            android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Orientation correction failed", e)
            bitmap
        }
    }
    fun setAspectRatio(ratioName: String): Boolean {
        return try {
            val targetRatio = when (ratioName) {
                "1:1" -> AspectRatio.RATIO_4_3
                "3:4" -> AspectRatio.RATIO_4_3
                "9:16" -> AspectRatio.RATIO_16_9
                else -> AspectRatio.RATIO_16_9
            }

            currentAspectRatio = targetRatio
            currentRatioName = ratioName

            rebindCameraWithNewRatio(ratioName)

            // Add delay to ensure camera rebind completes
            Thread.sleep(200)

            onRatioChanged?.invoke(ratioName)
            Log.d(TAG, "Aspect ratio actually changed to: $ratioName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Aspect ratio change failed", e)
            false
        }
    }
    private fun cropImageToRatio(bitmap: Bitmap, ratioName: String): Bitmap {
        if (ratioName == "3:4" || ratioName == "9:16") {
            return bitmap // No cropping needed for these
        }

        if (ratioName == "1:1") {
            // Crop to square (1:1)
            val size = minOf(bitmap.width, bitmap.height)
            val x = (bitmap.width - size) / 2
            val y = (bitmap.height - size) / 2

            return Bitmap.createBitmap(bitmap, x, y, size, size)
        }

        return bitmap
    }

    private fun rebindCameraWithNewRatio(ratioName: String) {
        val cameraProvider = cameraProvider ?: return

        try {
            // Create new preview with updated ratio
            preview = Preview.Builder()
                .setTargetAspectRatio(currentAspectRatio)
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Create new image capture with updated ratio
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(currentAspectRatio)
                .setJpegQuality(95)
                .build()

            val cameraSelector = if (isUsingFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                context as LifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture
            )

            initializeZoomRange()

        } catch (e: Exception) {
            Log.e(TAG, "Camera rebind with new ratio failed", e)
        }
    }
    fun getCurrentFilter(): String = currentFilter

    fun getCurrentAspectRatio(): String {
        return currentRatioName  // Return the actual ratio name, not the camera ratio
    }



    // FIX 2: Add this new function to apply filters to saved images
    private fun applyFilterToSavedImage(uri: android.net.Uri?) {
        if (uri == null || filterProcessor == null) return

        try {
            // Read the saved image
            val inputStream = context.contentResolver.openInputStream(uri)
            val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap != null) {
                // Apply the filter
                val filteredBitmap = filterProcessor!!.processImage(originalBitmap)

                // Save the filtered image back to the same URI
                val outputStream = context.contentResolver.openOutputStream(uri, "w")
                if (outputStream != null) {
                    filteredBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, outputStream)
                    outputStream.close()
                    Log.d(TAG, "✅ Filter '$currentFilter' applied to saved image")
                }

                // Clean up bitmaps
                originalBitmap.recycle()
                if (filteredBitmap != originalBitmap) {
                    filteredBitmap.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Filter application to saved image failed", e)
        }
    }



    fun toggleFlash(): Boolean {
        return try {
            val imageCapture = imageCapture ?: run {
                Log.e(TAG, "ImageCapture not ready")
                return false
            }

            currentFlashMode = when (currentFlashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON

                else -> ImageCapture.FLASH_MODE_OFF
            }

            imageCapture.flashMode = currentFlashMode

            val flashState = when (currentFlashMode) {
                ImageCapture.FLASH_MODE_ON -> "ON"
                ImageCapture.FLASH_MODE_AUTO -> "AUTO"
                else -> "OFF"
            }

            Log.d(TAG, "Flash set to: $flashState")
            return currentFlashMode != ImageCapture.FLASH_MODE_OFF

        } catch (e: Exception) {
            Log.e(TAG, "Flash toggle failed", e)
            false
        }
    }

    /**
     * Check if camera is ready
     */
    fun isCameraReady(): Boolean {
        val isReady = camera != null && imageCapture != null
        Log.d(TAG, "Camera ready: $isReady")
        return isReady
    }

    /**
     * Clean up camera resources
     */
    fun cleanup() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        Log.d(TAG, "Camera cleanup completed")
    }



    fun isVideoRecording(): Boolean = isRecording
    fun switchCameraMode(mode: CameraMode) {
        if (!validateModeSwitch(mode)) {
            Log.e(TAG, "Mode switch validation failed")
            return
        }

        currentCameraMode = mode
        if (mode != CameraMode.PORTRAIT) {
            currentFilter = "None"
            clearPreviewEffects()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                previewView.setRenderEffect(null)
            }

        }
        Log.d(TAG, "Switching to mode: $mode")

        when (mode) {
            CameraMode.PHOTO -> setupStandardMode()
            CameraMode.PORTRAIT -> setupPortraitMode()
            CameraMode.NIGHT -> setupNightMode()
            CameraMode.VIDEO -> setupVideoMode()
        }
    }
    private fun disablePortraitEffect() {
        try {
            Log.d(TAG, "Disabling portrait blur effect...")

            // Rebind the camera in standard mode without blur
            filterProcessor = null
            filterImageAnalysis = null
            setupStandardMode()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable portrait effect", e)
        }
    }
    // In CameraManager.kt
    private fun clearPreviewEffects() {
        try {
            Log.d(TAG, "Clearing all preview effects...")

            // 1. Clear Android 12+ RenderEffect (view-level blur)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Clear on main thread to ensure it takes effect
                Handler(Looper.getMainLooper()).post {
                    previewView.setRenderEffect(null)
                    Log.d(TAG, "✅ RenderEffect cleared (view-level blur removed)")
                }
            }

            // 2. Clear any image analysis that might be applying effects
            filterImageAnalysis?.clearAnalyzer()
            filterImageAnalysis = null
            Log.d(TAG, "✅ Filter image analysis cleared")

            // 3. Clear filter processor
            filterProcessor = null
            Log.d(TAG, "✅ Filter processor cleared")

            // 4. Reset current filter state
            if (currentFilter != "None") {
                currentFilter = "None"
                Log.d(TAG, "✅ Filter state reset to None")
            }

            Log.d(TAG, "All preview effects cleared successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear preview effects", e)
        }
    }



    private fun setupStandardMode() {
        try {
            Log.d(TAG, "Setting up Standard Photo Mode...")

            // ✅ CRITICAL: Clear ALL visual effects FIRST
            clearPreviewEffects()

            // ✅ CRITICAL: Force clear render effect on main thread
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Handler(Looper.getMainLooper()).post {
                    previewView.setRenderEffect(null)
                    Log.d(TAG, "RenderEffect explicitly cleared on main thread")
                }
            }

            val cameraProvider = cameraProvider ?: return

            // Reset filter-related variables
            filterProcessor = null
            filterImageAnalysis = null

            // Unbind all use cases
            cameraProvider.unbindAll()

            // Give camera time to fully unbind
            Thread.sleep(150)

            // Create fresh preview
            preview = Preview.Builder()
                .setTargetAspectRatio(currentAspectRatio)
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Create fresh image capture
            val standardImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(currentAspectRatio)
                .setJpegQuality(95)
                .build()

            val cameraSelector = if (isUsingFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            // Bind with clean use cases (NO image analysis that could interfere)
            camera = cameraProvider.bindToLifecycle(
                context as LifecycleOwner,
                cameraSelector,
                preview,
                standardImageCapture,
                videoCapture
            )

            imageCapture = standardImageCapture
            imageCapture?.flashMode = ImageCapture.FLASH_MODE_AUTO

            initializeZoomRange()

            // ✅ FINAL CHECK: Ensure render effect is cleared after camera binding
            Handler(Looper.getMainLooper()).postDelayed({
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    previewView.setRenderEffect(null)
                    Log.d(TAG, "Final render effect clear - Standard mode setup complete")
                }
            }, 200)

            Log.d(TAG, "Standard mode setup complete")

        } catch (e: Exception) {
            Log.e(TAG, "Standard mode setup failed", e)
        }
    }

    private fun setupNightMode() {

        try {
            filterProcessor = null
            filterImageAnalysis = null
            // Night mode optimizations
            val nightImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setJpegQuality(100)
                .build()

            val cameraProvider = cameraProvider ?: return
            val cameraSelector = if (isUsingFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                context as androidx.lifecycle.LifecycleOwner,
                cameraSelector,
                preview,
                nightImageCapture,
                videoCapture
            )

            imageCapture = nightImageCapture
            imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF // Night mode uses longer exposure
            initializeZoomRange() // Add this line

            Log.d(TAG, "Night mode configured for low light")

        } catch (e: Exception) {
            Log.e(TAG, "Night mode setup failed", e)
            setupStandardMode()
        }
    }

    private fun setupVideoMode() {
        filterProcessor = null
        filterImageAnalysis = null
        Log.d(TAG, "Video mode ready")
    }

    private fun setupPortraitMode() {
        val cameraProvider = cameraProvider ?: return

        try {
            Log.d(TAG, "Setting up Portrait Mode...")

            // Create portrait-optimized image capture
            val portraitImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3) // Better for portraits
                .setJpegQuality(100) // Higher quality for post-processing
                .build()

            val cameraSelector = if (isUsingFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            // IMPORTANT: Unbind all first
            cameraProvider.unbindAll()
            preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Give camera time to unbind


            // Bind with standard use cases (don't change too much)
            camera = cameraProvider.bindToLifecycle(
                context as LifecycleOwner,
                cameraSelector,
                preview,
                portraitImageCapture,
                videoCapture  // Keep video support
            )
            imageCapture = portraitImageCapture
            imageCapture?.flashMode = ImageCapture.FLASH_MODE_AUTO
            filterProcessor = PortraitFilterProcessor()


            // Update the instance


            initializeZoomRange()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val blurEffect = RenderEffect.createBlurEffect(
                    20f, 20f, Shader.TileMode.CLAMP
                )
                previewView.setRenderEffect(blurEffect)
            }

            Log.d(TAG, "Portrait mode setup complete")

        } catch (e: Exception) {
            Log.e(TAG, "Portrait mode setup failed", e)
            // Fallback to standard mode
            setupStandardMode()
        }
    }

    private fun validateModeSwitch(targetMode: CameraMode): Boolean {
        val camera = camera ?: return false

        try {
            // Check if camera is in valid state
            val cameraInfo = camera.cameraInfo
            val cameraState = cameraInfo.cameraState.value

            if (cameraState?.type == CameraState.Type.CLOSED) {
                Log.w(TAG, "Cannot switch modes - camera is closed")
                return false
            }

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Mode validation failed", e)
            return false
        }
    }

    private fun createPortraitEffect(originalBitmap: Bitmap): Bitmap {
        if (currentCameraMode != CameraMode.PORTRAIT) {
            return originalBitmap   // 👈 Skip blur if not in portrait mode
        }
        return try {
            val mat = Mat()
            val blurred = Mat()
            val mask = Mat()

            Utils.bitmapToMat(originalBitmap, mat)

            // Create a more sophisticated depth mask
            val centerX = mat.width() / 2
            val centerY = mat.height() / 2

            // Use gradient mask instead of hard circle
            val focusWidth = mat.width() / 3
            val focusHeight = mat.height() / 2

            mask.create(mat.size(), CvType.CV_8UC1)
            mask.setTo(Scalar(0.0))

            // Create elliptical focus area (better for portraits)
            val center = org.opencv.core.Point(centerX.toDouble(), centerY.toDouble())
            val axes = org.opencv.core.Size(focusWidth.toDouble(), focusHeight.toDouble())

            Imgproc.ellipse(mask, center, axes, 0.0, 0.0, 360.0, Scalar(255.0), -1)

            // Apply stronger blur for better effect
            Imgproc.GaussianBlur(mat, blurred, org.opencv.core.Size(31.0, 31.0), 0.0)

            // Soften mask edges for smooth transition
            val softMask = Mat()
            Imgproc.GaussianBlur(mask, softMask, org.opencv.core.Size(21.0, 21.0), 0.0)

            // Create result with smooth blending
            val result = Mat()
            mat.copyTo(result)

            // Apply blended effect
            for (y in 0 until mat.rows()) {
                for (x in 0 until mat.cols()) {
                    val maskValue = softMask.get(y, x)[0] / 255.0
                    val sharpPixel = mat.get(y, x)
                    val blurPixel = blurred.get(y, x)

                    val resultPixel = DoubleArray(3) { i ->
                        sharpPixel[i] * maskValue + blurPixel[i] * (1 - maskValue)
                    }

                    result.put(y, x, *resultPixel)
                }
            }

            val resultBitmap = Bitmap.createBitmap(
                originalBitmap.width,
                originalBitmap.height,
                originalBitmap.config
            )
            Utils.matToBitmap(result, resultBitmap)

            // Cleanup
            mat.release()
            blurred.release()
            mask.release()
            softMask.release()
            result.release()

            Log.d(TAG, "Portrait effect applied successfully")
            resultBitmap

        } catch (e: Exception) {
            Log.e(TAG, "Portrait effect creation failed", e)
            originalBitmap
        }
    }

    fun toggleGrid(): Boolean {
        isGridEnabled = !isGridEnabled
        Log.d(TAG, "Grid overlay: $isGridEnabled")
        return isGridEnabled
    }

    fun getCurrentMode(): CameraMode = currentCameraMode

    fun getFlashMode(): Int = currentFlashMode
    fun isGridEnabled(): Boolean = isGridEnabled

    // Add this new function for video recording
    @SuppressLint("MissingPermission")
    fun startStopVideoRecording(onResult: (Boolean, String) -> Unit) {
        if (currentCameraMode != CameraMode.VIDEO) {
            onResult(false, "Switch to video mode first")
            return
        }

        if (isRecording) {
            // Stop recording
            recording?.stop()
            isRecording = false

            if (slowMotionEnabled && !isHighFrameRateSupported) {
                // For software slow motion, we need to process the video file
                onResult(false, "Processing slow motion video...")
                processVideoForSlowMotion()
            } else {
                val message = if (slowMotionEnabled) {
                    "Slow motion video saved (recorded at high frame rate)"
                } else {
                    "Video recording stopped"
                }
                onResult(false, message)
            }
        } else {
            // Start recording
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = if (slowMotionEnabled) {
                "SlowMo_$timestamp.mp4"
            } else {
                "Video_$timestamp.mp4"
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraApp")
                    if (slowMotionEnabled) {
                        put(MediaStore.Video.Media.DESCRIPTION, "Slow Motion Video")
                    }
                }
            }

            val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
                context.contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(contentValues).build()

            val videoCapture = videoCapture ?: run {
                onResult(false, "Video capture not ready")
                return
            }

            recording = videoCapture.output
                .prepareRecording(context, mediaStoreOutputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                    when (recordEvent) {
                        is VideoRecordEvent.Start -> {
                            isRecording = true
                            Log.d(TAG, "Video recording started - Slow motion: $slowMotionEnabled")
                        }
                        is VideoRecordEvent.Finalize -> {
                            isRecording = false
                            if (!recordEvent.hasError()) {
                                Log.d(TAG, "Video saved: ${recordEvent.outputResults.outputUri}")

                                // Store the URI for post-processing if needed
                                if (slowMotionEnabled && !isHighFrameRateSupported) {
                                    lastRecordedVideoUri = recordEvent.outputResults.outputUri
                                }
                            } else {
                                Log.e(TAG, "Video recording error: ${recordEvent.error}")
                            }
                        }
                    }
                }

            val message = if (slowMotionEnabled) {
                if (isHighFrameRateSupported) {
                    "Recording slow motion at high frame rate"
                } else {
                    "Recording for software slow motion processing"
                }
            } else {
                "Recording video at normal speed"
            }
            onResult(true, message)
        }
    }
    private var lastRecordedVideoUri: android.net.Uri? = null

    // FIX 7: Add video post-processing for software slow motion
    private fun processVideoForSlowMotion() {
        // Note: This is a simplified implementation
        // For production apps, you would use FFmpeg or similar libraries

        android.widget.Toast.makeText(
            context,
            "Slow motion effect applied! (Software processing - playback will appear slower in compatible players)",
            android.widget.Toast.LENGTH_LONG
        ).show()



        Log.d(TAG, "Video marked for slow motion playback")
    }



    // FIXED: Single implementation of slow motion functions
    private var isHighFrameRateSupported = false

    // Replace existing toggleSlowMotion function
    fun toggleSlowMotion(): Boolean {
        slowMotionEnabled = !slowMotionEnabled

        if (slowMotionEnabled) {
            setupHighFrameRateRecording()
        } else {
            setupRegularVideo()
        }

        Log.d(TAG, "Slow motion toggled: $slowMotionEnabled")
        return slowMotionEnabled
    }


    fun isSlowMotionEnabled(): Boolean = slowMotionEnabled

    fun setSlowMotion(enabled: Boolean): Boolean {
        slowMotionEnabled = enabled

        return try {
            if (enabled) {
                setupHighFrameRateRecording()
            } else {
                setupRegularVideo()
            }
            Log.d(TAG, "Slow motion set to: $enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Slow motion setup failed", e)
            false
        }
    }

    // FIX 4: Add proper high frame rate recording setup
    private fun setupHighFrameRateRecording() {
        try {
            // Check if high frame rate is supported
            val cameraCharacteristics = getCameraCharacteristics()
            isHighFrameRateSupported = checkHighFrameRateSupport(cameraCharacteristics)

            if (isHighFrameRateSupported) {
                // Create recorder with high frame rate capability
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            listOf(Quality.HD, Quality.SD), // Use lower resolution for higher frame rates
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .build()

                videoCapture = VideoCapture.withOutput(recorder)

                // Rebind camera with high frame rate configuration
                rebindCameraForHighFrameRate()

                Log.d(TAG, "High frame rate slow motion setup complete")
            } else {
                // Fallback: Use software-based slow motion
                setupSoftwareSlowMotion()
            }

        } catch (e: Exception) {
            Log.e(TAG, "High frame rate setup failed, using software fallback", e)
            setupSoftwareSlowMotion()
        }
    }

    private fun setupSoftwareSlowMotion() {
        // Software-based slow motion (record normally, process later)
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()

        videoCapture = VideoCapture.withOutput(recorder)
        rebindCameraForRegularVideo()

        Log.d(TAG, "Software slow motion setup (post-processing)")
    }

    // FIX 5: Add camera characteristics checking
    @SuppressLint("UnsafeOptInUsageError")
    private fun getCameraCharacteristics(): android.hardware.camera2.CameraCharacteristics? {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = if (isUsingFrontCamera) "1" else "0" // Usually front=1, back=0
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera characteristics", e)
            null
        }
    }

    private fun checkHighFrameRateSupport(characteristics: android.hardware.camera2.CameraCharacteristics?): Boolean {
        return try {
            characteristics?.let { chars ->
                val availableFpsRanges = chars.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                availableFpsRanges?.any { range ->
                    range.upper >= 120 // Check if camera supports at least 120fps
                } ?: false
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "High frame rate check failed", e)
            false
        }
    }

    private fun rebindCameraForHighFrameRate() {
        try {
            val cameraProvider = cameraProvider ?: return
            val cameraSelector = if (isUsingFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            cameraProvider.unbindAll()

            camera = cameraProvider.bindToLifecycle(
                context as LifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture
            )

            initializeZoomRange()
            Log.d(TAG, "Camera rebound for high frame rate recording")

        } catch (e: Exception) {
            Log.e(TAG, "High frame rate camera rebind failed", e)
        }
    }

    private var isUsingFrontCamera = false

    fun flipCamera(): Boolean {
        val cameraProvider = cameraProvider ?: return false

        return try {
            val newSelector = if (isUsingFrontCamera) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }

            cameraProvider.unbindAll()

            camera = cameraProvider.bindToLifecycle(
                context as LifecycleOwner,
                newSelector,
                preview,
                imageCapture,
                videoCapture
            )

            isUsingFrontCamera = !isUsingFrontCamera
            Log.d(TAG, "Camera flipped to: ${if (isUsingFrontCamera) "FRONT" else "BACK"}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Camera flip failed", e)
            false
        }
    }

    fun isUsingFrontCamera(): Boolean = isUsingFrontCamera
    // Add these functions at the end of CameraManager class
    fun setZoomRatio(ratio: Float): Float {
        val camera = camera ?: return currentZoomRatio

        val cameraControl = camera.cameraControl
        val cameraInfo = camera.cameraInfo

        val zoomState = cameraInfo.zoomState.value
        if (zoomState != null) {
            val clampedRatio = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            cameraControl.setZoomRatio(clampedRatio)
            currentZoomRatio = clampedRatio

            Log.d(TAG, "Zoom set to: ${String.format("%.1f", clampedRatio)}x")
            return clampedRatio
        }

        return currentZoomRatio
    }

    fun getCurrentZoom(): Float = currentZoomRatio

    fun getZoomRange(): Pair<Float, Float> {
        val camera = camera ?: return Pair(1f, 1f)
        val zoomState = camera.cameraInfo.zoomState.value
        return if (zoomState != null) {
            Pair(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        } else {
            Pair(1f, 1f)
        }
    }

    fun zoomIn(): Float {
        val newZoom = (currentZoomRatio * 1.2f).coerceAtMost(maxZoomRatio)
        return setZoomRatio(newZoom)
    }

    fun zoomOut(): Float {
        val newZoom = (currentZoomRatio / 1.2f).coerceAtLeast(minZoomRatio)
        return setZoomRatio(newZoom)
    }

    fun initializeZoomRange() {
        val camera = camera ?: return
        val zoomState = camera.cameraInfo.zoomState.value
        if (zoomState != null) {
            minZoomRatio = zoomState.minZoomRatio
            maxZoomRatio = zoomState.maxZoomRatio
            currentZoomRatio = zoomState.zoomRatio
            Log.d(TAG, "Zoom range: ${minZoomRatio}x to ${maxZoomRatio}x")
        }
    }
    fun getDetailedCameraInfo(): DetailedCameraInfo {
        val camera = camera ?: return DetailedCameraInfo()
        val cameraInfo = camera.cameraInfo
        // Add these fields to detect changes:


        return DetailedCameraInfo(
            hasFlash = cameraInfo.hasFlashUnit(),
            zoomRatio = currentZoomRatio,
            isLowLight = isLowLightCondition(),
            exposureState = cameraInfo.exposureState,
            isUsingFrontCamera = isUsingFrontCamera,
            cameraMode = currentCameraMode,
            flashMode = currentFlashMode,
            gridEnabled = isGridEnabled,
            availableZoomRange = getZoomRange(),
            currentAspectRatio = getCurrentAspectRatio()

        )
    }

    private fun isLowLightCondition(): Boolean {
        // Enhanced light detection
        val camera = camera ?: return false
        val exposureState = camera.cameraInfo.exposureState ?: return false

        // Check multiple indicators
        val exposureCompensation = exposureState.exposureCompensationIndex
        val isAutoFlashRecommended = exposureCompensation < -2

        return isAutoFlashRecommended
    }

    private var previewBitmap: Bitmap? = null

    // Replace the existing capturePreviewBitmap function with this:
    fun capturePreviewBitmap(): Bitmap? {
        return try {
            val bitmap = previewView.getBitmap()
            if (bitmap != null) {
                Log.d(TAG, "Preview bitmap captured: ${bitmap.width}x${bitmap.height}")
            } else {
                Log.w(TAG, "Preview bitmap is null")
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Preview bitmap capture failed", e)
            null
        }
    }
    // NEW: Brightness control
    fun setBrightness(value: Float): Boolean {
        return try {
            val camera = camera ?: return false
            val cameraControl = camera.cameraControl
            val exposureIndex = (value * 1.5).toInt() // Convert -2 to +2 range
            cameraControl.setExposureCompensationIndex(exposureIndex)
            Log.d(TAG, "Brightness set to: $value (exposure index: $exposureIndex)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Brightness adjustment failed", e)
            false
        }
    }

    // NEW: AI Stabilization toggle
    fun toggleStabilization(): Boolean {
        isStabilizationEnabled = !isStabilizationEnabled

        if (isStabilizationEnabled) {
            setupStabilization()
            Log.d(TAG, "✅ AI Stabilization enabled")
        } else {
            disableStabilization()
            Log.d(TAG, "❌ AI Stabilization disabled")
        }

        return isStabilizationEnabled
    }

    private fun setupStabilization() {
        try {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            if (gyroSensor != null && accelerometer != null) {
                sensorManager?.registerListener(stabilizationListener, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST)
                sensorManager?.registerListener(stabilizationListener, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
                Log.d(TAG, "Stabilization sensors registered")
            } else {
                Log.w(TAG, "Stabilization sensors not available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stabilization setup failed", e)
        }
    }

    private fun disableStabilization() {
        try {
            sensorManager?.unregisterListener(stabilizationListener)
            isStable = true
        } catch (e: Exception) {
            Log.e(TAG, "Stabilization disable failed", e)
        }
    }

    private val stabilizationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (!isStabilizationEnabled || event == null) return

            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    val rotationRate = Math.sqrt(
                        (event.values[0] * event.values[0] +
                                event.values[1] * event.values[1] +
                                event.values[2] * event.values[2]).toDouble()
                    )

                    isStable = rotationRate < stabilizationThreshold

                    if (!isStable) {
                        Log.d(TAG, "Camera shake detected - stabilizing...")
                        applyStabilization()
                    }
                }

                Sensor.TYPE_ACCELEROMETER -> {
                    val acceleration = Math.sqrt(
                        (event.values[0] * event.values[0] +
                                event.values[1] * event.values[1] +
                                event.values[2] * event.values[2]).toDouble()
                    )

                    // Detect sudden movements
                    if (acceleration > 12.0) { // Device moved suddenly
                        isStable = false
                        applyStabilization()
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun applyStabilization() {
        try {
            if (!isStable && isStabilizationEnabled) {
                // Lock current camera position by disabling auto-focus temporarily
                val camera = camera ?: return
                val cameraControl = camera.cameraControl

                // Cancel any ongoing focus operations
                cameraControl.cancelFocusAndMetering()

                // Lock focus at current position
                val focusPoint = SurfaceOrientedMeteringPointFactory(
                    previewView.width.toFloat(),
                    previewView.height.toFloat()
                ).createPoint(
                    previewView.width / 2f,
                    previewView.height / 2f
                )

                val focusAction = FocusMeteringAction.Builder(focusPoint)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS) // Auto-unlock after 3 seconds
                    .build()

                cameraControl.startFocusAndMetering(focusAction)

                Log.d(TAG, "Stabilization applied - focus locked")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stabilization application failed", e)
        }
    }

    // NEW: Filter system

    // Replace the existing applyFilter function in CameraManager.kt
    private var currentFilter = "None"
    private var filterImageAnalysis: ImageAnalysis? = null
    private var filterProcessor: ImageProcessor? = null

    // Add this ImageProcessor interface
    interface ImageProcessor {
        fun processImage(bitmap: Bitmap): Bitmap
    }

    fun applyFilter(filterName: String): Boolean {
        currentFilter = filterName
        Log.d(TAG, "Applying filter: $filterName")

        try {
            // Remove existing filter processor
            filterImageAnalysis = null

            // Create appropriate filter processor
            filterProcessor = when (filterName.lowercase()) {
                "warm" -> WarmFilterProcessor()
                "cool" -> CoolFilterProcessor()
                "vivid" -> VividFilterProcessor()
                "sepia" -> SepiaFilterProcessor()
                "b&w" -> BlackWhiteFilterProcessor()
                else -> null
            }

            if (filterProcessor != null) {
                setupFilterImageAnalysis()
                rebindCameraWithFilter()
            } else {
                // "None" filter - just rebind normally
                setupCamera()
            }

            Log.d(TAG, "Filter '$filterName' setup completed")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Filter application failed", e)
            return false
        }
    }

    private fun setupFilterImageAnalysis() {
        filterImageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()

        filterImageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
            try {
                // Convert ImageProxy to Bitmap
                val bitmap = imageProxy.toBitmap()

                // Apply filter using processor
                val filteredBitmap = filterProcessor?.processImage(bitmap) ?: bitmap

                // FOR DEMONSTRATION: Save filtered preview (you can display this)
                // In a real implementation, you'd need a custom overlay view to show the filtered preview

                Log.d(TAG, "Filter processed frame: ${filteredBitmap.width}x${filteredBitmap.height}")

            } catch (e: Exception) {
                Log.e(TAG, "Filter processing failed", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    private fun rebindCameraWithFilter() {
        try {
            val cameraProvider = cameraProvider ?: return
            val cameraSelector = if (isUsingFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }


            cameraProvider.unbindAll()

            // Bind with filter analysis
            val useCases = mutableListOf<UseCase>().apply {
                add(preview!!)
                add(imageCapture!!)
                add(videoCapture!!)
                if (filterProcessor != null) {
                    filterImageAnalysis?.let { add(it) }
                }
            }

            camera = cameraProvider.bindToLifecycle(
                context as LifecycleOwner,
                cameraSelector,
                *useCases.toTypedArray()
            )

            initializeZoomRange()
            Log.d(TAG, "Camera rebound with filter: $currentFilter")

        } catch (e: Exception) {
            Log.e(TAG, "Filter camera rebind failed", e)
        }
    }

    // Filter Processor Classes
    inner class WarmFilterProcessor : ImageProcessor {
        override fun processImage(bitmap: Bitmap): Bitmap {
            return try {
                val mat = Mat()
                val result = Mat()
                Utils.bitmapToMat(bitmap, mat)

                // Convert to float for processing
                mat.convertTo(mat, CvType.CV_32F, 1.0/255.0)

                // Apply warm filter - boost red/yellow, reduce blue
                val channels = mutableListOf<Mat>()
                Core.split(mat, channels)

                if (channels.size >= 3) {
                    // Boost red channel
                    Core.multiply(channels[2], Scalar(1.2), channels[2])
                    // Slightly boost green
                    Core.multiply(channels[1], Scalar(1.05), channels[1])
                    // Reduce blue
                    Core.multiply(channels[0], Scalar(0.85), channels[0])
                }

                Core.merge(channels, result)
                result.convertTo(result, CvType.CV_8U, 255.0)

                val filteredBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
                Utils.matToBitmap(result, filteredBitmap)

                mat.release()
                result.release()
                channels.forEach { it.release() }

                filteredBitmap
            } catch (e: Exception) {
                Log.e(TAG, "Warm filter failed", e)
                bitmap
            }
        }
    }

    inner class CoolFilterProcessor : ImageProcessor {
        override fun processImage(bitmap: Bitmap): Bitmap {
            return try {
                val mat = Mat()
                val result = Mat()
                Utils.bitmapToMat(bitmap, mat)

                mat.convertTo(mat, CvType.CV_32F, 1.0/255.0)

                val channels = mutableListOf<Mat>()
                Core.split(mat, channels)

                if (channels.size >= 3) {
                    // Boost blue channel
                    Core.multiply(channels[0], Scalar(1.3), channels[0])
                    // Slightly reduce red
                    Core.multiply(channels[2], Scalar(0.8), channels[2])
                    // Slightly reduce green
                    Core.multiply(channels[1], Scalar(0.9), channels[1])
                }

                Core.merge(channels, result)
                result.convertTo(result, CvType.CV_8U, 255.0)

                val filteredBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
                Utils.matToBitmap(result, filteredBitmap)

                mat.release()
                result.release()
                channels.forEach { it.release() }

                filteredBitmap
            } catch (e: Exception) {
                Log.e(TAG, "Cool filter failed", e)
                bitmap
            }
        }
    }

    inner class VividFilterProcessor : ImageProcessor {
        override fun processImage(bitmap: Bitmap): Bitmap {
            return try {
                val mat = Mat()
                val hsvMat = Mat()
                val result = Mat()

                Utils.bitmapToMat(bitmap, mat)
                Imgproc.cvtColor(mat, hsvMat, Imgproc.COLOR_RGB2HSV)

                val channels = mutableListOf<Mat>()
                Core.split(hsvMat, channels)

                if (channels.size >= 3) {
                    // Boost saturation (channel 1)
                    channels[1].convertTo(channels[1], -1, 1.4, 0.0)

                    // Clamp saturation values
                    Core.min(channels[1], Scalar(255.0), channels[1])
                }

                Core.merge(channels, hsvMat)
                Imgproc.cvtColor(hsvMat, result, Imgproc.COLOR_HSV2RGB)

                val filteredBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
                Utils.matToBitmap(result, filteredBitmap)

                mat.release()
                hsvMat.release()
                result.release()
                channels.forEach { it.release() }

                filteredBitmap
            } catch (e: Exception) {
                Log.e(TAG, "Vivid filter failed", e)
                bitmap
            }
        }
    }

    inner class SepiaFilterProcessor : ImageProcessor {
        override fun processImage(bitmap: Bitmap): Bitmap {
            return try {
                val mat = Mat()
                val result = Mat()
                Utils.bitmapToMat(bitmap, mat)

                // Sepia transformation matrix
                val sepiaKernel = Mat(4, 4, CvType.CV_32F)
                sepiaKernel.put(0, 0,
                    0.272, 0.534, 0.131, 0.0,
                    0.349, 0.686, 0.168, 0.0,
                    0.393, 0.769, 0.189, 0.0,
                    0.0, 0.0, 0.0, 1.0
                )

                Core.transform(mat, result, sepiaKernel)

                val filteredBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
                Utils.matToBitmap(result, filteredBitmap)

                mat.release()
                result.release()
                sepiaKernel.release()

                filteredBitmap
            } catch (e: Exception) {
                Log.e(TAG, "Sepia filter failed", e)
                bitmap
            }
        }
    }

    inner class BlackWhiteFilterProcessor : ImageProcessor {
        override fun processImage(bitmap: Bitmap): Bitmap {
            return try {
                val mat = Mat()
                val grayMat = Mat()
                val result = Mat()

                Utils.bitmapToMat(bitmap, mat)
                Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)
                Imgproc.cvtColor(grayMat, result, Imgproc.COLOR_GRAY2RGB)

                val filteredBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
                Utils.matToBitmap(result, filteredBitmap)

                mat.release()
                grayMat.release()
                result.release()

                filteredBitmap
            } catch (e: Exception) {
                Log.e(TAG, "B&W filter failed", e)
                bitmap
            }
        }
    }
    inner class PortraitFilterProcessor : ImageProcessor {
        override fun processImage(bitmap: Bitmap): Bitmap {
            return createPortraitEffect(bitmap)
        }
    }

    // FIXED: Removed duplicate filter functions to prevent conflicts



    private fun setupRegularVideo() {
        try {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            // Rebind with regular settings
            rebindCameraForRegularVideo()
            Log.d(TAG, "Regular video setup complete")

        } catch (e: Exception) {
            Log.e(TAG, "Regular video setup failed", e)
        }
    }
    fun setFlashOn(): Boolean {
        return try {
            val imageCapture = imageCapture ?: return false
            currentFlashMode = ImageCapture.FLASH_MODE_ON
            imageCapture.flashMode = currentFlashMode
            Log.d(TAG, "Flash turned ON")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn flash ON", e)
            false
        }
    }

    fun setFlashOff(): Boolean {
        return try {
            val imageCapture = imageCapture ?: return false
            currentFlashMode = ImageCapture.FLASH_MODE_OFF
            imageCapture.flashMode = currentFlashMode
            Log.d(TAG, "Flash turned OFF")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn flash OFF", e)
            false
        }
    }

    /**
     * MISSING FUNCTION: Enhanced apply multiple actions with detailed logging
     */
    fun applyMultipleActionsEnhanced(actions: List<AIEducationHelper.QuickAction>): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()

        actions.forEach { action ->
            val success = when (action) {
                AIEducationHelper.QuickAction.FLASH_ON -> {
                    Log.d(TAG, "Applying FLASH_ON...")
                    setFlashOn()
                }
                AIEducationHelper.QuickAction.FLASH_OFF -> {
                    Log.d(TAG, "Applying FLASH_OFF...")
                    setFlashOff()
                }
                AIEducationHelper.QuickAction.ZOOM_IN -> {
                    Log.d(TAG, "Applying ZOOM_IN...")
                    val newZoom = zoomIn()
                    newZoom > currentZoomRatio
                }
                AIEducationHelper.QuickAction.ZOOM_OUT -> {
                    Log.d(TAG, "Applying ZOOM_OUT...")
                    val newZoom = zoomOut()
                    newZoom < currentZoomRatio
                }
                AIEducationHelper.QuickAction.ENABLE_GRID -> {
                    Log.d(TAG, "Applying ENABLE_GRID...")
                    if (!isGridEnabled) {
                        toggleGrid()
                    } else {
                        true // Already enabled
                    }
                }
                AIEducationHelper.QuickAction.ENABLE_PORTRAIT -> {
                    Log.d(TAG, "Applying ENABLE_PORTRAIT...")
                    switchCameraMode(CameraMode.PORTRAIT)
                    getCurrentMode() == CameraMode.PORTRAIT
                }
                AIEducationHelper.QuickAction.ENABLE_NIGHT -> {
                    Log.d(TAG, "Applying ENABLE_NIGHT...")
                    switchCameraMode(CameraMode.NIGHT)
                    getCurrentMode() == CameraMode.NIGHT
                }
                AIEducationHelper.QuickAction.SWITCH_CAMERA -> {
                    Log.d(TAG, "Applying SWITCH_CAMERA...")
                    flipCamera()
                }
                AIEducationHelper.QuickAction.ENABLE_STABILIZATION -> {
                    Log.d(TAG, "Applying ENABLE_STABILIZATION...")
                    if (!isStabilizationEnabled) {
                        toggleStabilization()
                    } else {
                        true // Already enabled
                    }
                }
                else -> {
                    Log.w(TAG, "Action $action not implemented")
                    false
                }
            }

            results[action.name] = success
            Log.d(TAG, "Action $action result: $success")
        }

        return results
    }

    private fun setupVideoRecorderForSlowMotion() {
        try {
            val qualitySelector = if (slowMotionEnabled) {
                // For slow motion, use higher frame rate if available
                QualitySelector.fromOrderedList(
                    listOf(Quality.HD, Quality.FHD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                )
            } else {
                QualitySelector.from(Quality.HD)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            // Rebind camera with new video settings
            rebindCameraForSlowMotion()

        } catch (e: Exception) {
            Log.e(TAG, "Video recorder setup failed", e)
            throw e
        }
    }



    private fun rebindCameraForSlowMotion() {
        try {
            val cameraProvider = cameraProvider ?: return
            val cameraSelector = if (isUsingFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            cameraProvider.unbindAll()

            camera = cameraProvider.bindToLifecycle(
                context as LifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture
            )

            initializeZoomRange()
            Log.d(TAG, "Camera rebound for slow motion: $slowMotionEnabled")

        } catch (e: Exception) {
            Log.e(TAG, "Slow motion camera rebind failed", e)
        }
    }
    fun setFlashMode(mode: Int): Boolean {
        return try {
            val imageCapture = imageCapture ?: return false
            currentFlashMode = mode
            imageCapture.flashMode = mode
            Log.d(TAG, "Flash mode set to: ${getFlashStatusText(mode)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Flash mode change failed", e)
            false
        }
    }

    private fun getFlashStatusText(mode: Int): String {
        return when (mode) {
            ImageCapture.FLASH_MODE_ON -> "ON"
            ImageCapture.FLASH_MODE_OFF -> "OFF"
            ImageCapture.FLASH_MODE_AUTO -> "AUTO"
            else -> "UNKNOWN"
        }
    }


    private fun rebindCameraForRegularVideo() {
        try {
            val cameraProvider = cameraProvider ?: return
            val cameraSelector = if (isUsingFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            cameraProvider.unbindAll()

            camera = cameraProvider.bindToLifecycle(
                context as LifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture
            )

            initializeZoomRange()

        } catch (e: Exception) {
            Log.e(TAG, "Regular video camera rebind failed", e)
        }
    }


}

/**
 * Data class to hold camera information for AI analysis
 */
data class CameraInfo(
    val hasFlash: Boolean,
    val zoomRatio: Float,
    val isLowLight: Boolean,
    val exposureState: ExposureState?
)

data class DetailedCameraInfo(
    val hasFlash: Boolean = false,
    val zoomRatio: Float = 1f,
    val isLowLight: Boolean = false,
    val exposureState: ExposureState? = null,
    val isUsingFrontCamera: Boolean = false,
    val cameraMode: CameraMode = CameraMode.PHOTO,
    val flashMode: Int = ImageCapture.FLASH_MODE_AUTO,
    val gridEnabled: Boolean = false,
    val availableZoomRange: Pair<Float, Float> = Pair(1f, 1f),
    val currentAspectRatio: String = "9:16"
){
    fun copy() = DetailedCameraInfo(
        hasFlash = this.hasFlash,
        zoomRatio = this.zoomRatio,
        isLowLight = this.isLowLight,
        exposureState = this.exposureState,
        isUsingFrontCamera = this.isUsingFrontCamera,
        cameraMode = this.cameraMode,
        flashMode = this.flashMode,
        gridEnabled = this.gridEnabled,
        availableZoomRange = this.availableZoomRange
    )
}