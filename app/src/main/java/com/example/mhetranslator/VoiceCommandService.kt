package com.example.mhetranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.Locale

class VoiceCommandService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "ClavisVoice"
        private const val CHANNEL_ID = "clavis_voice_channel"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_LANG_CHANGED = "com.example.mhetranslator.LANG_CHANGED"

        var isRunning = false
            private set

        // Wake words
        private val WAKE_WORDS = listOf("clavis", "hey clavis", "wake up clavis", "ok clavis")
        private val STOP_WORDS = listOf("stop clavis", "bye clavis", "close clavis", "sleep clavis")
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var responseBubble: View? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var engine: Engine? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // States
    private var isAwake = false        // Listening for commands (after wake word)
    private var isListening = false    // SpeechRecognizer active
    private var isProcessing = false
    private var continuousListenJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        tts = TextToSpeech(this, this)
        initSpeechRecognizer()
        showFloatingButton()
        serviceScope.launch(Dispatchers.IO) { initEngine() }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        continuousListenJob?.cancel()
        removeFloatingButton()
        removeResponseBubble()
        speechRecognizer?.destroy()
        tts?.stop(); tts?.shutdown()
        engine?.close()
        serviceScope.cancel()
    }

    // ── TTS ──────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("en", "IN"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
                tts?.setLanguage(Locale.US)
            ttsReady = true
            tts?.setSpeechRate(1.05f)
            speak("Clavis is ready. Say Hey Clavis to wake me up.") {
                startContinuousListening()
            }
        }
    }

    private fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ttsReady) { showToast(text); onDone?.invoke(); return }
        showResponseBubble(text)
        val id = "u_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uid: String?) {}
            override fun onDone(uid: String?) { handler.post { onDone?.invoke() } }
            @Deprecated("Deprecated") override fun onError(uid: String?) { handler.post { onDone?.invoke() } }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    // ── Engine ───────────────────────────────────────────────────

    private fun initEngine() {
        if (!GemmaModelManager.isModelDownloaded(this)) return
        try {
            val p = GemmaModelManager.getModelPath(this)
            val eng = Engine(EngineConfig(modelPath = p))
            eng.initialize(); engine = eng
        } catch (_: Exception) {}
    }

    // ── Notification ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Clavis Voice", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clavis Assistant")
            .setContentText("Say 'Hey Clavis' to activate")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()

    // ── Continuous Listening (Wake Word) ─────────────────────────

    private fun startContinuousListening() {
        continuousListenJob?.cancel()
        continuousListenJob = serviceScope.launch {
            while (isActive && isRunning) {
                if (!isProcessing && !isListening) {
                    startListeningOnce()
                }
                delay(500)
            }
        }
    }

    private fun startListeningOnce() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return
        if (isListening) return
        // Don't listen while TTS is speaking
        if (tts?.isSpeaking == true) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            speechRecognizer?.startListening(intent)
            isListening = true
        } catch (_: Exception) {}
    }

    // ── Speech Recognizer ───────────────────────────────────────

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(buf: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}

            override fun onError(error: Int) {
                isListening = false
                // Silently restart — user doesn't need to know about timeouts
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                val lower = text.lowercase().trim()
                Log.d(TAG, "Heard: $lower (awake=$isAwake)")

                // Check for stop words first
                if (STOP_WORDS.any { lower.contains(it) }) {
                    isAwake = false
                    updateMicState()
                    speak("Going to sleep. Say Hey Clavis to wake me up.")
                    return
                }

                // Check for wake word
                if (!isAwake) {
                    if (WAKE_WORDS.any { lower.contains(it) }) {
                        isAwake = true
                        updateMicState()
                        // Extract command after wake word, if any
                        var command = lower
                        for (w in WAKE_WORDS) { command = command.replace(w, "").trim() }
                        if (command.length > 2) {
                            processCommand(command)
                        } else {
                            speak("Yes, I'm here! What do you need?")
                        }
                    }
                    // Not awake and no wake word → ignore
                    return
                }

                // Awake — process as command
                processCommand(text)
            }
        })
    }

    // ── Command Processing ──────────────────────────────────────

    private fun processCommand(spokenText: String) {
        isProcessing = true
        updateMicState()
        showResponseBubble("🎤 \"$spokenText\"\n\nProcessing...")

        val eng = engine
        if (eng == null) {
            keywordFallback(spokenText)
            return
        }

        serviceScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    askGemma(eng, spokenText)
                }
                handleResponse(response.trim(), spokenText)
            } catch (e: Exception) {
                keywordFallback(spokenText)
            }
        }
    }

    private suspend fun askGemma(eng: Engine, userText: String): String {
        val prompt = """You are Clavis, a voice assistant in a translation app.

COMMANDS (output ONLY the command tag, nothing else):
LIVE_TRANSLATE - translate screen text
STOP_TRANSLATE - stop translation
CROP_TRANSLATE - crop area to translate
CHANGE_LANG:Hindi - switch to Hindi
CHANGE_LANG:Marathi - switch to Marathi
DICTIONARY:word - look up word meaning
OPEN_SETTINGS - open settings

For general questions, start with ANSWER: and give a 1-2 sentence reply.
If user mentions language + translate, output CHANGE_LANG then LIVE_TRANSLATE.

User: "$userText"
Response:"""
        val sb = StringBuilder()
        eng.createConversation().use { c ->
            c.sendMessageAsync(prompt).collect { sb.append(it) }
        }
        return sb.toString().trim()
    }

    private fun handleResponse(response: String, original: String) {
        isProcessing = false

        when {
            response.startsWith("ANSWER:") -> {
                speak(response.removePrefix("ANSWER:").trim())
            }
            response.contains("CHANGE_LANG") || response.contains("TRANSLATE") ||
            response.contains("DICTIONARY") || response.contains("SETTINGS") -> {
                executeCommands(response)
            }
            response.length > 5 -> speak(response)
            else -> speak("I didn't understand. Try saying translate screen or ask me something.")
        }
        updateMicState()
    }

    // ── Command Execution with Feedback ─────────────────────────

    private fun executeCommands(block: String) {
        val cmds = block.lines().map { it.trim() }.filter { it.isNotBlank() && it != "UNKNOWN" }

        for (cmd in cmds) {
            when {
                cmd == "LIVE_TRANSLATE" -> {
                    speak("Translating your screen now. Please wait.") {
                        ClavisAccessibilityService.startTranslation()
                    }
                }
                cmd == "STOP_TRANSLATE" -> {
                    speak("Stopping translation.") {
                        ClavisAccessibilityService.stopTranslation()
                    }
                }
                cmd == "CROP_TRANSLATE" -> {
                    speak("Opening the crop tool. Select the area to translate.") {
                        startActivity(Intent(this, CapturePermissionActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    }
                }
                cmd.startsWith("CHANGE_LANG:") -> {
                    val lang = cmd.removePrefix("CHANGE_LANG:")
                    if (lang == "Hindi" || lang == "Marathi") {
                        speak("I'm changing the language to $lang. Please wait.") {
                            getSharedPreferences("mhe_prefs", MODE_PRIVATE)
                                .edit().putString("selected_language", lang).apply()
                            // Broadcast so MainActivity can update UI
                            sendBroadcast(Intent(ACTION_LANG_CHANGED).apply {
                                putExtra("language", lang)
                                setPackage(packageName)
                            })
                            speak("Done! Language is now set to $lang.")
                        }
                    }
                }
                cmd.startsWith("DICTIONARY:") -> {
                    val word = cmd.removePrefix("DICTIONARY:")
                    speak("Looking up the meaning of $word.") {
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            putExtra("dictionary_word", word)
                        })
                    }
                }
                cmd == "OPEN_SETTINGS" -> {
                    speak("Opening settings.") {
                        startActivity(Intent(this, ModelDownloadActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    }
                }
            }
        }
    }

    private fun keywordFallback(text: String) {
        isProcessing = false
        updateMicState()
        val l = text.lowercase()
        when {
            l.contains("hello") || l.contains("hi") || l.contains("hey") ->
                speak("Hello! I'm Clavis. What would you like me to do?")
            l.contains("how are you") ->
                speak("I'm great! Ready to help you translate.")
            l.contains("thank") ->
                speak("You're welcome! Say Hey Clavis anytime.")
            l.contains("what can you do") || l.contains("help") ->
                speak("I can translate your screen, crop and translate areas, look up word meanings, and switch languages. Just tell me!")
            l.contains("stop") -> { speak("Stopping translation."); executeCommands("STOP_TRANSLATE") }
            l.contains("marathi") -> executeCommands("CHANGE_LANG:Marathi\nLIVE_TRANSLATE")
            l.contains("hindi") -> executeCommands("CHANGE_LANG:Hindi\nLIVE_TRANSLATE")
            l.contains("crop") || l.contains("select") -> executeCommands("CROP_TRANSLATE")
            l.contains("setting") -> executeCommands("OPEN_SETTINGS")
            l.contains("translate") || l.contains("screen") -> executeCommands("LIVE_TRANSLATE")
            else -> speak("I didn't catch that. Try saying translate screen or switch to Hindi.")
        }
    }

    // ── Floating Button ─────────────────────────────────────────

    private fun showFloatingButton() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val size = (56 * d).toInt()
        val container = FrameLayout(this)
        val mic = TextView(this).apply { setText("🎤"); textSize = 22f; gravity = Gravity.CENTER }
        val dot = View(this).apply { visibility = View.GONE }
        container.addView(mic, FrameLayout.LayoutParams(size, size))
        container.addView(dot, FrameLayout.LayoutParams((10 * d).toInt(), (10 * d).toInt()).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, (2 * d).toInt(), (2 * d).toInt(), 0)
        })
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(android.graphics.Color.parseColor("#E6111111"))
            setStroke((2 * d).toInt(), android.graphics.Color.parseColor("#00E5FF"))
        }
        container.background = bg

        val p = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        p.x = (16 * d).toInt()

        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var drag = false
        container.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = p.x; iy = p.y; tx = e.rawX; ty = e.rawY; drag = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - tx) > 10 || Math.abs(e.rawY - ty) > 10) {
                        drag = true; p.x = ix - (e.rawX - tx).toInt(); p.y = iy + (e.rawY - ty).toInt()
                        windowManager?.updateViewLayout(container, p)
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!drag && !isProcessing) {
                        if (isAwake) {
                            isAwake = false; updateMicState()
                            speak("Going to sleep. Say Hey Clavis to wake me.")
                        } else {
                            isAwake = true; updateMicState()
                            speak("Yes? How can I help?")
                        }
                    }; true
                }
                else -> false
            }
        }
        try { windowManager?.addView(container, p); floatingView = container } catch (_: Exception) {}
    }

    private fun updateMicState() {
        val c = floatingView as? FrameLayout ?: return
        val mic = c.getChildAt(0) as? TextView ?: return
        val dot = c.getChildAt(1) ?: return
        val bg = c.background as? android.graphics.drawable.GradientDrawable ?: return
        val d = resources.displayMetrics.density
        val sw = (2 * d).toInt()
        when {
            isProcessing -> {
                mic.text = "✦"; dot.visibility = View.VISIBLE
                dot.setBackgroundColor(android.graphics.Color.parseColor("#8B5CF6"))
                bg.setStroke(sw, android.graphics.Color.parseColor("#8B5CF6"))
            }
            isAwake -> {
                mic.text = "🟢"; dot.visibility = View.VISIBLE
                dot.setBackgroundColor(android.graphics.Color.parseColor("#10B981"))
                bg.setStroke(sw, android.graphics.Color.parseColor("#10B981"))
            }
            else -> {
                mic.text = "🎤"; dot.visibility = View.GONE
                bg.setStroke(sw, android.graphics.Color.parseColor("#00E5FF"))
            }
        }
    }

    private fun removeFloatingButton() {
        floatingView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        floatingView = null
    }

    // ── Response Bubble ─────────────────────────────────────────

    private fun showResponseBubble(text: String) {
        removeResponseBubble()
        val d = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(android.graphics.Color.parseColor("#E8111111"))
                setStroke((1 * d).toInt(), android.graphics.Color.parseColor("#333333"))
            }
        }
        val label = TextView(this).apply {
            setText("✦ Clavis"); setTextColor(android.graphics.Color.parseColor("#00E5FF")); textSize = 11f
        }
        container.addView(label)
        val tv = TextView(this).apply {
            setText(text); setTextColor(android.graphics.Color.parseColor("#F0F0F0"))
            textSize = 13f; maxLines = 6; setPadding(0, (3 * d).toInt(), 0, 0)
        }
        container.addView(tv)

        val params = WindowManager.LayoutParams(
            (260 * d).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        params.x = (80 * d).toInt()

        try {
            windowManager?.addView(container, params); responseBubble = container
            handler.postDelayed({ removeResponseBubble() }, 7000)
        } catch (_: Exception) {}
    }

    private fun removeResponseBubble() {
        responseBubble?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        responseBubble = null
    }

    private fun showToast(m: String) { handler.post { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() } }
}
