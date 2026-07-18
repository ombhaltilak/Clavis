package com.example.mhetranslator

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SettingsBackground = Color(0xFF0D1117)
private val SettingsSurface = Color(0xFF161B22)
private val SettingsCard = Color(0xFF1C2333)
private val SettingsMuted = Color(0xFF8B949E)
private val SettingsCyan = Color(0xFF22D3EE)
private val SettingsViolet = Color(0xFF8B5CF6)
private val SettingsGreen = Color(0xFF10B981)

class ApiSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiKeyStore.initialize(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ApiSettingsScreen(
                    initialGemini = ApiKeyStore.get("gemini"),
                    initialHuggingFace = ApiKeyStore.get("huggingface"),
                    onSave = { gemini, huggingFace ->
                        if (ApiKeyStore.save(gemini, huggingFace, "")) {
                            Toast.makeText(this, "Provider keys saved", Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            Toast.makeText(this, "Could not save keys", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onManageOffline = {
                        startActivity(Intent(this@ApiSettingsActivity, ModelDownloadActivity::class.java))
                    }
                )
            }
        }
    }
}

@Composable
private fun ApiSettingsScreen(
    initialGemini: String,
    initialHuggingFace: String,
    onSave: (String, String) -> Unit,
    onManageOffline: () -> Unit
) {
    var gemini by remember { mutableStateOf(initialGemini) }
    var huggingFace by remember { mutableStateOf(initialHuggingFace) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SettingsBackground, SettingsSurface)))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("✦  CLAVIS", color = SettingsCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("Translation settings", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Choose a provider for online translation, or download Gemma for the optional local rewrite.", color = SettingsMuted, fontSize = 14.sp, lineHeight = 20.sp)

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SettingsCard),
            modifier = Modifier.fillMaxWidth().border(1.dp, SettingsCyan.copy(alpha = 0.24f), RoundedCornerShape(18.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("HOW TRANSLATION WORKS", color = SettingsCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("OCR → ML Kit English → Gemini or Qwen → Hinglish / Marathlish", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("The Offline mode can use ML Kit without a cloud key. Gemma is optional and needs a large one-time model download.", color = SettingsMuted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }

        Text("ONLINE PROVIDERS", color = SettingsMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        ProviderKeyCard(
            title = "✦  Gemini",
            detail = "Fast online translation. Qwen can act as a fallback when its token is configured.",
            label = "Gemini API key",
            value = gemini,
            accent = SettingsViolet,
            onValueChange = { gemini = it }
        )
        ProviderKeyCard(
            title = "Q  Qwen via Hugging Face",
            detail = "Use a Hugging Face token that has Inference Providers permission.",
            label = "Hugging Face token",
            value = huggingFace,
            accent = SettingsCyan,
            onValueChange = { huggingFace = it }
        )

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SettingsGreen.copy(alpha = 0.12f)),
            modifier = Modifier.fillMaxWidth().border(1.dp, SettingsGreen.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
        ) {
            Text("Tip: a saved token only proves it is stored. If Qwen later shows HTTP 403, create or update the token in Hugging Face with Inference Providers permission.", color = Color(0xFFD1FAE5), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(14.dp))
        }

        Button(
            onClick = { onSave(gemini, huggingFace) },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SettingsCyan, contentColor = Color(0xFF06151A))
        ) {
            Text("Save provider keys", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        OutlinedButton(
            onClick = onManageOffline,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp, brush = Brush.linearGradient(listOf(SettingsViolet, SettingsCyan)))
        ) {
            Text("Manage offline Gemma model", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Text("Keys are stored only on this device. Android removes them if the app is uninstalled.", color = SettingsMuted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    }
}

@Composable
private fun ProviderKeyCard(
    title: String,
    detail: String,
    label: String,
    value: String,
    accent: Color,
    onValueChange: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SettingsCard),
        modifier = Modifier.fillMaxWidth().border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(18.dp))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = SettingsMuted, fontSize = 12.sp, lineHeight = 18.sp)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(label) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = SettingsMuted.copy(alpha = 0.45f),
                    focusedLabelColor = accent,
                    unfocusedLabelColor = SettingsMuted,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = accent
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}
