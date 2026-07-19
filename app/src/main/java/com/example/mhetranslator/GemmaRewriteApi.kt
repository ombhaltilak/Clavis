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
            Rewrite the English text below into natural $style.
            Use natural everyday Indian speech, not a fixed language percentage. This is a strict script rule for both Hinglish and Marathlish: every common daily-use English word MUST stay in English Latin letters; never write it in Devanagari or transliterated letters. Keep phone, ticket, station, office, meeting, message, time, school, market, doctor, problem, bus, train, app, screen, and online in Latin script, never forms such as फोन, टिकट, स्टेशन, ऑफिस, मीटिंग, मैसेज, टाइम, स्कूल, मार्केट, डॉक्टर, प्रॉब्लम, बस, ट्रेन, ऐप, स्क्रीन, or ऑनलाइन. Translate only words normally spoken in Hindi or Marathi. Do not force either language merely to meet a ratio.
            Preserve names, numbers, URLs, codes, and line breaks. Return only the rewritten text.

            English text:
            $englishText
        """.trimIndent()
    }
}
