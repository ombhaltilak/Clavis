package com.example.mhetranslator

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Hugging Face Inference API Client for Translation
 * 
 * Uses the FREE tier (5000 requests/month)
 * Supports EN->HI and EN->MR translation
 * 
 * Token: YOUR_HF_TOKEN_HERE
 */
object HuggingFaceApi {
    private const val TAG = "HFApi"
    private const val BASE_URL = "https://api-inference.huggingface.co"
    private const val TIMEOUT_SECONDS = 30L
    
    // FREE API token - provided by user
    private const val API_TOKEN = "YOUR_HF_TOKEN_HERE"
    
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
    
    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()
    
    // Model endpoints
    private val translationModels = mapOf(
        "Hindi" to "Helsinki-NLP/opus-mt-en-hi",
        "Marathi" to "Helsinki-NLP/opus-mt-en-mr"
    )
    
    data class TranslationRequest(
        val inputs: String,
        @SerializedName("parameters")
        val params: Map<String, String>? = null
    )
    
    data class TranslationResponse(
        @SerializedName("translation_text")
        val translationText: String? = null,
        @SerializedName("generated_text")
        val generatedText: String? = null,
        @SerializedName("error")
        val error: String? = null
    )
    
    /**
     * Translate text using Hugging Face API
     * 
     * @param text Text to translate
     * @param targetLanguage "Hindi" or "Marathi"
     * @return Translated text in Devanagari script
     */
    suspend fun translate(
        text: String,
        targetLanguage: String
    ): String {
        return try {
            val modelId = translationModels[targetLanguage]
                ?: throw IllegalArgumentException("Unsupported language: $targetLanguage")
            
            val translationRequest = TranslationRequest(
                inputs = text,
                params = mapOf("temperature" to "0.7", "max_length" to "512")
            )
            
            val requestBody = gson.toJson(translationRequest).toRequestBody(jsonMediaType)
            
            val httpRequest = Request.Builder()
                .url("$BASE_URL/models/$modelId")
                .header("Authorization", "Bearer $API_TOKEN")
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()
            
            val response = client.newCall(httpRequest).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                Log.e(TAG, "API Error ${response.code}: $errorBody")
                throw IOException("API request failed: ${response.code} - $errorBody")
            }
            
            val responseBody = response.body?.string()
            val translationResponse = gson.fromJson(responseBody, Array<TranslationResponse>::class.java)
            
            // Try different response formats
            translationResponse.firstOrNull()?.let { resp ->
                resp.translationText ?: resp.generatedText ?: resp.error ?: ""
            } ?: responseBody ?: ""
            
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed: ${e.message}", e)
            // Fallback to empty string, caller should handle
            ""
        }
    }
    
    /**
     * Translate multiple texts (calls translate() for each text)
     * Note: HF API doesn't support true batch, so we call sequentially
     */
    suspend fun translateBatch(
        texts: List<String>,
        targetLanguage: String
    ): List<String> {
        return texts.map { text ->
            try {
                translate(text, targetLanguage)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to translate text: ${e.message}")
                ""
            }
        }
    }
    
    /**
     * Check API token validity and quota
     */
    suspend fun checkTokenValidity(): Boolean {
        return try {
            val httpRequest = Request.Builder()
                .url("$BASE_URL/whoami-v2")
                .header("Authorization", "Bearer $API_TOKEN")
                .get()
                .build()
            
            val response = client.newCall(httpRequest).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Token check failed: ${e.message}")
            false
        }
    }
}
