package com.example.mhetranslator

import android.content.Context

object ApiKeyStore {
    private const val PREFS = "mhe_api_keys"
    private var context: Context? = null
    fun initialize(value: Context) { context = value.applicationContext }
    private fun prefs() = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun get(name: String): String = prefs()?.getString(name, "")?.trim().orEmpty()
    fun save(gemini: String, huggingFace: String, cloud: String): Boolean {
        return prefs()?.edit()
            ?.putString("gemini", gemini.trim())
            ?.putString("huggingface", huggingFace.trim())
            ?.putString("cloud", cloud.trim())
            ?.commit() ?: false
    }
}
