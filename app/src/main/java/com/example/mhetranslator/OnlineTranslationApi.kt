package com.example.mhetranslator

/** Online style providers with the same no-billing ML Kit source-to-English step. */
object OnlineTranslationApi {
    suspend fun translate(text: String, targetLanguage: String, provider: String): String {
        val protected = OfflineTranslationApi.protectEverydayEnglishTerms(text)
        val english = OfflineTranslationApi.toEnglish(protected.text)
        val translated = when (provider) {
            "qwen" -> HuggingFaceApi.translate(english, targetLanguage)
            else -> GeminiApi.translate(english, targetLanguage)
        }
        return OfflineTranslationApi.preserveEverydayEnglishScript(protected.restore(translated))
    }

    suspend fun translateLines(texts: List<String>, targetLanguage: String, provider: String): List<String> =
        texts.map { translate(it, targetLanguage, provider) }
}
