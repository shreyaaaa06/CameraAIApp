package com.shreya.cameraapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import android.util.Base64
import androidx.camera.core.ImageCapture
import android.os.Looper

class GeminiAIService(private val context: Context) {
    private var lastSuggestions = mutableListOf<String>()
    private var lastCameraState: DetailedCameraInfo? = null
    private var lastSuggestionTime = 0L
    private var dailyCallCount = 0
    private var lastResetDate = ""
    private val MAX_DAILY_CALLS = 40 // Leave buffer of 10
    private var lastFrameAnalysis: RealTimeVisionAnalyzer.FrameAnalysis? = null
    private val suggestionHistory = mutableListOf<String>()
    private var usedSuggestionTypes = mutableSetOf<String>()
    private var sceneChangeCounter = 0


    companion object {
        private const val TAG = "GeminiAIService"
        private const val GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
        private const val API_KEY = "YOUR_API_KEY"  //i have replaced

        private const val TIMEOUT_SECONDS = 30L
    }


    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS) // increased
        .readTimeout(60, TimeUnit.SECONDS)    // was 30, now 60
        .writeTimeout(60, TimeUnit.SECONDS)   // was 30, now 60
        .build()

    /**
     * NEW: Get smart, actionable suggestions with one-tap actions
     */
    private val suggestionCache = mutableMapOf<String, List<AIEducationHelper.ActionableSuggestion>>()

    suspend fun getSmartActionableSuggestions(
        cameraInfo: DetailedCameraInfo,
        frameAnalysis: RealTimeVisionAnalyzer.FrameAnalysis? = null
    ): List<AIEducationHelper.ActionableSuggestion> = withContext(Dispatchers.IO) {

        // ✅ REMOVE CACHE CHECK – always request new suggestions
        // suggestionCache[cacheKey]?.let { cachedSuggestions ->
        //     Log.d(TAG, "Using cached suggestions for similar scene")
        //     return@withContext cachedSuggestions
        // }

        // ✅ Throttle: allow new Gemini call only every 3 seconds
        val now = System.currentTimeMillis()
        if (now - lastSuggestionTime < 3000) {
            Log.d(TAG, "⏳ Throttled: waiting before next AI call")
            return@withContext emptyList()
        }
        lastSuggestionTime = now

        if (!canMakeAPICall()) {
            Log.w(TAG, "Daily API quota limit reached, skipping AI call")
            return@withContext emptyList()
        }

        dailyCallCount++

        try {
            Log.d(TAG, "🎯 Getting actionable AI suggestions...")

            val prompt = createActionablePrompt(cameraInfo, frameAnalysis)
            val aiResponse = if (frameAnalysis != null) {
                val previewBitmap = withContext(Dispatchers.Main) {
                    try {
                        (context as? MainActivity)?.cameraManager?.capturePreviewBitmap()
                    } catch (e: Exception) {
                        Log.e(TAG, "Preview bitmap capture failed", e)
                        null
                    }
                }
                if (previewBitmap != null) {
                    callGeminiWithImage(prompt, previewBitmap)
                } else {
                    Log.w(TAG, "Preview bitmap is null, using text-only analysis")
                    callGeminiTextOnly(prompt)
                }
            } else {
                callGeminiTextOnly(prompt)
            }

            Log.d(TAG, "✅ AI Response received")
            val suggestions = parseActionableResponse(aiResponse, cameraInfo)
            Log.d(TAG, "📋 Got ${suggestions.size} actionable suggestions")

            return@withContext suggestions

        } catch (e: Exception) {
            Log.e(TAG, "❌ AI failed, using smart fallbacks", e)
            return@withContext getSmartFallbackActions(cameraInfo)
        }
    }
    private suspend fun callGeminiWithImage(prompt: String, bitmap: Bitmap): String {
        // RESIZE IMAGE FIRST - This is the biggest performance gain
        val resizedBitmap = resizeBitmap(bitmap, 640, 480) // Much smaller!

        val baos = ByteArrayOutputStream()
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos) // Lower quality
        val imageBytes = baos.toByteArray()
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        Log.d(TAG, "Image size: ${imageBytes.size / 1024}KB") // Monitor size

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.4)
                put("maxOutputTokens", 500) // Shorter responses = faster
                put("topP", 0.8)
            })
        }

        val request = Request.Builder()
            .url("$GEMINI_API_URL?key=$API_KEY")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            Log.e(TAG, "API Error: $responseBody")
            throw Exception("API call failed: ${response.code}")
        }

        return parseGeminiResponse(responseBody)
    }

    // 3. ADD IMAGE RESIZE FUNCTION - Add this new method:
    private fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Calculate scale to maintain aspect ratio
        val scale = minOf(
            maxWidth.toFloat() / width,
            maxHeight.toFloat() / height,
            1.0f // Never upscale
        )

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Create focused prompt for actionable advice
     */
    // REPLACE the entire prompt with structured photography analysis
    private fun createActionablePrompt(
        cameraInfo: DetailedCameraInfo,
        frameAnalysis: RealTimeVisionAnalyzer.FrameAnalysis? = null
    ): String {
        return """
You're a friendly photography buddy helping someone take better photos. Look at this image and give casual, encouraging advice like you're standing right next to them.

CURRENT CAMERA SETTINGS:
- Camera: ${if (cameraInfo.isUsingFrontCamera) "Front (selfie)" else "Main camera"}
- Zoom: ${String.format("%.1f", cameraInfo.zoomRatio)}x
- Flash: ${getFlashStatusText(cameraInfo.flashMode)}
- Mode: ${cameraInfo.cameraMode}
- Grid: ${if (cameraInfo.gridEnabled) "ON" else "OFF"}
AVAILABLE ONE-TAP FEATURES:
- Flash (ON/OFF/AUTO)
- Zoom (IN/OUT to specific levels)
- Night Mode (ENABLE/DISABLE)
- Portrait Mode (ENABLE/DISABLE)
- Grid Lines (ENABLE/DISABLE)
- Camera Switch (FRONT/BACK)

MANDATORY: You MUST include at least 2 phone-controlled suggestions from the available features above, plus 1-2 user movement suggestions.

TECHNICAL ANALYSIS:
- Brightness: ${frameAnalysis?.brightness?.toInt() ?: "Unknown"}/255
- Faces: ${frameAnalysis?.faceCount ?: 0}
- Sharpness: ${if (frameAnalysis?.hasMotionBlur == true) "Blurry" else "Sharp"}

Act like you're standing next to someone teaching them photography. Use natural language like:
- "Try tilting your phone slightly to the left"
- "The lighting looks harsh - step into some shade"
- "Great! Now zoom out a bit for better framing"
- "Hold the phone steadier - I can see some shake"

Focus on what you actually SEE in the image, not just the technical data.

Return ONLY this JSON format:
{
  "suggestions": [
    {
      "title": "Natural instruction (like 'Move closer to your subject')",
      "description": "Friendly explanation why (like 'This will make them the focus of the shot')",
      "action": "SPECIFIC_ACTION",
      "action_value": "target_value",
      "priority": 1,
      "icon": "📱"
    },
    
    {
      "title": "Enable Grid Lines",
      "description": "Grid helps align your subject better",
      "action": "GRID_ON",
      "action_value": "ON",
      "priority": 2,
      "icon": "⊞"
    },
    { "title": "Turn Flash On",
     "description": "Image is too dark for clear details",
      "action": "FLASH_ON",
       "action_value": "ON",
        "priority": 1,
         "icon": "⚡" 
         },
    {
      "title": "Move 2 Steps Back",
      "description": "Get more of the scene in frame",
      "action": "MOVE_BACK",
      "action_value": "2_steps",
      "priority": 3,
      "icon": "🚶"
    },
    
    {
      "title": "Try 3:4 Aspect Ratio",
      "description": "Cinematic format perfect for this scene",
      "action": "RATIO_4_3",
      "action_value": "3:4",
      "priority": 1,
      "icon": "📱"
    }
  ] 
}
IMPORTANT: Always suggest at least 2 actions from this list: FLASH_ON, FLASH_OFF, ZOOM_IN, ZOOM_OUT, ENABLE_NIGHT, ENABLE_PORTRAIT, GRID_ON, SWITCH_CAMERA,RATIO_16_9, 
Give me 3-4 suggestions based on what you see in the actual image.
"""
    }

    /**
     * Parse AI response into actionable suggestions
     */
    private fun parseActionableResponse(
        aiResponse: String,
        cameraInfo: DetailedCameraInfo
    ): List<AIEducationHelper.ActionableSuggestion> {

        return try {
            val jsonStart = aiResponse.indexOf("{")
            val jsonEnd = aiResponse.lastIndexOf("}") + 1

            if (jsonStart != -1 && jsonEnd > jsonStart) {
                val jsonString = aiResponse.substring(jsonStart, jsonEnd)
                val jsonObject = JSONObject(jsonString)
                val suggestionsArray = jsonObject.getJSONArray("suggestions")

                val suggestions = mutableListOf<AIEducationHelper.ActionableSuggestion>()

                for (i in 0 until minOf(suggestionsArray.length(), 4)) {

                    val suggestionObj = suggestionsArray.getJSONObject(i)


                    var suggestion = AIEducationHelper.ActionableSuggestion(

                        title = suggestionObj.getString("title"),
                        description = suggestionObj.getString("description"),
                        action = parseAction(suggestionObj.getString("action")),

                        icon = suggestionObj.getString("icon"),
                        priority = suggestionObj.optInt("priority", 1)

                    )

                    suggestions.add(suggestion)
// Extract target value from AI response
                    val actionValue = suggestionObj.optString("action_value", "")
                    if (actionValue.isNotEmpty()) {
                        suggestion = suggestion.copy(targetValue = actionValue)
                    }
                }
                // ADD this filtering after parsing suggestions
                val uniqueSuggestions = suggestions.filter { suggestion ->
                    !suggestionHistory.contains(suggestion.title)
                }.take(4)

// Update history
                suggestionHistory.addAll(uniqueSuggestions.map { it.title })
                if (suggestionHistory.size > 20) suggestionHistory.clear() // Reset after 20

                return suggestions
            } else {
                return parseTextToActions(aiResponse, cameraInfo)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Parse failed - AI Response was: $aiResponse", e)
            Log.e(TAG, "Ask Gemini", e)
            return getSmartFallbackActions(cameraInfo)
        }
    }


    private fun detectSceneType(cameraInfo: DetailedCameraInfo, analysis: RealTimeVisionAnalyzer.FrameAnalysis?): String {
        return when {
            cameraInfo.isUsingFrontCamera -> "SELFIE"
            analysis?.faceCount ?: 0 > 1 -> "GROUP PHOTO"
            analysis?.faceCount == 1 -> "PORTRAIT"
            analysis?.brightness ?: 0.0 < 60 -> "LOW LIGHT"
            analysis?.brightness ?: 0.0 > 160 -> "OUTDOOR/BRIGHT"
            else -> "GENERAL"
        }
    }

    private fun getExposureContext(analysis: RealTimeVisionAnalyzer.FrameAnalysis?): String {
        return when {
            analysis?.isOverexposed == true -> "OVEREXPOSED (too bright)"
            analysis?.isUnderexposed == true -> "UNDEREXPOSED (too dark)"
            else -> "BALANCED"
        }
    }

    private fun parseAction(actionString: String): AIEducationHelper.QuickAction {
        return when (actionString.uppercase()) {
            "FLASH_ON" -> AIEducationHelper.QuickAction.FLASH_ON
            "FLASH_OFF" -> AIEducationHelper.QuickAction.FLASH_OFF
            "ZOOM_OUT" -> AIEducationHelper.QuickAction.ZOOM_OUT
            "ZOOM_IN" -> AIEducationHelper.QuickAction.ZOOM_IN
            "SWITCH_CAMERA" -> AIEducationHelper.QuickAction.SWITCH_CAMERA
            "ENABLE_NIGHT" -> AIEducationHelper.QuickAction.ENABLE_NIGHT
            "DISABLE_NIGHT" -> AIEducationHelper.QuickAction.DISABLE_NIGHT
            "MOVE_CLOSER" -> AIEducationHelper.QuickAction.MOVE_CLOSER
            "MOVE_BACK" -> AIEducationHelper.QuickAction.MOVE_BACK
            "PORTRAIT_MODE" -> AIEducationHelper.QuickAction.ENABLE_PORTRAIT
            "NIGHT_MODE" -> AIEducationHelper.QuickAction.ENABLE_NIGHT
            "STABILIZATION_ON" -> AIEducationHelper.QuickAction.ENABLE_STABILIZATION
            "GRID_ON" -> AIEducationHelper.QuickAction.ENABLE_GRID
            "FILTER_WARM" -> AIEducationHelper.QuickAction.APPLY_WARM_FILTER
            "FILTER_VIVID" -> AIEducationHelper.QuickAction.APPLY_VIVID_FILTER
            "RATIO_16_9" -> AIEducationHelper.QuickAction.RATIO_16_9
            "RATIO_4_3" -> AIEducationHelper.QuickAction.RATIO_4_3
            "RATIO_1_1" -> AIEducationHelper.QuickAction.RATIO_1_1
            "RATIO_FULL" -> AIEducationHelper.QuickAction.RATIO_FULL
            else -> AIEducationHelper.QuickAction.HOLD_STEADY
        }
    }

    /**
     * Parse plain text into actionable suggestions
     */
    private fun parseTextToActions(
        text: String,
        cameraInfo: DetailedCameraInfo
    ): List<AIEducationHelper.ActionableSuggestion> {

        // Simple text analysis to extract actionable advice
        val lowerText = text.lowercase()
        val suggestions = mutableListOf<AIEducationHelper.ActionableSuggestion>()

        when {
            cameraInfo.isLowLight && lowerText.contains("flash") -> {
                suggestions.add(
                    AIEducationHelper.ActionableSuggestion(
                        "Turn on Flash",
                        "Low light needs flash for clear photos",
                        AIEducationHelper.QuickAction.FLASH_ON,
                        "⚡",
                        1
                    )
                )
            }

            cameraInfo.zoomRatio > 2f && (lowerText.contains("zoom") || lowerText.contains("closer")) -> {
                suggestions.add(
                    AIEducationHelper.ActionableSuggestion(
                        "Zoom Out",
                        "High zoom causes blur - try moving closer",
                        AIEducationHelper.QuickAction.ZOOM_OUT,
                        "🔍",
                        1
                    )
                )
            }

            else -> {
                suggestions.add(
                    AIEducationHelper.ActionableSuggestion(
                        "Hold Steady",
                        "Keep phone stable for sharp photos",
                        AIEducationHelper.QuickAction.HOLD_STEADY,
                        "🎯",
                        2
                    )
                )
            }
        }

        return suggestions.take(2)
    }

    /**
     * Smart fallback suggestions based on camera conditions
     */
    private fun getSmartFallbackActions(cameraInfo: DetailedCameraInfo): List<AIEducationHelper.ActionableSuggestion> {
        val suggestions = mutableListOf<AIEducationHelper.ActionableSuggestion>()

        // FORCE these specific features for demo
        val demoFeatures = listOf(
            // Flash
            if (cameraInfo.flashMode == androidx.camera.core.ImageCapture.FLASH_MODE_OFF) {
                AIEducationHelper.ActionableSuggestion(
                    "Turn Flash ON", "Brighten your photo with flash",
                    AIEducationHelper.QuickAction.FLASH_ON, "⚡", 1
                )
            } else {
                AIEducationHelper.ActionableSuggestion(
                    "Turn Flash OFF", "Try natural lighting",
                    AIEducationHelper.QuickAction.FLASH_OFF, "⚡", 1
                )
            },

            // ALWAYS include aspect ratio
            AIEducationHelper.ActionableSuggestion(
                "Try 16:9 Ratio", "Cinematic wide format for better framing",
                AIEducationHelper.QuickAction.RATIO_16_9, "📱", 1
            ),

            // ALWAYS include zoom
            if (cameraInfo.zoomRatio > 1.5f) {
                AIEducationHelper.ActionableSuggestion(
                    "Zoom to 1x", "Reset zoom for wider view",
                    AIEducationHelper.QuickAction.ZOOM_OUT, "🔍", 1
                )
            } else {
                AIEducationHelper.ActionableSuggestion(
                    "Zoom to 2x", "Get closer to your subject",
                    AIEducationHelper.QuickAction.ZOOM_IN, "🔍", 1
                )
            },

            // Grid
            AIEducationHelper.ActionableSuggestion(
                "Enable Grid", "Use rule of thirds for composition",
                AIEducationHelper.QuickAction.ENABLE_GRID, "⊞", 2
            )
        )

        return demoFeatures.take(3)
    }

    // Keep your existing methods for backward compatibility


    private suspend fun callGeminiTextOnly(prompt: String): String {
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)  // Lower temperature for more focused responses
                put("maxOutputTokens", 1000)  // Shorter responses
                put("topP", 0.8)
            })
        }
        Log.d("GeminiAIService", "Current API Key (first 8 chars): " + API_KEY.take(8))


        val request = Request.Builder()
            .url("$GEMINI_API_URL?key=$API_KEY")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            Log.e(TAG, "API Error: $responseBody")
            throw Exception("API call failed: ${response.code}")
        }

        return parseGeminiResponse(responseBody)
    }

    private fun parseGeminiResponse(responseBody: String): String {
        val jsonResponse = JSONObject(responseBody)
        val candidates = jsonResponse.getJSONArray("candidates")
        val firstCandidate = candidates.getJSONObject(0)
        val content = firstCandidate.getJSONObject("content")
        val parts = content.getJSONArray("parts")
        val text = parts.getJSONObject(0).getString("text")
        return text
    }

    private fun checkSignificantChange(
        current: DetailedCameraInfo,
        previous: DetailedCameraInfo?,
        currentAnalysis: RealTimeVisionAnalyzer.FrameAnalysis?,
        previousAnalysis: RealTimeVisionAnalyzer.FrameAnalysis?
    ): Boolean {
        if (previous == null) return true

        // INCREASED minimum wait time to reduce API calls
        val timeDiff = System.currentTimeMillis() - lastSuggestionTime
        if (timeDiff < 5000) return false  // Wait 5 seconds minimum

        // Major camera changes only
        val significantCameraChange = (
                kotlin.math.abs(current.zoomRatio - previous.zoomRatio) > 0.5f || // More threshold
                        current.isUsingFrontCamera != previous.isUsingFrontCamera ||
                        current.cameraMode != previous.cameraMode ||
                        current.flashMode != previous.flashMode
                )

        // Major scene changes only
        val significantSceneChange = if (currentAnalysis != null && previousAnalysis != null) {
            kotlin.math.abs(currentAnalysis.brightness - previousAnalysis.brightness) > 40 || // Higher threshold
                    kotlin.math.abs(currentAnalysis.faceCount - previousAnalysis.faceCount) > 0 ||
                    currentAnalysis.hasMotionBlur != previousAnalysis.hasMotionBlur
        } else true

        return significantCameraChange || significantSceneChange
    }



    private fun canMakeAPICall(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())

        if (today != lastResetDate) {
            dailyCallCount = 0
            lastResetDate = today
        }

        return dailyCallCount < MAX_DAILY_CALLS
    }

    private fun getFlashStatusText(flashMode: Int): String {
        return when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> "ON"
            ImageCapture.FLASH_MODE_OFF -> "OFF"
            ImageCapture.FLASH_MODE_AUTO -> "AUTO"
            else -> "OFF"
        }
    }
}
