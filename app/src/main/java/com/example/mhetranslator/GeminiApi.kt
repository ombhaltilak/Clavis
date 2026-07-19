package com.example.mhetranslator

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

/** Gemini API client using the current REST generateContent endpoint. */
object GeminiApi {
    private const val TAG = "GeminiApi"
    private const val MODEL = "gemini-3.5-flash"
    private val apiKey get() = ApiKeyStore.get("gemini")
    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    private data class Part(val text: String)
    private data class Content(val parts: List<Part>)
    private data class GenerationConfig(
        val temperature: Double,
        @SerializedName("maxOutputTokens") val maxOutputTokens: Int
    )
    private data class GenerateRequest(
        val contents: List<Content>,
        val generationConfig: GenerationConfig
    )
    private data class Candidate(val content: Content? = null)
    private data class GenerateResponse(val candidates: List<Candidate> = emptyList())

    suspend fun generate(prompt: String): String {
        if (!isConfigured) return ""
        return try {
            val body = gson.toJson(
                GenerateRequest(
                    contents = listOf(Content(listOf(Part(prompt)))),
                    generationConfig = GenerationConfig(temperature = 0.2, maxOutputTokens = 2048)
                )
            ).toRequestBody(jsonMediaType)
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("key", apiKey)
                .build()
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Gemini request failed ${response.code}: $responseBody")
                    return ""
                }
                gson.fromJson(responseBody, GenerateResponse::class.java)
                    ?.candidates?.firstOrNull()?.content?.parts
                    ?.joinToString(separator = "") { it.text }
                    ?.trim().orEmpty()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Gemini request failed", error)
            ""
        }
    }

    private suspend fun generateWithFallback(prompt: String): String {
        val geminiResponse = generate(prompt)
        if (geminiResponse.isNotBlank()) return geminiResponse
        if (!HuggingFaceApi.isConfigured) return ""
        Log.w(TAG, "Gemini unavailable; using Qwen fallback")
        return HuggingFaceApi.generate(prompt)
    }

    suspend fun translate(text: String, targetLanguage: String): String = OfflineTranslationApi.preserveEverydayEnglishScript(generateWithFallback(
        """Translate the following text into ${if (targetLanguage == "Marathi") "Marathlish" else "Hinglish"}—natural mixed Indian language, not plain $targetLanguage.
Use natural everyday Indian speech, not a fixed language percentage. This is a strict script rule: every common daily-use English word MUST remain in English Latin letters, never Devanagari or a transliteration. For example write phone, ticket, station, office, meeting, message, time, school, market, doctor, problem, bus, train, app, screen, and online — never फोन, टिकट, स्टेशन, ऑफिस, मीटिंग, मैसेज, टाइम, स्कूल, मार्केट, डॉक्टर, प्रॉब्लम, बस, ट्रेन, ऐप, स्क्रीन, or ऑनलाइन. Translate only words normally spoken in Hindi or Marathi. Example: Railway station कहाँ है?
For Hinglish, use natural Hindi for the remaining language; for Marathlish, use natural Marathi. Apply exactly the same Latin-script rule in both modes. Do not force either language merely to meet a ratio.
Preserve names, numbers, URLs, codes, and line breaks. Return only the translation.

Text: $text"""
    ))

    suspend fun translateLines(lines: List<String>, targetLanguage: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        val input = org.json.JSONArray(lines).toString()
        val raw = generateWithFallback(
            """Translate this JSON array into ${if (targetLanguage == "Marathi") "Marathlish" else "Hinglish"}, not plain $targetLanguage. Use natural everyday Indian speech, not a fixed language percentage. Strict script rule: write every common daily-use English word only in English Latin letters, never Devanagari/transliterated letters. Keep words such as phone, ticket, station, office, meeting, message, time, school, market, doctor, problem, bus, train, app, screen, and online exactly in Latin script; never output forms such as फोन, टिकट, स्टेशन, ऑफिस, मीटिंग, मैसेज, टाइम, स्कूल, मार्केट, डॉक्टर, प्रॉब्लम, बस, ट्रेन, ऐप, स्क्रीन, or ऑनलाइन. Translate only words normally spoken in Hindi or Marathi. Hinglish must use Hindi for the remaining language; Marathlish must use Marathi. Apply this same rule in both modes. Do not force either language merely to meet a ratio. Return only a valid JSON array with the same number and order of strings.
Return only a valid JSON array with the same number and order of strings.

$input"""
        ).trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val array = org.json.JSONArray(raw)
            List(lines.size) { index -> OfflineTranslationApi.preserveEverydayEnglishScript(array.optString(index, lines[index])) }
        } catch (error: Exception) {
            Log.e(TAG, "Could not parse line translation response", error)
            lines
        }
    }

    suspend fun classifyIntent(userText: String): String = generateWithFallback(
        """You are Clavis, a voice assistant in a translation app.
Return only one command: LIVE_TRANSLATE, STOP_TRANSLATE, CROP_TRANSLATE,
CHANGE_LANG:Hindi, CHANGE_LANG:Marathi, DICTIONARY:word, OPEN_SETTINGS,
or ANSWER: followed by one short answer.

User: $userText"""
    )

    suspend fun answer(question: String): String =
        generateWithFallback("Answer in one or two short sentences: $question")
}
