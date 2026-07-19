package com.example.mhetranslator

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ModelDownloadActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isAlreadyDownloaded = GemmaModelManager.isModelDownloaded(this)
        val modelSizeMB = GemmaModelManager.getModelSizeMB(this)
        val partialDownloadSizeMB = GemmaModelManager.getPartialDownloadSizeMB(this)

        val prefs = getSharedPreferences("mhe_prefs", MODE_PRIVATE)
        var selectedLanguage = prefs.getString("selected_language", "Hindi") ?: "Hindi"

        setContent {
            var currentLanguage by remember { mutableStateOf(selectedLanguage) }

            ModelDownloadScreen(
                isAlreadyDownloaded = isAlreadyDownloaded,
                modelSizeMB = modelSizeMB,
                partialDownloadSizeMB = partialDownloadSizeMB,
                currentLanguage = currentLanguage,
                onLanguageChanged = { lang ->
                    currentLanguage = lang
                    prefs.edit().putString("selected_language", lang).apply()
                },
                onStartDownload = { onProgress, onComplete, onError ->
                    lifecycleScope.launch {
                        val result = GemmaModelManager.downloadModel(
                            this@ModelDownloadActivity
                        ) { downloaded, total, percent ->
                            onProgress(downloaded, total, percent)
                        }
                        result.fold(
                            onSuccess = { onComplete() },
                            onFailure = { e -> onError(e.message ?: "Unknown error") }
                        )
                    }
                },
                onStartFromScratch = { onProgress, onComplete, onError ->
                    GemmaModelManager.deletePartialDownload(this@ModelDownloadActivity)
                    lifecycleScope.launch {
                        val result = GemmaModelManager.downloadModel(this@ModelDownloadActivity) { downloaded, total, percent ->
                            onProgress(downloaded, total, percent)
                        }
                        result.fold(
                            onSuccess = { onComplete() },
                            onFailure = { error -> onError(error.message ?: "Unknown error") }
                        )
                    }
                },
                onDownloadComplete = {
                    setResult(RESULT_OK)
                    finish()
                },
                onDeleteModel = {
                    GemmaModelManager.deleteModel(this)
                    Toast.makeText(this, "AI model deleted.", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                },
                onSkip = {
                    Toast.makeText(this, "You can download the AI model later.", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_CANCELED)
                    finish()
                }
            )
        }
    }
}

