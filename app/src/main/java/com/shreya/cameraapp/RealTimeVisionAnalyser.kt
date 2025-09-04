package com.shreya.cameraapp

import android.graphics.Bitmap
import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.android.Utils
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class RealTimeVisionAnalyzer {

    companion object {
        private const val TAG = "VisionAnalyzer"
    }

    data class FrameAnalysis(
        val brightness: Double = 0.0,
        val blurLevel: Double = 0.0,
        val faceCount: Int = 0,
        val isBacklit: Boolean = false,
        val hasMotionBlur: Boolean = false,
        val compositionScore: Double = 0.0,
        val horizonTilt: Double = 0.0,
        val subjectCentered: Boolean = false,
        val isOverexposed: Boolean = false,
        val isUnderexposed: Boolean = false,
        val subjectPositionX: Double = 0.5,
        val subjectPositionY: Double = 0.5,
        val dynamicRange: Double = 0.0,
        val analysisSuccess: Boolean = false,
        val colorBalance: String = "NEUTRAL", // "WARM", "COOL", "OVERSATURATED"
        val backgroundType: String = "CLEAN", // "CLUTTERED", "DISTRACTING"
        val faceEmotions: List<String> = emptyList(), // "SMILING", "EYES_CLOSED"
        val ruleOfThirdsScore: Double = 0.0,
        val contrastLevel: String = "NORMAL", // "LOW", "HIGH"
        val noiseLevel: Double = 0.0,

    )

    // Data class for camera info (you may need to define this elsewhere)
    data class DetailedCameraInfo(
        val isUsingFrontCamera: Boolean = false,
        val flashMode: String = "AUTO",
        val focusMode: String = "AUTO"
    )

    // Analyze camera frame in real-time
    suspend fun analyzeFrame(bitmap: Bitmap?): FrameAnalysis = withContext(Dispatchers.Default) {
        if (bitmap == null) {
            Log.w(TAG, "Bitmap is null, returning default analysis")
            return@withContext FrameAnalysis()
        }

        try {
            Log.d(TAG, "Starting frame analysis for ${bitmap.width}x${bitmap.height} bitmap")

            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            if (mat.empty()) {
                Log.e(TAG, "OpenCV Mat is empty")
                return@withContext FrameAnalysis()
            }

            val brightness = calculateBrightness(mat)
            val blurLevel = calculateBlur(mat)
            val faceCount = detectFaces(bitmap)
            val exposureAnalysis = analyzeExposure(mat)
            val subjectPosition = detectSubjectPosition(mat)
            val sceneContext = detectSceneContext(mat, brightness, faceCount)
            val colorAnalysis = analyzeColorBalance(mat)
            val backgroundAnalysis = analyzeBackground(mat)
            val faceEmotions = if (faceCount > 0) analyzeFaceEmotions(bitmap) else emptyList()
            val ruleOfThirdsScore = calculateRuleOfThirds(mat)
            val contrastLevel = analyzeContrast(mat)
            val noiseLevel = analyzeNoise(mat)

            val analysis = FrameAnalysis(
                brightness = brightness,
                blurLevel = blurLevel,
                faceCount = faceCount,
                isBacklit = detectBacklighting(mat, brightness),
                hasMotionBlur = blurLevel < 150,
                horizonTilt = detectHorizonTilt(mat),
                subjectCentered = calculateSubjectCentering(mat),
                isOverexposed = exposureAnalysis.first,
                isUnderexposed = exposureAnalysis.second,
                subjectPositionX = subjectPosition.first,
                subjectPositionY = subjectPosition.second,
                dynamicRange = exposureAnalysis.third,
                compositionScore = calculateComposition(mat),
                colorBalance = colorAnalysis,
                backgroundType = backgroundAnalysis,
                faceEmotions = faceEmotions,
                ruleOfThirdsScore = ruleOfThirdsScore,
                contrastLevel = contrastLevel,
                noiseLevel = noiseLevel,
                analysisSuccess = true
            )

            Log.d(
                TAG,
                "Analysis complete: brightness=${brightness.toInt()}, blur=$blurLevel, faces=$faceCount"
            )
            return@withContext analysis

        } catch (e: Exception) {
            Log.e(TAG, "Frame analysis failed", e)
            return@withContext FrameAnalysis()
        }
    }

    private fun calculateBrightness(mat: Mat): Double {
        return try {
            val grayMat = Mat()
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)
            val mean = Core.mean(grayMat)
            val brightness = mean.`val`[0]
            Log.d(TAG, "Brightness calculated: $brightness")
            brightness
        } catch (e: Exception) {
            Log.e(TAG, "Brightness calculation failed", e)
            128.0
        }
    }

    private fun calculateBlur(mat: Mat): Double {
        return try {
            val grayMat = Mat()
            val laplacian = Mat()
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)
            Imgproc.Laplacian(grayMat, laplacian, CvType.CV_64F)

            val mu = MatOfDouble()
            val sigma = MatOfDouble()
            Core.meanStdDev(laplacian, mu, sigma)
            val variance = sigma.get(0, 0)[0].pow(2.0) // Fixed: explicit pow operation
            Log.d(TAG, "Blur variance: $variance")
            variance
        } catch (e: Exception) {
            Log.e(TAG, "Blur calculation failed", e)
            200.0
        }
    }

    private suspend fun detectFaces(bitmap: Bitmap): Int =
        suspendCancellableCoroutine { continuation ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .build()

                val detector = FaceDetection.getClient(options)

                detector.process(image)
                    .addOnSuccessListener { faces ->
                        Log.d(TAG, "Face detection success: ${faces.size} faces found")
                        if (continuation.isActive) {
                            continuation.resume(faces.size)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Face detection failed", e)
                        if (continuation.isActive) {
                            continuation.resume(0)
                        }
                    }
                    .addOnCanceledListener {
                        Log.w(TAG, "Face detection cancelled")
                        if (continuation.isActive) {
                            continuation.resume(0)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Face detection setup failed", e)
                if (continuation.isActive) {
                    continuation.resume(0)
                }
            }
        }

    private fun calculateComposition(mat: Mat): Double {
        return try {
            val height = mat.rows()
            val width = mat.cols()

            val thirdX1 = width / 3
            val thirdX2 = width * 2 / 3
            val thirdY1 = height / 3
            val thirdY2 = height * 2 / 3

            val grayMat = Mat()
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

            val edges = Mat()
            Imgproc.Canny(grayMat, edges, 100.0, 200.0)

            val roiSize = 50
            var score = 0.0

            val points = listOf(
                Pair(thirdX1, thirdY1), Pair(thirdX2, thirdY1),
                Pair(thirdX1, thirdY2), Pair(thirdX2, thirdY2)
            )

            points.forEach { (x, y) ->
                if (x > roiSize && y > roiSize &&
                    x < width - roiSize && y < height - roiSize
                ) {

                    val roi = edges.submat(
                        y - roiSize / 2, y + roiSize / 2,
                        x - roiSize / 2, x + roiSize / 2
                    )
                    val density = Core.mean(roi).`val`[0] / 255.0
                    score += density
                }
            }

            Log.d(TAG, "Composition score: $score")
            return score / points.size

        } catch (e: Exception) {
            Log.e(TAG, "Composition calculation failed", e)
            0.3
        }
    }

    private fun detectHorizonTilt(mat: Mat): Double {
        return try {
            val grayMat = Mat()
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

            val edges = Mat()
            Imgproc.Canny(grayMat, edges, 50.0, 150.0)

            val lines = Mat()
            Imgproc.HoughLines(edges, lines, 1.0, Math.PI / 180, 100)

            var avgAngle = 0.0
            var lineCount = 0

            for (i in 0 until lines.rows()) {
                val line = lines.get(i, 0)
                if (line != null && line.isNotEmpty()) {
                    val theta = line[1]
                    val angle = Math.toDegrees(theta - Math.PI / 2)

                    if (abs(angle) < 45) {
                        avgAngle += angle
                        lineCount++
                    }
                }
            }

            return if (lineCount > 0) avgAngle / lineCount else 0.0

        } catch (e: Exception) {
            Log.e(TAG, "Horizon detection failed", e)
            0.0
        }
    }

    private fun detectSceneContext(mat: Mat, brightness: Double, faceCount: Int): String {
        return when {
            brightness > 160 && faceCount == 0 -> "OUTDOOR_LANDSCAPE"
            brightness > 140 && faceCount > 0 -> "OUTDOOR_PORTRAIT"
            brightness < 80 && faceCount > 0 -> "INDOOR_PORTRAIT"
            brightness < 60 -> "LOW_LIGHT_SCENE"
            faceCount > 2 -> "GROUP_PHOTO"
            else -> "GENERAL_PHOTO"
        }
    }

    private fun calculateSubjectCentering(mat: Mat): Boolean {
        return try {
            val height = mat.rows()
            val width = mat.cols()
            val centerX = width / 2
            val centerY = height / 2
            val roi = mat.submat(
                centerY - height / 6, centerY + height / 6,
                centerX - width / 6, centerX + width / 6
            )
            val density = Core.mean(roi).`val`[0]
            density > 100
        } catch (e: Exception) {
            false
        }
    }

    private fun analyzeExposure(mat: Mat): Triple<Boolean, Boolean, Double> {
        return try {
            val grayMat = Mat()
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

            val hist = Mat()
            Imgproc.calcHist(
                listOf(grayMat),
                MatOfInt(0),
                Mat(),
                hist,
                MatOfInt(256),
                MatOfFloat(0f, 256f)
            )

            val totalPixels = (mat.rows() * mat.cols()).toDouble()

            val overexposedPixels = hist.get(255, 0)
            val underexposedPixels = hist.get(0, 0)

            val overexposed = if (overexposedPixels != null && overexposedPixels.isNotEmpty()) {
                overexposedPixels[0] > (totalPixels * 0.01)
            } else false

            val underexposed = if (underexposedPixels != null && underexposedPixels.isNotEmpty()) {
                underexposedPixels[0] > (totalPixels * 0.1)
            } else false

            val dynamicRange = Core.mean(hist).`val`[0]

            Triple(overexposed, underexposed, dynamicRange)
        } catch (e: Exception) {
            Log.e(TAG, "Exposure analysis failed", e)
            Triple(false, false, 0.0)
        }
    }

    private fun detectSubjectPosition(mat: Mat): Pair<Double, Double> {
        return try {
            val grayMat = Mat()
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

            val edges = Mat()
            Imgproc.Canny(grayMat, edges, 50.0, 150.0)

            val moments = Imgproc.moments(edges)

            // Check for division by zero
            val centerX =
                if (moments.m00 != 0.0) moments.m10 / moments.m00 else mat.cols().toDouble() / 2
            val centerY =
                if (moments.m00 != 0.0) moments.m01 / moments.m00 else mat.rows().toDouble() / 2

            val normalizedX = centerX / mat.cols()
            val normalizedY = centerY / mat.rows()

            Pair(normalizedX, normalizedY)
        } catch (e: Exception) {
            Log.e(TAG, "Subject position detection failed", e)
            Pair(0.5, 0.5)
        }
    }

    private fun detectSelfieMode(
        frameAnalysis: FrameAnalysis,
        cameraInfo: DetailedCameraInfo
    ): Boolean {
        return cameraInfo.isUsingFrontCamera && frameAnalysis.faceCount > 0
    }

    private fun generateContextualAdvice(
        frameAnalysis: FrameAnalysis,
        cameraInfo: DetailedCameraInfo
    ): List<String> {
        val advice = mutableListOf<String>()

        when {
            detectSelfieMode(frameAnalysis, cameraInfo) -> {
                advice.add("Hold phone at arm's length for better selfie angle")
                if (frameAnalysis.brightness < 80) advice.add("Try front flash or move to brighter area")
            }

            frameAnalysis.faceCount >= 2 -> {
                advice.add("Switch to Portrait mode for group shots")
                advice.add("Make sure everyone fits in frame")
            }

            frameAnalysis.brightness < 50 -> {
                advice.add("Use Night mode for low light scenes")
                advice.add("Enable flash if subjects are close")
            }
        }

        return advice
    }

    private fun detectBacklighting(mat: Mat, overallBrightness: Double): Boolean {
        return try {
            // Split image into center and edges to detect backlighting
            val height = mat.rows()
            val width = mat.cols()

            // Center region (subject area)
            val centerRoi = mat.submat(height / 4, 3 * height / 4, width / 4, 3 * width / 4)
            val centerBrightness = Core.mean(centerRoi).`val`[0]

            // Edge regions (background)
            val topRoi = mat.submat(0, height / 4, 0, width)
            val edgeBrightness = Core.mean(topRoi).`val`[0]

            // True backlighting: bright background, dark subject
            (edgeBrightness - centerBrightness) > 60
        } catch (e: Exception) {
            Log.e(TAG, "Backlighting detection failed", e)
            false
        }
    }

    private suspend fun analyzeFaceEmotions(bitmap: Bitmap): List<String> {
        return try {
            // Enhanced face emotion analysis using ML Kit
            // For now, return placeholder until enhanced ML Kit implementation
            listOf("NEUTRAL")
        } catch (e: Exception) {
            Log.e(TAG, "Face emotion analysis failed", e)
            emptyList()
        }
    }

    private fun analyzeColorBalance(mat: Mat): String {
        return try {
            val channels = mutableListOf<Mat>()
            Core.split(mat, channels)

            if (channels.size >= 3) {
                val blueMean = Core.mean(channels[0]).`val`[0]
                val greenMean = Core.mean(channels[1]).`val`[0]
                val redMean = Core.mean(channels[2]).`val`[0]


                when {
                    redMean > greenMean + 20 && redMean > blueMean + 20 -> "WARM"
                    blueMean > redMean + 20 && blueMean > greenMean + 10 -> "COOL"
                    abs(redMean - greenMean) < 10 && abs(greenMean - blueMean) < 10 -> "BALANCED"
                    else -> "NEUTRAL"
                }
            } else "NEUTRAL"
        } catch (e: Exception) {
            Log.e(TAG, "Color balance analysis failed", e)
            "NEUTRAL"
        }
    }

    private fun analyzeBackground(mat: Mat): String {
        return try {
            val edges = Mat()
            val grayMat = Mat()
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)
            Imgproc.Canny(grayMat, edges, 50.0, 150.0)

            val edgeCount = Core.countNonZero(edges)
            val totalPixels = mat.rows() * mat.cols()
            val edgeRatio = edgeCount.toDouble() / totalPixels

            when {
                edgeRatio > 0.15 -> "CLUTTERED"
                edgeRatio > 0.08 -> "BUSY"
                else -> "CLEAN"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background analysis failed", e)
            "CLEAN"
        }
    }

    private fun calculateRuleOfThirds(mat: Mat): Double {
        return try {
            val height = mat.rows()
            val width = mat.cols()

            val thirdX = width / 3
            val thirdY = height / 3

            val grayMat = Mat()
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

            val edges = Mat()
            Imgproc.Canny(grayMat, edges, 100.0, 200.0)

            // Check intersection points
            var score = 0.0
            val checkRadius = 30

            val intersections = listOf(
                Pair(thirdX, thirdY), Pair(2 * thirdX, thirdY),
                Pair(thirdX, 2 * thirdY), Pair(2 * thirdX, 2 * thirdY)
            )

            intersections.forEach { (x, y) ->
                if (x > checkRadius && y > checkRadius &&
                    x < width - checkRadius && y < height - checkRadius
                ) {

                    val roi = edges.submat(
                        y - checkRadius, y + checkRadius,
                        x - checkRadius, x + checkRadius
                    )
                    val density = Core.mean(roi).`val`[0] / 255.0
                    score += density
                }
            }

            score / intersections.size
        } catch (e: Exception) {
            Log.e(TAG, "Rule of thirds calculation failed", e)
            0.3
        }
    }

    private fun analyzeContrast(mat: Mat): String {
        return try {
            val grayMat = Mat()
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

            val mean = MatOfDouble()
            val stdDev = MatOfDouble()
            Core.meanStdDev(grayMat, mean, stdDev)

            val contrast = stdDev.get(0, 0)?.get(0) ?: 0.0

            when {
                contrast > 60 -> "HIGH"
                contrast > 30 -> "NORMAL"
                else -> "LOW"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contrast analysis failed", e)
            "NORMAL"
        }
    }

    private fun analyzeNoise(mat: Mat): Double {
        return try {
            val grayMat = Mat()
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

            val blurred = Mat()
            Imgproc.GaussianBlur(grayMat, blurred, Size(3.0, 3.0), 0.0)

            val diff = Mat()
            Core.absdiff(grayMat, blurred, diff)

            Core.mean(diff).`val`[0]
        } catch (e: Exception) {
            Log.e(TAG, "Noise analysis failed", e)
            10.0
        }
    }
}