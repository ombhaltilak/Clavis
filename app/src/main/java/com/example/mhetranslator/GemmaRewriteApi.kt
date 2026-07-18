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

    suspend fun rewrite(context: Context?, englishText: String, targetLanguage: String): String {
        if (context == null || englishText.isBlank() || !GemmaModelManager.isModelDownloaded(context)) {
            return ""
        }
        return withContext(Dispatchers.IO) {
            val localEngine = engineFor(context) ?: return@withContext ""
            val conversation = localEngine.createConversation()
            try {
                val response = conversation.sendMessage(rewritePrompt(englishText, targetLanguage))
                response.contents.contents.filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
                    .trim()
            } catch (error: Exception) {
                Log.e(TAG, "Gemma rewrite failed", error)
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
            For Hinglish, do not produce pure Hindi: retain 30-50% familiar content words in English Latin letters and use Devanagari only for Hindi connector words. For Marathlish, retain familiar English content words in Latin letters and use Devanagari for Marathi grammar words.
            Preserve names, numbers, URLs, codes, and line breaks. Return only the rewritten text.

            English text:
            $englishText
        """.trimIndent()
    }
}