@Composable
fun ModelDownloadScreen(
    isAlreadyDownloaded: Boolean = false,
    modelSizeMB: Long = 0,
    partialDownloadSizeMB: Long = 0,
    currentLanguage: String = "Hindi",
    onLanguageChanged: (String) -> Unit = {},
    onStartDownload: (
        onProgress: (Long, Long, Int) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit,
    onStartFromScratch: (
        onProgress: (Long, Long, Int) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit,
    onDownloadComplete: () -> Unit,
    onDeleteModel: () -> Unit = {},
    onSkip: () -> Unit
) {
    var downloadState by remember {
        mutableStateOf<DownloadState>(
            if (isAlreadyDownloaded) DownloadState.Installed else DownloadState.Ready
        )
    }
    var bytesDownloaded by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(0L) }
    var progressPercent by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf("") }

    val bgGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0A0A1A), Color(0xFF1A1A3E), Color(0xFF0A0A1A))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgGradient),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
        ) {
            // Animated icon
            val infiniteTransition = rememberInfiniteTransition(label = "rotate")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(3000, easing = LinearEasing)
                ),
                label = "rotation"
            )

            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.sweepGradient(
                            listOf(
                                Color(0xFF00E5FF),
                                Color(0xFF7C4DFF),
                                Color(0xFFFF6090),
                                Color(0xFF00E5FF)
                            )
                        )
                    )
                    .rotate(if (downloadState is DownloadState.Downloading) rotation else 0f),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0A0A1A)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (downloadState) {
                            is DownloadState.Ready -> "🧠"
                            is DownloadState.Downloading -> "⬇️"
                            is DownloadState.Complete -> "✅"
                            is DownloadState.Error -> "❌"
                            is DownloadState.Installed -> "✅"
                        },
                        fontSize = 36.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = when (downloadState) {
                    is DownloadState.Ready -> "Setup AI Translation"
                    is DownloadState.Downloading -> "Downloading Clavis AI..."
                    is DownloadState.Complete -> "Ready to Translate!"
                    is DownloadState.Error -> "Download paused"
                    is DownloadState.Installed -> "AI Model Settings"
                },
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            when (downloadState) {
                // ── READY STATE ─────────────────────────────────────
                is DownloadState.Ready -> {
                    Text(
                        text = if (partialDownloadSizeMB > 0) {
                            "A $partialDownloadSizeMB MB partial download was found. Tap Resume to continue from where it stopped."
                        } else {
                            "Download the Clavis AI model for on-device translation. This is a one-time download (~2.5 GB). After this, all translations work 100% offline!"
                        },
                        color = Color(0xFFB0B0C0),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Benefits
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            BenefitRow("⚡", "Fast — translates in ~1 second")
                            Spacer(modifier = Modifier.height(6.dp))
                            BenefitRow("🔒", "Private — never leaves your device")
                            Spacer(modifier = Modifier.height(6.dp))
                            BenefitRow("📶", "Offline — works without internet")
                            Spacer(modifier = Modifier.height(6.dp))
                            BenefitRow("💰", "Free — no API costs ever")
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Button(
                        onClick = {
                            downloadState = DownloadState.Downloading
                            onStartDownload(
                                { dl, total, pct ->
                                    bytesDownloaded = dl
                                    totalBytes = total
                                    progressPercent = pct
                                },
                                { downloadState = DownloadState.Complete },
                                { msg ->
                                    errorMessage = msg
                                    downloadState = DownloadState.Error
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text(
                            if (partialDownloadSizeMB > 0) "▶  Resume AI Model Download" else "⬇️  Download AI Model",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    if (partialDownloadSizeMB > 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                downloadState = DownloadState.Downloading
                                onStartFromScratch(
                                    { dl, total, pct ->
                                        bytesDownloaded = dl
                                        totalBytes = total
                                        progressPercent = pct
                                    },
                                    { downloadState = DownloadState.Complete },
                                    { message ->
                                        errorMessage = message
                                        downloadState = DownloadState.Error
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text("↺  Start from scratch", fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "Deletes the saved partial download and starts again from 0 MB.",
                            color = Color(0xFF707090),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(onClick = onSkip) {
                        Text(
                            "Skip for now (use online translation)",
                            color = Color(0xFF707090),
                            fontSize = 13.sp
                        )
                    }
                }

                // ── DOWNLOADING STATE ───────────────────────────────
                is DownloadState.Downloading -> {
                    Text(
                        text = "${bytesDownloaded / (1024 * 1024)} MB / ${totalBytes / (1024 * 1024)} MB",
                        color = Color(0xFFB0B0C0),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    LinearProgressIndicator(
                        progress = { progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = Color(0xFF00E5FF),
                        trackColor = Color(0xFF2A2A4A)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "$progressPercent%",
                        color = Color(0xFF00E5FF),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "Please keep the app open.\nIf interrupted, your download progress is saved for Resume.",
                        color = Color(0xFF707090),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }

                // ── COMPLETE STATE ──────────────────────────────────
                is DownloadState.Complete -> {
                    Text(
                        text = "Clavis AI is installed on your device! 🎉\nAll translations now work 100% offline.",
                        color = Color(0xFFB0B0C0),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = onDownloadComplete,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text(
                            "🚀  Start Translating!",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }

                // ── ERROR STATE ─────────────────────────────────────
                is DownloadState.Error -> {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFB0B0C0),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            downloadState = DownloadState.Downloading
                            onStartDownload(
                                { dl, total, pct ->
                                    bytesDownloaded = dl
                                    totalBytes = total
                                    progressPercent = pct
                                },
                                { downloadState = DownloadState.Complete },
                                { msg ->
                                    errorMessage = msg
                                    downloadState = DownloadState.Error
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF6090),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text("▶  Resume Download", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }

                    if (partialDownloadSizeMB > 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                downloadState = DownloadState.Downloading
                                onStartFromScratch(
                                    { dl, total, pct ->
                                        bytesDownloaded = dl
                                        totalBytes = total
                                        progressPercent = pct
                                    },
                                    { downloadState = DownloadState.Complete },
                                    { message ->
                                        errorMessage = message
                                        downloadState = DownloadState.Error
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text("↺  Start from scratch", fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "Deletes the saved partial download and starts again from 0 MB.",
                            color = Color(0xFF707090),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(onClick = onSkip) {
                        Text("Skip for now", color = Color(0xFF707090), fontSize = 13.sp)
                    }
                }

                // ── INSTALLED STATE (Model Management) ─────────────────
                is DownloadState.Installed -> {
                    Text(
                        text = "Clavis AI is installed on your device! 🎉\nAll translations work 100% offline.",
                        color = Color(0xFFB0B0C0),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            BenefitRow("📁", "Model size: ${modelSizeMB} MB")
                            Spacer(modifier = Modifier.height(6.dp))
                            BenefitRow("✅", "Status: Active & Ready")
                            Spacer(modifier = Modifier.height(6.dp))
                            BenefitRow("🔒", "All data stays on device")
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Button(
                        onClick = onDownloadComplete,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text(
                            "←  Back to Translator",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = onDeleteModel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFFF6090)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF6090)),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text(
                            "🗑  Delete AI Model",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}

// ── Helper Composables ────────────────────────────────────────────

@Composable
fun BenefitRow(emoji: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 18.sp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, color = Color(0xFFD0D0E0), fontSize = 14.sp)
    }
}

sealed class DownloadState {
    data object Ready : DownloadState()
    data object Downloading : DownloadState()
    data object Complete : DownloadState()
    data object Error : DownloadState()
    data object Installed : DownloadState()
}
