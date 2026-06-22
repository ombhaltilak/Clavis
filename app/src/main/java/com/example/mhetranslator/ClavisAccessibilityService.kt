package com.example.mhetranslator

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.Executor

/**
 * Accessibility Service that:
 * 1. Takes a screenshot (API 30+) to sample REAL background colors
 * 2. Reads all TextViews from the live accessibility tree with exact bounds
 * 3. Translates the text using on-device Gemma
 * 4. Overlays translated text at the EXACT same position with matched background
 *
 * Result: Text appears replaced in-place — images/icons/UI stay untouched.
 */
class ClavisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClavisA11y"

        var isTranslating = false
            private set
        var instance: ClavisAccessibilityService? = null
            private set

        fun startTranslation() {
            isTranslating = true
            instance?.performScreenTranslation()
        }

        fun stopTranslation() {
            isTranslating = false
            instance?.removeOverlay()
        }
    }

    private var engine: Engine? = null
    private var overlayView: ScreenTranslationOverlay? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
        serviceScope.launch(Dispatchers.IO) { initEngine() }
    }

    private fun initEngine() {
        if (!GemmaModelManager.isModelDownloaded(this)) return
        try {
            val modelPath = GemmaModelManager.getModelPath(this)
            val config = EngineConfig(modelPath = modelPath)
            val eng = Engine(config)
            eng.initialize()
            engine = eng
            Log.d(TAG, "AI engine initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Engine init failed: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { removeOverlay() }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isTranslating = false
        removeOverlay()
        engine?.close()
        serviceScope.cancel()
    }

    /**
     * Main flow:
     * 1. Screenshot → for background color sampling
     * 2. Accessibility tree → for text + exact bounds
     * 3. Translate
     * 4. Overlay with matched backgrounds
     */
    fun performScreenTranslation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: Use takeScreenshot for background sampling
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hwBitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer, screenshot.colorSpace
                        )
                        // Convert to software bitmap so we can read pixels
                        val bitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap?.recycle()
                        screenshot.hardwareBuffer.close()

                        if (bitmap != null) {
                            doTranslateWithBitmap(bitmap)
                        } else {
                            // Fallback: translate without bg sampling
                            doTranslateWithBitmap(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Screenshot failed: $errorCode, using fallback")
                        doTranslateWithBitmap(null)
                    }
                }
            )
        } else {
            // Pre-API 30: no screenshot, use heuristic bg colors
            doTranslateWithBitmap(null)
        }
    }

    private fun doTranslateWithBitmap(screenshot: Bitmap?) {
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "No root window")
            screenshot?.recycle()
            return
        }

        serviceScope.launch {
            try {
                // 1. Collect all text nodes
                val textNodes = mutableListOf<TextNodeInfo>()
                collectTextNodes(rootNode, textNodes)

                if (textNodes.isEmpty()) {
                    Log.w(TAG, "No text found")
                    screenshot?.recycle()
                    return@launch
                }

                Log.d(TAG, "Found ${textNodes.size} text nodes")

                // 2. Sample background colors from screenshot for each node
                if (screenshot != null) {
                    for (node in textNodes) {
                        node.sampledBgColor = sampleBackgroundColor(screenshot, node.bounds)
                    }
                }

                // 3. Translate all text (one line per node)
                val prefs = getSharedPreferences("mhe_prefs", MODE_PRIVATE)
                val targetLang = prefs.getString("selected_language", "Hindi") ?: "Hindi"

                // Build indexed text for translation
                val allText = textNodes.mapIndexed { i, n -> "${i + 1}. ${n.text}" }
                    .joinToString("\n")

                val translated = translateText(allText, targetLang, textNodes.size)
                    ?: run {
                        screenshot?.recycle()
                        return@launch
                    }

                // 4. Parse translated lines and map back
                val translatedLines = parseTranslatedLines(translated, textNodes.size)

                val overlayNodes = textNodes.mapIndexed { index, node ->
                    TranslatedTextNode(
                        bounds = node.bounds,
                        originalText = node.text,
                        translatedText = translatedLines.getOrElse(index) { node.text }.trim(),
                        textSizePx = node.textSizePx,
                        bgColor = node.sampledBgColor
                    )
                }

                screenshot?.recycle()

                // 5. Show overlay
                withContext(Dispatchers.Main) {
                    showOverlay(overlayNodes)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Translation error: ${e.message}")
                screenshot?.recycle()
            }
        }
    }

    /**
     * Sample the average background color from the screenshot bitmap
     * at the given node's screen bounds. This gives us the REAL bg color
     * of whatever app is showing underneath.
     */
    private fun sampleBackgroundColor(bitmap: Bitmap, bounds: Rect): Int {
        try {
            val bw = bitmap.width; val bh = bitmap.height
            val l = bounds.left.coerceIn(0, bw - 1)
            val t = bounds.top.coerceIn(0, bh - 1)
            val r = bounds.right.coerceIn(l + 1, bw)
            val b = bounds.bottom.coerceIn(t + 1, bh)

            // Sample edges of the rect (edges more likely to be bg, center has text)
            var rS = 0L; var gS = 0L; var bS = 0L; var count = 0

            // Sample top edge
            for (x in l until r step ((r - l) / 6).coerceAtLeast(1)) {
                val px = bitmap.getPixel(x.coerceIn(0, bw - 1), t.coerceIn(0, bh - 1))
                rS += android.graphics.Color.red(px)
                gS += android.graphics.Color.green(px)
                bS += android.graphics.Color.blue(px)
                count++
            }
            // Sample bottom edge
            val bEdge = (b - 1).coerceIn(0, bh - 1)
            for (x in l until r step ((r - l) / 6).coerceAtLeast(1)) {
                val px = bitmap.getPixel(x.coerceIn(0, bw - 1), bEdge)
                rS += android.graphics.Color.red(px)
                gS += android.graphics.Color.green(px)
                bS += android.graphics.Color.blue(px)
                count++
            }
            // Sample left edge
            for (y in t until b step ((b - t) / 4).coerceAtLeast(1)) {
                val px = bitmap.getPixel(l.coerceIn(0, bw - 1), y.coerceIn(0, bh - 1))
                rS += android.graphics.Color.red(px)
                gS += android.graphics.Color.green(px)
                bS += android.graphics.Color.blue(px)
                count++
            }

            if (count > 0) {
                return android.graphics.Color.argb(
                    255,
                    (rS / count).toInt().coerceIn(0, 255),
                    (gS / count).toInt().coerceIn(0, 255),
                    (bS / count).toInt().coerceIn(0, 255)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bg sample error: ${e.message}")
        }
        return android.graphics.Color.argb(255, 30, 30, 30) // fallback dark
    }

    /**
     * Walk accessibility tree, collect visible text nodes with bounds.
     */
    private fun collectTextNodes(node: AccessibilityNodeInfo, result: MutableList<TextNodeInfo>) {
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.width() > 20 && bounds.height() > 10 &&
                bounds.top >= 0 && bounds.left >= 0
            ) {
                // Estimate text size from node height & text line count
                val lineCount = text.lines().size.coerceAtLeast(1)
                val estimatedTextSize = (bounds.height().toFloat() / lineCount * 0.65f)
                    .coerceIn(20f, 72f)

                result.add(
                    TextNodeInfo(
                        text = text,
                        bounds = bounds,
                        textSizePx = estimatedTextSize
                    )
                )
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextNodes(child, result)
            child.recycle()
        }
    }

    /**
     * Translate text: one line per text node.
     */
    private suspend fun translateText(text: String, targetLanguage: String, nodeCount: Int): String? {
        val eng = engine ?: run {
            Log.w(TAG, "Engine not loaded")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val prompt = """Translate each numbered line to $targetLanguage.
Output exactly $nodeCount numbered translated lines.
Keep the numbering format "1. translated text".
Only output translations, no explanations.

$text"""

                val sb = StringBuilder()
                eng.createConversation().use { conversation ->
                    conversation.sendMessageAsync(prompt).collect { token ->
                        sb.append(token)
                    }
                }
                sb.toString().trim()
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Parse numbered translation output back into individual lines.
     */
    private fun parseTranslatedLines(raw: String, expectedCount: Int): List<String> {
        val lines = raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map {
                // Remove numbering like "1. ", "2. " etc
                it.replace(Regex("""^\d+\.\s*"""), "")
            }
        return lines
    }

    private fun showOverlay(nodes: List<TranslatedTextNode>) {
        removeOverlay()

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val overlay = ScreenTranslationOverlay(this)
        overlay.setTranslatedNodes(nodes)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        try {
            wm.addView(overlay, params)
            overlayView = overlay
            Log.d(TAG, "Overlay shown with ${nodes.size} blocks")
        } catch (e: Exception) {
            Log.e(TAG, "Overlay add failed: ${e.message}")
        }
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            } catch (_: Exception) {}
            overlayView = null
        }
    }
}

/**
 * Text node extracted from the accessibility tree.
 * sampledBgColor is filled later from the screenshot bitmap.
 */
data class TextNodeInfo(
    val text: String,
    val bounds: Rect,
    val textSizePx: Float,
    var sampledBgColor: Int = android.graphics.Color.argb(255, 30, 30, 30)
)

data class TranslatedTextNode(
    val bounds: Rect,
    val originalText: String,
    val translatedText: String,
    val textSizePx: Float,
    val bgColor: Int  // Sampled from real screenshot
)
