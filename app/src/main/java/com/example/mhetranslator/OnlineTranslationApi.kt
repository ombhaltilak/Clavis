package com.example.mhetranslator

/** Online style providers with the same no-billing ML Kit source-to-English step. */
object OnlineTranslationApi {
    suspend fun translate(text: String, targetLanguage: String, provider: String): String {
        val english = OfflineTranslationApi.toEnglish(text)
        return when (provider) {
            "qwen" -> HuggingFaceApi.translate(english, targetLanguage)
            else -> GeminiApi.translate(english, targetLanguage)
        }
    }

    suspend fun translateLines(texts: List<String>, targetLanguage: String, provider: String): List<String> =
        texts.map { translate(it, targetLanguage, provider) }
}
