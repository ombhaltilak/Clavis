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

    private const val OUT_MARKER = "<<<OUT>>>"
    private const val END_MARKER = "<<<END>>>"

    suspend fun rewrite(context: Context?, englishText: String, targetLanguage: String): String {
        if (context == null || englishText.isBlank() || !GemmaModelManager.isModelDownloaded(context)) {
            return ""
        }
        val prompt = rewritePrompt(englishText, targetLanguage)
        val raw = runOnEngine(context, prompt) ?: return ""
        return cleanTranslationOutput(raw, keepLineBreaks = englishText.contains("\n"))
    }

    /** Runs an arbitrary prompt on the downloaded Gemma model without network access. */
    suspend fun generate(context: Context?, prompt: String): String {
        if (context == null || prompt.isBlank() || !GemmaModelManager.isModelDownloaded(context)) {
            return ""
        }
        val raw = runOnEngine(context, prompt) ?: return ""
        // Generic entry point: no marker-stripping assumptions, just trim.
        return raw.trim()
    }

    private suspend fun runOnEngine(context: Context, prompt: String): String? =
        withContext(Dispatchers.IO) {
            val localEngine = engineFor(context) ?: return@withContext null
            val conversation = localEngine.createConversation()
            try {
                val response = conversation.sendMessage(prompt)
                response.contents.contents.filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
            } catch (error: Exception) {
                Log.e(TAG, "Gemma generation failed", error)
                null
            } finally {
                conversation.close()
            }
        }

    private fun cleanTranslationOutput(text: String, keepLineBreaks: Boolean): String {
        // Prefer the delimited block; fall back to the raw text if the model
        // dropped the markers (small on-device models sometimes do).
        val betweenMarkers = text.substringAfter(OUT_MARKER, text)
            .substringBefore(END_MARKER)
            .trim()

        val withoutLabel = betweenMarkers
            .removePrefix("FINAL TRANSLATION:")
            .removePrefix("Translation:")
            .removePrefix("Hinglish:")
            .removePrefix("Marathlish:")
            .trim('"', '\'', ' ', '\n')

        val normalized =
            if (keepLineBreaks) withoutLabel else withoutLabel.replace(Regex("\\s+"), " ")
        return OfflineTranslationApi.preserveEverydayEnglishScript(normalized)
    }

    fun close() {
        engine?.close()
        engine = null
        loadedModelPath = null
    }

    private suspend fun engineFor(context: Context): Engine? = engineLock.withLock {
        val modelPath = GemmaModelManager.getModelPath(context)
        engine?.takeIf { it.isInitialized() && loadedModelPath == modelPath }
            ?.let { return@withLock it }
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
        val isMarathi = targetLanguage == "Marathi"
        val styleName = if (isMarathi) "Marathi-English code-mixed (Marathlish)" else "Hindi-English code-mixed (Hinglish)"
        val examples = if (isMarathi) {
            """
            English: A Large Language Model (LLM) is based on [neural networks and transformer architectures](https://example.com).
            Marathlish: एक Large Language Model (LLM) हा [neural networks and transformer architectures](https://example.com) वर आधारित artificial intelligence program आहे.

            English: It analyzes millions of language patterns to predict the next word.
            Marathlish: तो millions of language patterns analyze करून पुढचा word predict करतो.
            """.trimIndent()
        } else {
            """
            English: A Large Language Model (LLM) is based on [neural networks and transformer architectures](https://example.com).
            Hinglish: एक Large Language Model (LLM) एक प्रकार का artificial intelligence program है, जो [neural networks and transformer architectures](https://example.com) पर आधारित है।

            English: It analyzes millions of language patterns to predict the next word.
            Hinglish: यह millions of language patterns analyze करके अगला word predict करता है।

            English: Everyday English words and phrases are protected before translation and restored exactly afterwards.
            Hinglish: Everyday English words और phrases को translation से पहले protect किया जाता है और बाद में exactly restore किया जाता है।
            """.trimIndent()
        }
        val hindiRules = if (isMarathi) "" else """
        HINDI MODE — CRITICAL:
        - The target language is Hindi only, never Marathi. Use natural Hindi grammar, sentence order, and endings:
          है, हैं, था, थी, थे, करता है, करती है, करते हैं, किया जाता है, के लिए, में, पर, और, लेकिन.
        - Never use Marathi words or forms such as आहे, आहेत, करून, मध्ये, आणि, च्या, पुढचा, or शकतो.
        - Write every Hindi word in Devanagari. Keep Latin letters only for English terms that are naturally used
          in English. Never write Roman Hindi such as se pehle, kiya jata hai, or mujhe.
        - Make complete natural Hindi-Hinglish sentences. For example: यह model text को समझकर जवाब generate करता है।
        """.trimIndent()

        return """
        Translate the English input into fluent, natural $styleName. The translation must read like a clear
        modern Indian explanation, with natural grammar and no literal word-by-word phrasing.

        SCRIPT AND STYLE:
        - Write the $targetLanguage grammar in Devanagari. Never use Romanized Hindi or Marathi.
        - Keep familiar modern English terms in their original Latin spelling when they sound natural in an
          Indian technical, educational, workplace, or daily-life explanation. This includes technical phrases
          such as Large Language Model, artificial intelligence, neural networks, transformer architectures,
          human text, dataset, train, books, articles, websites, pre-programmed rules, sequence, logical,
          predict, generate, language patterns, analyze, probability, context, complex language tasks,
          conversational chatting, document summarization, language translation, writing code, and perform.
        - Translate the surrounding grammar, connectors, and ordinary non-technical words into natural
          $targetLanguage. Do not force a fixed English/target-language ratio.

        $hindiRules

        QUALITY RULES:
        - Preserve every sentence meaning, relationship, and level of detail. Do not omit or add information.
        - Never duplicate a word by translating it and then adding an English gloss in parentheses. For example,
          never produce train (trained), word (next word), or a Marathi/Hindi word followed by its English copy.
          Keep parentheses only when they exist in the input, such as (LLM).
        - Never phonetic-transliterate English into Devanagari. A term is either kept in Latin script or properly
          translated into a real $targetLanguage word.
        - Preserve URLs, markdown links, headings, citations such as [1, 2, 3], numbers, code, and the original
          line and paragraph breaks exactly.
        - The input can contain tokens such as [[CLAVIS_KEEP_EN_0]]. Copy every token exactly in the same place.

        QUALITY EXAMPLES:
        $examples

        OUTPUT:
        Return only the final $styleName translation. No explanation, label, quote, or repeated source text.
        Put the answer between $OUT_MARKER and $END_MARKER.

        INPUT:
        $englishText

        $OUT_MARKER
        """.trimIndent()
    }
}
