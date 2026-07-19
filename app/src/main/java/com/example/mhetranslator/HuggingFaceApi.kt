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

    private data class Message(
        val role: String,
        val content: String = "",
        @SerializedName("reasoning_content") val reasoningContent: String? = null
    )
    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Double,
        @SerializedName("max_tokens") val maxTokens: Int,
        @SerializedName("chat_template_kwargs") val chatTemplateKwargs: Map<String, Boolean> = mapOf("enable_thinking" to false)
    )
    private data class ChatResponse(val choices: List<Choice> = emptyList())
    private data class Choice(val message: Message? = null)

    suspend fun generate(prompt: String): String {
        if (!isConfigured) return ""
        return try {
            val body = gson.toJson(ChatRequest(
                model = MODEL,
                messages = listOf(
                    Message("system", "You are Clavis. Translate precisely into natural Indian mixed language. Never explain, reason aloud, use think tags, or add labels. Return only the final translated text."),
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
                    ?.choices?.firstOrNull()?.message?.content.orEmpty()
                    .replace(Regex("(?s)<think>.*?</think>"), "")
                    .removePrefix("Translation:").trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Qwen fallback failed", e)
            ""
        }
    }
    suspend fun translate(text: String, targetLanguage: String): String = OfflineTranslationApi.preserveEverydayEnglishScript(generate("""TASK: Translate the English input into natural Indian mixed $targetLanguage.
OUTPUT: Return only one final translation—no explanation, labels, markdown, or think tags.
SCRIPT: Hindi/Marathi words use Devanagari. Everyday Indian English words always stay in English Latin letters: phone, ticket, station, office, meeting, message, time, school, market, doctor, problem, bus, train, app, screen, online. Never transliterate them into Devanagari.
STYLE: Use the words people naturally say in India; do not use a fixed language ratio.
EXAMPLE: English: I have a meeting at the office after lunch. Hindi mix: मेरे पास lunch के बाद office में meeting है. Marathi mix: माझी lunch नंतर office मध्ये meeting आहे.

INPUT: $text
FINAL TRANSLATION:"""))

    suspend fun translateLines(lines: List<String>, targetLanguage: String): List<String> = lines.map { translate(it, targetLanguage).ifBlank { it } }

}
