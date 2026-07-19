package com.example.mhetranslator

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Fully offline pipeline: OCR -> ML Kit language ID -> ML Kit to English ->
 * optional local Gemma rewrite into Hinglish/Marathlish.
 */
object OfflineTranslationApi {
    private const val MODEL_DOWNLOAD_TIMEOUT_MS = 45_000L
    private const val LANGUAGE_ID_TIMEOUT_MS = 10_000L
    private const val TAG = "OfflineTranslation"
    /** Ensures common Indian English words stay readable in their original Latin script. */
    fun preserveEverydayEnglishScript(text: String): String {
        val replacements = linkedMapOf(
            "फोन" to "phone", "टिकट" to "ticket", "स्टेशन" to "station", "ऑफिस" to "office",
            "मीटिंग" to "meeting", "मैसेज" to "message", "संदेश" to "message", "टाइम" to "time",
            "स्कूल" to "school", "मार्केट" to "market", "डॉक्टर" to "doctor", "प्रॉब्लम" to "problem",
            "बस" to "bus", "ट्रेन" to "train", "ऐप" to "app", "स्क्रीन" to "screen", "ऑनलाइन" to "online",
            "मोबाइल" to "mobile", "कंप्यूटर" to "computer", "लैपटॉप" to "laptop", "चार्जर" to "charger",
            "कैमरा" to "camera", "फोटो" to "photo", "वीडियो" to "video", "कॉल" to "call",
            "नंबर" to "number", "इंटरनेट" to "internet", "बैंक" to "bank", "पेमेंट" to "payment",
            "कार्ड" to "card", "बिल" to "bill", "हॉस्पिटल" to "hospital", "होटल" to "hotel",
            "प्लान" to "plan", "फ्रेंड" to "friend", "फैमिली" to "family", "गेम" to "game",
            "क्लास" to "class", "कॉलेज" to "college", "टीचर" to "teacher", "स्टूडेंट" to "student"
        )
        return replacements.entries.fold(text) { current, (from, to) ->
            current.replace(Regex("(?<![\\p{L}\\p{M}])" + Regex.escape(from) + "(?![\\p{L}\\p{M}])"), to)
        }
    }

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun languageCode(targetLanguage: String): String = when (targetLanguage) {
        "Hindi" -> TranslateLanguage.HINDI
        "Marathi" -> TranslateLanguage.MARATHI
        else -> error("Unsupported offline language: $targetLanguage")
    }

    private fun createTranslator(sourceLanguage: String, targetLanguage: String): Translator =
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build()
        )

    private suspend fun ensureModelDownloaded(translator: Translator) {
        Tasks.await(
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()),
            MODEL_DOWNLOAD_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private suspend fun detectLanguage(text: String): String = withContext(Dispatchers.IO) {
        val identifier = LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.34f)
                .build()
        )
        try {
            Tasks.await(identifier.identifyLanguage(text), LANGUAGE_ID_TIMEOUT_MS, TimeUnit.MILLISECONDS).lowercase()
        } finally {
            identifier.close()
        }
    }

    private fun supportedLanguage(languageTag: String): String? {
        if (languageTag == "und") return null
        return TranslateLanguage.fromLanguageTag(languageTag)
            ?: TranslateLanguage.fromLanguageTag(languageTag.substringBefore('-'))
    }

    private suspend fun translateWithMlKit(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String {
        if (sourceLanguage == targetLanguage) return text
        val translator = createTranslator(sourceLanguage, targetLanguage)
        return try {
            ensureModelDownloaded(translator)
            Tasks.await(
                translator.translate(text),
                MODEL_DOWNLOAD_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            ).trim()
        } finally {
            translator.close()
        }
    }

    suspend fun toEnglish(text: String): String = try {
        translateToEnglish(text)
    } catch (error: Exception) {
        Log.w(TAG, "ML Kit source-to-English step failed; using original text", error)
        text
    }

    private suspend fun translateToEnglish(text: String): String {
        val sourceLanguage = supportedLanguage(detectLanguage(text))
        if (sourceLanguage == null || sourceLanguage == TranslateLanguage.ENGLISH) return text
        return translateWithMlKit(text, sourceLanguage, TranslateLanguage.ENGLISH)
    }

    suspend fun prepare(targetLanguage: String) = withContext(Dispatchers.IO) {
        val translator = createTranslator(TranslateLanguage.ENGLISH, languageCode(targetLanguage))
        try {
            ensureModelDownloaded(translator)
        } finally {
            translator.close()
        }
    }

    suspend fun translate(text: String, targetLanguage: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        try {
            val english = toEnglish(text)
            preserveEverydayEnglishScript(
                GemmaRewriteApi.rewrite(appContext, english, targetLanguage).ifBlank {
                    // Keep offline translation usable until the optional Gemma model is installed.
                    translateWithMlKit(english, TranslateLanguage.ENGLISH, languageCode(targetLanguage))
                }
            )
        } catch (error: Exception) {
            Log.e(TAG, "Offline translation failed", error)
            throw error
        }
    }

    suspend fun translateLines(lines: List<String>, targetLanguage: String): List<String> =
        lines.map { translate(it, targetLanguage) }
}
