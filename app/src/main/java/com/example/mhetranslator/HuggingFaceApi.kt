package com.example.mhetranslator

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Hugging Face Inference Providers fallback using Qwen3. */
object HuggingFaceApi {
    private const val TAG = "HuggingFaceApi"
    private const val ENDPOINT = "https://router.huggingface.co/v1/chat/completions"
    private const val MODEL = "Qwen/Qwen3-32B"
    private val apiKey get() = ApiKeyStore.get("huggingface")
    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    private data class Message(val role: String, val content: String)
    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Double,
        @SerializedName("max_tokens") val maxTokens: Int
    )
    private data class ChatResponse(val choices: List<Choice> = emptyList())
    private data class Choice(val message: Message? = null)

    suspend fun generate(prompt: String): String {
        if (!isConfigured) return ""
        return try {
            val body = gson.toJson(ChatRequest(
                model = MODEL,
                messages = listOf(
                    Message("system", "You are a precise Indian-language translation assistant. Follow the user instructions exactly. Return only the requested translation."),
                    Message("user", prompt)
                ),
                temperature = 0.2,
                maxTokens = 2048
            )).toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Qwen request failed ${response.code}: $responseBody")
                    return ""
                }
                gson.fromJson(responseBody, ChatResponse::class.java)
                    ?.choices?.firstOrNull()?.message?.content?.trim().orEmpty()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Qwen fallback failed", e)
            ""
        }
    }
    suspend fun translate(text: String, targetLanguage: String): String = generate("Translate the following text to $targetLanguage. For Hindi, output true Hinglish, never pure Hindi: retain 30-50% familiar content words in English Latin letters and use Devanagari only for Hindi connector words. For Marathi, retain familiar English content words in Latin letters. Return only the translation.\n\nText: $text")

    suspend fun translateLines(lines: List<String>, targetLanguage: String): List<String> = lines.map { translate(it, targetLanguage).ifBlank { it } }

}
