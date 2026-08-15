package com.example.mhetranslator

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Hugging Face Inference Providers fallback using instruction-tuned Qwen. */
object HuggingFaceApi {
    private const val TAG = "HuggingFaceApi"
    private const val ENDPOINT = "https://router.huggingface.co/v1/chat/completions"
    private const val MODEL = "Qwen/Qwen2.5-72B-Instruct"
    private const val RESPONSE_TIMEOUT_SECONDS = 90L
    private const val TRANSLATION_SYSTEM_PROMPT =
        "You are Clavis. Translate precisely into natural Indian mixed language. Never explain, reason aloud, use think tags, or add labels. Return only the final translated text."
    private const val DICTIONARY_SYSTEM_PROMPT =
        "You are Clavis, a bilingual dictionary assistant. Follow the requested target language exactly and return all three sections in this exact Markdown format: **1. Meaning in Devanagari:** followed by the genuine Hindi or Marathi meaning; **2. Simple explanation:** followed by a concise explanation in that target language; **3. Example sentence:** followed by one complete, natural target-language sentence. The dictionary headword itself must be genuinely translated in the Meaning section, never phonetically transliterated into Devanagari. The example sentence must not be a full English sentence. Write Hindi or Marathi words in Devanagari, retaining Latin script only for genuinely everyday English terms. Do not reduce the response to a translation, add reasoning, or add unrelated labels."
    private const val HINDI_DICTIONARY_RULE =
        " HINDI MODE (CRITICAL): The target language is Hindi, not Marathi. Write every Hindi word in Devanagari and never use Marathi words or Romanized Hindi. For example, for apple use **1. Meaning in Devanagari:** followed by सफरचंद, then a Hindi explanation ending in है, and a natural Hindi example such as Doctor ने मुझे fit रहने के लिए रोज़ एक apple खाने को कहा है।"
    private val apiKey get() = ApiKeyStore.get("huggingface")
    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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

    suspend fun generate(prompt: String): String = generate(prompt, TRANSLATION_SYSTEM_PROMPT)

    /** Uses Qwen for dictionary responses without the translation-only system instruction. */
    suspend fun generateDictionary(prompt: String, targetLanguage: String): String =
        generate(prompt, DICTIONARY_SYSTEM_PROMPT + if (targetLanguage == "Hindi") HINDI_DICTIONARY_RULE else "")

    private suspend fun generate(prompt: String, systemPrompt: String): String {
        if (!isConfigured) return ""
        return try {
            val body = gson.toJson(ChatRequest(
                model = MODEL,
                messages = listOf(
                    Message("system", systemPrompt),
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
    suspend fun translate(text: String, targetLanguage: String): String {
        val protected = OfflineTranslationApi.protectEverydayEnglishTerms(text)
        val translated = generate("""TASK: Translate the English input into natural ${if (targetLanguage == "Marathi") "Marathlish" else "Hinglish"}, not plain $targetLanguage.
OUTPUT: Return only one final translation—no explanation, labels, markdown, or think tags.
SCRIPT: In Hinglish use Hindi words in Devanagari; in Marathlish use Marathi words in Devanagari. Never write Hindi or Marathi phonetically in Roman/Latin letters: "se pehle", "kiye jaate hain", and "mujhe" are wrong. Every English word in the INPUT that people commonly use in India must be copied exactly in English Latin letters, including project, files, codes, phone, ticket, station, office, meeting, message, time, school, market, doctor, problem, bus, train, app, screen, online. Never transliterate, split, or translate those words into Devanagari.
STYLE: Use the words people naturally say in India; do not use a fixed language ratio.
TOKENS: The input can contain protection tokens such as [[CLAVIS_KEEP_EN_0]]. Copy every token exactly, unchanged, in its original position. Do not translate, transliterate, remove, split, or add spaces inside a token.
EXAMPLE: English: I have a meeting at the office after lunch. Hindi mix: मेरे पास lunch के बाद office में meeting है. Marathi mix: माझी lunch नंतर office मध्ये meeting आहे.

INPUT: ${protected.text}
FINAL TRANSLATION:""")
        return OfflineTranslationApi.preserveEverydayEnglishScript(protected.restore(translated))
    }

    suspend fun translateLines(lines: List<String>, targetLanguage: String): List<String> = lines.map { translate(it, targetLanguage).ifBlank { it } }

}
