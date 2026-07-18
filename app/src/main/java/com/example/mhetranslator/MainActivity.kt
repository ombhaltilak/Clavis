package com.example.mhetranslator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue

// ── Premium Color Palette ──────────────────────────────────────────
private val DeepNavy = Color(0xFF0D1117)
private val DarkSurface = Color(0xFF161B22)
private val CardDark = Color(0xFF1C2333)
private val AccentViolet = Color(0xFF8B5CF6)
private val AccentIndigo = Color(0xFF6366F1)
private val AccentCyan = Color(0xFF22D3EE)
private val AccentEmerald = Color(0xFF10B981)
private val SoftWhite = Color(0xFFF0F0F5)
private val MutedGray = Color(0xFF8B949E)
private val SubtleGray = Color(0xFF30363D)
private val GlowViolet = Color(0x338B5CF6)

class MainActivity : ComponentActivity() {
    private val _modelStatus = mutableStateOf("checking")
    private val _modelError = mutableStateOf("")

    private fun initLocalModelAsync() {
        val provider = getSharedPreferences("mhe_prefs", MODE_PRIVATE)
            .getString("translation_provider", "gemini") ?: "gemini"
        val isReady = when (provider) {
            "offline" -> true // ML Kit fallback works while optional Gemma is being downloaded.
            "qwen" -> HuggingFaceApi.isConfigured
            else -> GeminiApi.isConfigured || HuggingFaceApi.isConfigured
        }
        _modelStatus.value = if (isReady) "ready" else "error"
        _modelError.value = if (isReady) "" else "Add the selected provider key in Settings."
    }

    override fun onResume() {
        super.onResume()
        initLocalModelAsync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // API keys are stored separately from UI preferences; initialise before checking a provider.
        ApiKeyStore.initialize(this)
        OfflineTranslationApi.initialize(this)

        // Online Gemini and offline ML Kit are selected in the main sheet.
        initLocalModelAsync()

        val activity = this

        // Capture text highlighted globally from other mobile apps
        val capturedSource = intent.getStringExtra("captured_source").orEmpty().trim()
        val dictionaryWord = intent.getStringExtra("dictionary_word").orEmpty().trim()
        val interceptedText = when {
            capturedSource.isNotBlank() -> capturedSource
            dictionaryWord.isNotBlank() -> dictionaryWord
            intent.action == Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
            else -> "No text selected yet. Open any app, highlight text, and tap 'Clavis Translate'!"
        }

        setContent {
            val modelStatus by remember { _modelStatus }
            val modelError by remember { _modelError }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0x99000000) // Semi-transparent scrim behind bottom sheet
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { finish() }, // Tap scrim to dismiss
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight()
                                .fillMaxHeight(0.85f) // Max 85% of screen, scrollable inside
                                .clickable(enabled = false) {}, // Prevent dismiss when tapping sheet
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                            color = DeepNavy,
                            tonalElevation = 12.dp,
                            shadowElevation = 24.dp
                        ) {
                            TranslatorSheet(
                                initialSourceText = interceptedText,
                                onTranslateRequested = { text, mode, provider, onResult ->
                                    runTranslation(text, mode, provider, onResult)
                                },
                                onDictionaryRequested = { word, mode, onResult ->
                                    runDictionaryLookup(word, mode, onResult)
                                },
                                onProviderChanged = { provider ->
                                    getSharedPreferences("mhe_prefs", MODE_PRIVATE).edit()
                                        .putString("translation_provider", provider).apply()
                                    initLocalModelAsync()
                                },
                                onTranslateScreen = {
                                    if (android.provider.Settings.canDrawOverlays(activity)) {
                                        startActivity(Intent(activity, CapturePermissionActivity::class.java))
                                    } else {
                                        startActivity(Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${activity.packageName}")
                                        ))
                                    }
                                },
                                onDismiss = { finish() },
                                onSettingsClicked = {
                                    startActivity(Intent(activity, ApiSettingsActivity::class.java))
                                },
                                isModelLoaded = modelStatus == "ready",
                                modelStatus = modelStatus,
                                modelError = modelError
                            )
                        }
                    }
                }
            }
        }
    }

    private fun runTranslation(text: String, mode: String, provider: String, onResult: (String) -> Unit) {
        val targetLanguage = if (mode == "hinglish") "Hindi" else "Marathi"

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (provider) { "offline" -> OfflineTranslationApi.translate(text, targetLanguage); else -> OnlineTranslationApi.translate(text, targetLanguage, provider) }
                }
                if (result.isNotBlank()) onResult(result)
                else onResult("Translation failed. Please try again.")
            } catch (e: Exception) {
                Log.e("Translation", "Translation failed: ${e.message}")
                onResult(if (provider == "offline") "Offline model download or translation failed. Connect once to download the language model." else "Online translation failed. Check the selected provider key and internet connection.")
            }
        }
    }

    private fun runDictionaryLookup(word: String, mode: String, onResult: (String) -> Unit) {
        val targetLanguage = if (mode == "hinglish") "Hindi" else "Marathi"

        lifecycleScope.launch {
            try {
                val prompt = "You are a dictionary assistant for Indian languages. " +
                    "For the word \"$word\", provide in $targetLanguage: " +
                    "1. Meaning in Devanagari script " +
                    "2. Simple explanation " +
                    "3. Example sentence " +
                    "Use a conversational mix of $targetLanguage and English words (like Hinglish/Marathlish). " +
                    "Be concise and natural."
                
                val result = withContext(Dispatchers.IO) {
                    GeminiApi.generate(prompt)
                }
                if (result.isNotBlank()) onResult(result)
                else onResult("Dictionary lookup failed.")
            } catch (e: Exception) {
                Log.e("Dictionary", "Gemini API failed: ${e.message}")
                onResult("Lookup failed. Check internet connection.")
            }
        }
    }
}

