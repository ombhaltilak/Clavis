package com.example.mhetranslator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the Gemma model lifecycle:
 * - Checks if model is already downloaded
 * - Downloads from Hugging Face using the provided access token
 * - Reports progress during download
 * - Stores model in app's internal files directory
 */
object GemmaModelManager {

    private const val MODEL_FILENAME = "gemma-4-e2b-it.litertlm"

    // HuggingFace direct download URL (native Android variant, 2.4 GB)
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    private const val HF_TOKEN = "YOUR_HF_TOKEN_HERE"

    fun getModelPath(context: Context): String {
        return File(context.filesDir, MODEL_FILENAME).absolutePath
    }

    fun isModelDownloaded(context: Context): Boolean {
        val file = File(context.filesDir, MODEL_FILENAME)
        // Ensure file exists and is reasonably large (> 1GB)
        return file.exists() && file.length() > 500_000_000L
    }

    fun getModelSizeMB(context: Context): Long {
        val file = File(context.filesDir, MODEL_FILENAME)
        return if (file.exists()) file.length() / (1024 * 1024) else 0
    }

    /**
     * Downloads the Gemma model using the GitHub Release URL.
     * @param onProgress callback with (bytesDownloaded, totalBytes, percentComplete)
     * @return Result with success or failure
     */
    suspend fun downloadModel(
        context: Context,
        onProgress: (downloaded: Long, total: Long, percent: Int) -> Unit
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val outputFile = File(context.filesDir, MODEL_FILENAME)
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")

        try {
            val url = URL(MODEL_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "ClavisTranslator/1.0")
            connection.setRequestProperty("Authorization", "Bearer $HF_TOKEN")

            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect()
                return@withContext Result.failure(
                    Exception("Download failed: HTTP $responseCode")
                )
            }

            val totalBytes = connection.contentLengthLong.let {
                if (it > 0) it else 2_000_000_000L // Fallback to 2GB if unknown
            }

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(65536) // 64KB buffer for faster download
            var bytesDownloaded = 0L
            var bytesRead: Int

            inputStream.use { input ->
                outputStream.use { output ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        val percent = if (totalBytes > 0) {
                            ((bytesDownloaded * 100) / totalBytes).toInt().coerceAtMost(99)
                        } else 0

                        onProgress(bytesDownloaded, totalBytes, percent)
                    }
                }
            }

            connection.disconnect()

            // Rename temp file to final
            if (tempFile.exists()) {
                outputFile.delete()
                tempFile.renameTo(outputFile)
            }

            onProgress(bytesDownloaded, bytesDownloaded, 100)
            Result.success(true)

        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    fun deleteModel(context: Context) {
        File(context.filesDir, MODEL_FILENAME).delete()
        File(context.filesDir, "$MODEL_FILENAME.tmp").delete()
    }
}
