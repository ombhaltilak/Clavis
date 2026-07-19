package com.example.mhetranslator

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runs the optional downloaded Gemma model entirely on-device. */
object GemmaRewriteApi {
    private const val TAG = "GemmaRewrite"
    private val engineLock = Mutex()
    private var engine: Engine? = null
    private var loadedModelPath: String? = null

    suspend fun rewrite(context: Context?, englishText: String, targetLanguage: String): String =
        generate(context, rewritePrompt(englishText, targetLanguage))

    /** Runs an arbitrary prompt on the downloaded Gemma model without network access. */
    suspend fun generate(context: Context?, prompt: String): String {
        if (context == null || prompt.isBlank() || !GemmaModelManager.isModelDownloaded(context)) {
            return ""
        }
        return withContext(Dispatchers.IO) {
            val localEngine = engineFor(context) ?: return@withContext ""
            val conversation = localEngine.createConversation()
            try {
                val response = conversation.sendMessage(prompt)
                response.contents.contents.filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
                    .trim()
            } catch (error: Exception) {
                Log.e(TAG, "Gemma generation failed", error)
                ""
            } finally {
                conversation.close()
            }
        }
    }

    fun close() {
        engine?.close()
        engine = null
        loadedModelPath = null
    }

    private suspend fun engineFor(context: Context): Engine? = engineLock.withLock {
        val modelPath = GemmaModelManager.getModelPath(context)
        engine?.takeIf { it.isInitialized() && loadedModelPath == modelPath }?.let { return@withLock it }
        close()
        try {
            Engine(EngineConfig(modelPath = modelPath)).also {
                it.initialize()
                engine = it
                loadedModelPath = modelPath
            }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to initialize Gemma", error)
            null
        }
    }

    private fun rewritePrompt(englishText: String, targetLanguage: String): String {
        val style = if (targetLanguage == "Marathi") "Marathlish" else "Hinglish"
        return """
            TASK: Translate the English input into natural $style.
            OUTPUT: Return only one final translation—no explanation, labels, markdown, or reasoning.
            SCRIPT: Hindi/Marathi words use Devanagari. Everyday Indian English words always stay in English Latin letters: phone, ticket, station, office, meeting, message, time, school, market, doctor, problem, bus, train, app, screen, online. Never transliterate these words into Devanagari.
            STYLE: Use the words people naturally say in India; do not use a fixed language ratio.
            EXAMPLE: English: I have a meeting at the office after lunch. Hinglish: मेरे पास lunch के बाद office में meeting है. Marathlish: माझी lunch नंतर office मध्ये meeting आहे.
            Preserve names, numbers, URLs, codes, and line breaks.

            INPUT: $englishText
            FINAL TRANSLATION:
        """.trimIndent()
    }
}