// ── Premium Bottom Sheet UI ────────────────────────────────────────
@Composable
fun TranslatorSheet(
    initialSourceText: String,
    onTranslateRequested: (String, String, String, (String) -> Unit) -> Unit,
    onDictionaryRequested: (String, String, (String) -> Unit) -> Unit,
    onProviderChanged: (String) -> Unit = {},
    onTranslateScreen: () -> Unit = {},
    onDismiss: () -> Unit,
    onSettingsClicked: () -> Unit = {},
    isModelLoaded: Boolean = false,
    modelStatus: String = "checking",
    modelError: String = ""
) {
    // Load persisted language preference
    val localCtx = androidx.compose.ui.platform.LocalContext.current
    val savedLang = remember {
        localCtx.getSharedPreferences("mhe_prefs", Context.MODE_PRIVATE)
            .getString("selected_language", "Marathi") ?: "Marathi"
    }
    var selectedMode by remember {
        mutableStateOf(if (savedLang == "Hindi") "hinglish" else "marathlish")
    }
    val savedProvider = remember {
        localCtx.getSharedPreferences("mhe_prefs", Context.MODE_PRIVATE)
            .getString("translation_provider", "gemini") ?: "gemini"
    }
    var provider by remember { mutableStateOf(if (savedProvider == "online") "gemini" else savedProvider) }
    var currentSourceText by remember {
        mutableStateOf(if (initialSourceText.startsWith("No text")) "" else initialSourceText)
    }
    var translatedOutput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    // Listen for voice-triggered language changes
    androidx.compose.runtime.DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                val lang = intent?.getStringExtra("language") ?: return
                selectedMode = if (lang == "Hindi") "hinglish" else "marathlish"
            }
        }
        val filter = android.content.IntentFilter(VoiceCommandService.ACTION_LANG_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            localCtx.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            localCtx.registerReceiver(receiver, filter)
        }
        onDispose { localCtx.unregisterReceiver(receiver) }
    }

    // Automatically fires translation when text changes (debounced), mode is toggled, or model finishes loading
    LaunchedEffect(selectedMode, currentSourceText, provider, isModelLoaded) {
        if (currentSourceText.isNotBlank() && isModelLoaded) {
            isLoading = true
            delay(800) // Debounce typing
            onTranslateRequested(currentSourceText, selectedMode, provider) { result ->
                translatedOutput = result
                isLoading = false
            }
        } else {
            translatedOutput = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DeepNavy, DarkSurface),
                    startY = 0f,
                    endY = 1200f
                )
            )
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ── Drag Handle ────────────────────────────────────────────
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MutedGray.copy(alpha = 0.5f))
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Header ─────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "✦ Clavis",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = SoftWhite
                )
                Text(
                    text = when (modelStatus) {
                        "loading" -> "⏳ Loading AI model..."
                        "ready" -> "✅ Translation ready"
                        "error" -> "❌ Model error: $modelError"
                        "not_downloaded" -> "⬇️ Tap 🧠 to download AI model"
                        else -> "AI-powered intelligent translation"
                    },
                    fontSize = 12.sp,
                    color = when (modelStatus) {
                        "ready" -> AccentEmerald
                        "error" -> Color(0xFFFF6090)
                        "loading" -> AccentCyan
                        else -> MutedGray
                    },
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Voice command mic button
                val voiceContext = androidx.compose.ui.platform.LocalContext.current
                val isVoiceActive = VoiceCommandService.isRunning

                // Permission launcher
                val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        // Permission granted → start service
                        val intent = android.content.Intent(voiceContext, VoiceCommandService::class.java)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            voiceContext.startForegroundService(intent)
                        } else {
                            voiceContext.startService(intent)
                        }
                    } else {
                        android.widget.Toast.makeText(
                            voiceContext,
                            "Microphone permission is needed for voice commands",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (isVoiceActive) Color(0xFF1A2A3A) else Color(0xFF2A1A2A)
                        )
                        .clickable {
                            if (isVoiceActive) {
                                voiceContext.stopService(
                                    android.content.Intent(voiceContext, VoiceCommandService::class.java)
                                )
                            } else {
                                // Check if we already have mic permission
                                val hasMicPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                                    voiceContext, android.Manifest.permission.RECORD_AUDIO
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                                if (hasMicPerm) {
                                    // Also check overlay permission
                                    if (!android.provider.Settings.canDrawOverlays(voiceContext)) {
                                        val overlayIntent = android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${voiceContext.packageName}")
                                        )
                                        overlayIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                        voiceContext.startActivity(overlayIntent)
                                    } else {
                                        val intent = android.content.Intent(voiceContext, VoiceCommandService::class.java)
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                            voiceContext.startForegroundService(intent)
                                        } else {
                                            voiceContext.startService(intent)
                                        }
                                    }
                                } else {
                                    // Request mic permission
                                    micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isVoiceActive) "🔴" else "🎤",
                        fontSize = 14.sp
                    )
                }

                // Settings / Model button
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (isModelLoaded) Color(0xFF1A3A2A) else Color(0xFF3A1A1A)
                        )
                        .clickable { onSettingsClicked() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isModelLoaded) "🧠" else "⬇️",
                        fontSize = 14.sp
                    )
                }

                // Close button
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(SubtleGray)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✕",
                        color = MutedGray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // ── Language Toggle Chips ──────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardDark)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LanguageChip(
                label = "मराठी  Marathi",
                isSelected = selectedMode == "marathlish",
                onClick = { selectedMode = "marathlish" },
                selectedGradient = listOf(AccentViolet, AccentIndigo),
                modifier = Modifier.weight(1f)
            )
            LanguageChip(
                label = "हिंदी  Hindi",
                isSelected = selectedMode == "hinglish",
                onClick = { selectedMode = "hinglish" },
                selectedGradient = listOf(AccentCyan, AccentEmerald),
                modifier = Modifier.weight(1f)
            )
        }

        Text("TRANSLATION PROVIDER", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MutedGray, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 4.dp))
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderOption("✦", "Gemini", "Online", provider == "gemini", AccentViolet, Modifier.weight(1f)) { provider = "gemini"; onProviderChanged("gemini") }
            ProviderOption("Q", "Qwen", "Online", provider == "qwen", AccentCyan, Modifier.weight(1f)) { provider = "qwen"; onProviderChanged("qwen") }
            ProviderOption("◌", "Offline", "On device", provider == "offline", AccentEmerald, Modifier.weight(1f)) { provider = "offline"; onProviderChanged("offline") }
        }
        Text(
            text = when (provider) {
                "gemini" -> "Gemini: best quality and automatic Qwen fallback"
                "qwen" -> "Qwen: uses your Hugging Face Inference Provider token"
                else -> "Offline: ML Kit first, then optional local Gemma rewrite"
            },
            color = MutedGray,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))

        // ── Screen Translate Card (Crop-based) ─────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onTranslateScreen() },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(AccentCyan.copy(alpha = 0.25f), AccentViolet.copy(alpha = 0.25f))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📸", fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Crop & Translate",
                            color = SoftWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            "Select area from any app or image",
                            color = MutedGray,
                            fontSize = 11.sp
                        )
                    }
                }
                Text("→", color = AccentCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Live Translate Card (Accessibility-based) ───────────────
        val a11yActive = ClavisAccessibilityService.instance != null
        val isLiveTranslating = ClavisAccessibilityService.isTranslating
        val localContext = androidx.compose.ui.platform.LocalContext.current

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (!a11yActive) {
                        // Open accessibility settings so user can enable the service
                        val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        localContext.startActivity(intent)
                    } else if (isLiveTranslating) {
                        ClavisAccessibilityService.stopTranslation()
                    } else {
                        ClavisAccessibilityService.startTranslation()
                    }
                },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isLiveTranslating)
                    Color(0xFF1A3A2A) else CardDark
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isLiveTranslating)
                                    Brush.linearGradient(listOf(AccentEmerald.copy(alpha = 0.4f), AccentCyan.copy(alpha = 0.4f)))
                                else
                                    Brush.linearGradient(listOf(AccentViolet.copy(alpha = 0.25f), Color(0xFFFF6090).copy(alpha = 0.25f)))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isLiveTranslating) "✦" else "🌐", fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (!a11yActive) "Live Translate (Setup)"
                            else if (isLiveTranslating) "Live Translating..."
                            else "Live Translate Screen",
                            color = SoftWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            if (!a11yActive) "Tap to enable in Accessibility Settings"
                            else if (isLiveTranslating) "Text replaced in-place · Tap to stop"
                            else "Replace all text on screen like Google",
                            color = if (isLiveTranslating) AccentEmerald else MutedGray,
                            fontSize = 11.sp
                        )
                    }
                }
                Text(
                    if (isLiveTranslating) "■" else "→",
                    color = if (isLiveTranslating) AccentEmerald else AccentViolet,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Source Text Card ───────────────────────────────────────
        Text(
            text = "SOURCE",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MutedGray,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SubtleGray, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            OutlinedTextField(
                value = currentSourceText,
                onValueChange = { currentSourceText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = SoftWhite.copy(alpha = 0.9f),
                    lineHeight = 22.sp
                ),
                placeholder = { Text("Paste text here to translate...", color = MutedGray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = SoftWhite,
                    unfocusedTextColor = SoftWhite,
                    cursorColor = AccentCyan
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Translated Output Card ─────────────────────────────────
        Text(
            text = "TRANSLATION",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selectedMode == "marathlish") AccentViolet else AccentCyan,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp)
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = if (selectedMode == "marathlish")
                            listOf(AccentViolet.copy(alpha = 0.4f), AccentIndigo.copy(alpha = 0.1f))
                        else
                            listOf(AccentCyan.copy(alpha = 0.4f), AccentEmerald.copy(alpha = 0.1f)),
                        start = Offset(0f, 0f),
                        end = Offset(600f, 600f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp)
                    .padding(16.dp)
            ) {
                if (isLoading) {
                    // Animated shimmer loading state
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800, easing = EaseInOut),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "Clavis Translate"
                        )

                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = if (selectedMode == "marathlish") AccentViolet else AccentCyan,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Translating with Clavis AI…",
                            fontSize = 13.sp,
                            color = MutedGray.copy(alpha = alpha),
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    SelectionContainer {
                        Text(
                            text = translatedOutput.ifEmpty { "Select text in any app and tap 'Clavis Translate' to begin." },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (translatedOutput.isEmpty()) MutedGray else SoftWhite,
                            lineHeight = 26.sp,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }

        // ── Dictionary Section ─────────────────────────────────────
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "DICTIONARY",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MutedGray,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        )

        var dictionaryWord by remember { mutableStateOf("") }
        var dictionaryMeaning by remember { mutableStateOf("") }
        var isDictLoading by remember { mutableStateOf(false) }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = dictionaryWord,
                onValueChange = { dictionaryWord = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Paste word here...", color = MutedGray) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (selectedMode == "marathlish") AccentViolet else AccentCyan,
                    unfocusedBorderColor = SubtleGray,
                    focusedTextColor = SoftWhite,
                    unfocusedTextColor = SoftWhite,
                    cursorColor = SoftWhite
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (dictionaryWord.isNotBlank()) {
                        isDictLoading = true
                        dictionaryMeaning = ""
                        onDictionaryRequested(dictionaryWord, selectedMode) { result ->
                            dictionaryMeaning = result
                            isDictLoading = false
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (selectedMode == "marathlish") AccentViolet else AccentCyan),
                modifier = Modifier.height(56.dp)
            ) {
                Text("Search", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        if (isDictLoading || dictionaryMeaning.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth().border(1.dp, SubtleGray, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = CardDark)
            ) {
                Box(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    if (isDictLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).align(Alignment.Center),
                            color = if (selectedMode == "marathlish") AccentViolet else AccentCyan,
                            strokeWidth = 2.dp
                        )
                    } else {
                        SelectionContainer {
                            Text(text = dictionaryMeaning, style = MaterialTheme.typography.bodyMedium, color = SoftWhite, lineHeight = 22.sp)
                        }
                    }
                }
            }
        }

// ── Bottom Branding ────────────────────────────────────────
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Powered by Clavis AI",
            fontSize = 11.sp,
            color = MutedGray.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            textAlign = TextAlign.Center
        )
    }
}


@Composable
private fun ProviderOption(
    icon: String,
    label: String,
    caption: String,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else CardDark)
            .border(1.dp, if (selected) accent.copy(alpha = 0.8f) else SubtleGray, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("$icon  $label", color = if (selected) SoftWhite else MutedGray, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(caption, color = if (selected) accent else MutedGray, fontSize = 10.sp)
    }
}

// ── Stylish Language Toggle Chip ───────────────────────────────────
@Composable
fun LanguageChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    selectedGradient: List<Color>,
    modifier: Modifier = Modifier
) {
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(300),
        label = "chipAlpha"
    )

    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (isSelected) {
                    Modifier.background(
                        Brush.horizontalGradient(selectedGradient)
                    )
                } else {
                    Modifier.background(Color.Transparent)
                }
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) Color.White else MutedGray
        )
    }
}