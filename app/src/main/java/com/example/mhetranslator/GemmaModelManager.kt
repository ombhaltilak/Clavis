package com.example.mhetranslator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Manages the optional on-device Gemma model and resumable downloads. */
object GemmaModelManager {

    private const val MODEL_FILENAME = "gemma-4-e2b-it.litertlm"
    private const val FALLBACK_MODEL_SIZE_BYTES = 2_000_000_000L
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    fun getModelPath(context: Context): String = File(context.filesDir, MODEL_FILENAME).absolutePath

    fun isModelDownloaded(context: Context): Boolean {
        val file = File(context.filesDir, MODEL_FILENAME)
        return file.exists() && file.length() > 500_000_000L
    }

    fun getModelSizeMB(context: Context): Long {
        val file = File(context.filesDir, MODEL_FILENAME)
        return if (file.exists()) file.length() / (1024 * 1024) else 0
    }

    fun getPartialDownloadSizeMB(context: Context): Long {
        val file = File(context.filesDir, "$MODEL_FILENAME.tmp")
        return if (file.exists()) file.length() / (1024 * 1024) else 0
    }

    /**
     * Downloads Gemma to a .tmp file. If a previous download was interrupted,
     * the remaining bytes are requested with HTTP Range and appended to it.
     */
    suspend fun downloadModel(
        context: Context,
        onProgress: (downloaded: Long, total: Long, percent: Int) -> Unit
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val outputFile = File(context.filesDir, MODEL_FILENAME)
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")
        var connection: HttpURLConnection? = null

        try {
            val existingBytes = tempFile.takeIf { it.exists() }?.length() ?: 0L
            connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ClavisTranslator/1.0")
                if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
                ApiKeyStore.get("huggingface").takeIf { it.isNotBlank() }?.let { token ->
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode == 416) {
                val expectedSize = connection.getHeaderField("Content-Range")
                    ?.substringAfter("*/")?.toLongOrNull()
                if (existingBytes > 0L && expectedSize == existingBytes && tempFile.renameTo(outputFile)) {
                    onProgress(existingBytes, existingBytes, 100)
                    return@withContext Result.success(true)
                }
                return@withContext Result.failure(Exception("Saved download cannot be resumed. Delete the partial model and try again."))
            }
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                return@withContext Result.failure(Exception("Download failed: HTTP $responseCode. Progress was kept for retry."))
            }

            // A 206 response confirms the server honored Range. If it returned
            // 200 instead, it ignored Range, so safely restart the partial file.
            val append = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            val startBytes = if (append) existingBytes else 0L
            if (!append && tempFile.exists()) tempFile.delete()

            val responseLength = connection.contentLengthLong
            val totalBytes = when {
                responseLength > 0L -> startBytes + responseLength
                else -> maxOf(FALLBACK_MODEL_SIZE_BYTES, startBytes)
            }
            var downloadedBytes = startBytes
            onProgress(downloadedBytes, totalBytes, ((downloadedBytes * 100) / totalBytes).toInt().coerceAtMost(99))

            FileOutputStream(tempFile, append).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceAtMost(99)
                        onProgress(downloadedBytes, totalBytes, percent)
                    }
                }
            }

            if (outputFile.exists() && !outputFile.delete()) {
                return@withContext Result.failure(Exception("Could not replace the existing model."))
            }
            if (!tempFile.renameTo(outputFile)) {
                return@withContext Result.failure(Exception("Download finished but could not finalize it. Retry to resume."))
            }
            onProgress(downloadedBytes, downloadedBytes, 100)
            Result.success(true)
        } catch (e: Exception) {
            // Keep the .tmp file: pressing Resume continues from this byte offset.
            Result.failure(Exception("${e.message ?: "Download interrupted"}. Progress was kept; tap Resume to continue.", e))
        } finally {
            connection?.disconnect()
        }
    }

    fun deletePartialDownload(context: Context) {
        File(context.filesDir, "$MODEL_FILENAME.tmp").delete()
    }

    /** Explicitly removes both the completed model and any resumable partial download. */
    fun deleteModel(context: Context) {
        File(context.filesDir, MODEL_FILENAME).delete()
        deletePartialDownload(context)
    }
}
