package com.example.mhetranslator

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.ai.edge.litertlm.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Production 5-Stage Translation Pipeline v2.
 *
 * Improvements over v1:
 * 1. Gradient-aware inpainting with dilated masks + edge feathering
 * 2. Keep-out padding to prevent icon/UI overlap
 * 3. Alpha & font-weight sampling for visual hierarchy preservation
 * 4. Indic-script-aware dynamic line-height and font sizing
 */
object TranslationPipeline {

    private const val TAG = "Pipeline"
    private const val MASK_DILATION_PX = 3      // Dilate mask to cover anti-aliased edges
    private const val FEATHER_PX = 4            // Edge feather radius - increased for smoother blending
    private const val INNER_PADDING_RATIO = 0.04f // 4% inner padding for keep-out (Google Assistant style)
    private const val MIN_FONT_SIZE = 14f
    private const val MAX_FONT_SIZE = 56f

    data class TextBlock(
        val text: String,
        val bounds: Rect,
        var textColor: Int,
        var textAlpha: Int = 255,       // Opacity of original text
        var bgColor: Int,
        var bgGradientTop: Int = 0,     // For gradient reconstruction
        var bgGradientBottom: Int = 0,
        var isBold: Boolean = false,    // Heading vs body
        var translatedText: String = ""
    )

    suspend fun process(
        originalBitmap: Bitmap,
        cropRect: androidx.compose.ui.geometry.Rect,
        engine: Engine?,
        targetLanguage: String,
        onStatus: (String) -> Unit
    ): Bitmap = withContext(Dispatchers.Default) {

        val workBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(workBitmap)

        val cl = cropRect.left.toInt().coerceIn(0, workBitmap.width - 1)
        val ct = cropRect.top.toInt().coerceIn(0, workBitmap.height - 1)
        val cr = cropRect.right.toInt().coerceIn(cl + 1, workBitmap.width)
        val cb = cropRect.bottom.toInt().coerceIn(ct + 1, workBitmap.height)

        // ── Stage 1: OCR on FULL BITMAP (not cropped) ────────────────
        onStatus("Stage 1/5: Detecting text in full image...")
        val textBlocks = runOCR(workBitmap)
        if (textBlocks.isEmpty()) {
            onStatus("No text detected")
            return@withContext workBitmap
        }
        
        // Filter blocks to only those within or overlapping the crop rectangle
        val cropRectF = android.graphics.Rect(cl, ct, cr, cb)
        val filteredBlocks = textBlocks.filter { block ->
            // Keep blocks that are within or overlap the crop area
            android.graphics.Rect.intersects(block.bounds, cropRectF) || cropRectF.contains(block.bounds)
        }
        
        if (filteredBlocks.isEmpty()) {
            onStatus("No text detected in selected area")
            return@withContext workBitmap
        }
        
        // Use filtered blocks instead of all blocks
        textBlocks.clear()
        textBlocks.addAll(filteredBlocks)

        // ── Stage 1b: Deep Color Analysis ───────────────────────────
        onStatus("Stage 1/5: Analyzing typography...")
        for (block in textBlocks) analyzeTypography(workBitmap, block, textBlocks)

        // ── Stage 2: Gradient-Aware Inpainting ─────────────────────
        onStatus("Stage 2/5: Healing background...")
        inpaintWithGradient(workBitmap, canvas, textBlocks)

        // ── Stage 3: Translate ──────────────────────────────────────
        onStatus("Stage 3/5: Translating...")
        translateBlocks(textBlocks, engine, targetLanguage)

        // ── Stage 4+5: Typography-Aware Rendering ──────────────────
        onStatus("Stage 4/5: Rendering text...")
        renderWithHierarchy(canvas, textBlocks)

        onStatus("")
        workBitmap
    }

    // ═══════════════════════════════════════════════════════════════
    // Stage 1: OCR
    // ═══════════════════════════════════════════════════════════════

    private suspend fun runOCR(bitmap: Bitmap): MutableList<TextBlock> =
        withContext(Dispatchers.IO) {
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val task = recognizer.process(image)
                com.google.android.gms.tasks.Tasks.await(task)
                val result = task.result ?: return@withContext mutableListOf()

                val blocks = mutableListOf<TextBlock>()
                for (tb in result.textBlocks) {
                    for (line in tb.lines) {
                        val box = line.boundingBox ?: continue
                        if (box.width() < 15 || box.height() < 8) continue
                        // Convert ML Kit Rect to android.graphics.Rect
                        val androidRect = android.graphics.Rect(
                            box.left, box.top, box.right, box.bottom
                        )
                        blocks.add(TextBlock(
                            text = line.text, bounds = androidRect,
                            textColor = Color.WHITE, bgColor = Color.BLACK
                        ))
                    }
                }
                blocks
            } catch (e: Exception) {
                Log.e(TAG, "OCR: ${e.message}"); mutableListOf()
            }
        }

    // ═══════════════════════════════════════════════════════════════
    // Stage 1b: Deep Typography Analysis
    // ═══════════════════════════════════════════════════════════════

    private fun analyzeTypography(bmp: Bitmap, block: TextBlock, allBlocks: List<TextBlock>) {
        val b = block.bounds
        val bw = bmp.width; val bh = bmp.height

        // ── Enhanced Background gradient sampling with larger area ──
        // Sample a larger area around the text block for better gradient reconstruction
        val sampleHeight = (b.height() * 0.3f).toInt().coerceAtLeast(5)
        block.bgGradientTop = sampleAreaColor(bmp, b.left, b.right, (b.top - sampleHeight).coerceIn(0, bh - 1), b.height() / 4, bw)
        block.bgGradientBottom = sampleAreaColor(bmp, b.left, b.right, (b.bottom).coerceIn(0, bh - 1), b.height() / 4, bw)
        block.bgColor = blendColors(block.bgGradientTop, block.bgGradientBottom, 0.5f)

        // ── Text color + alpha sampling with better accuracy ──
        // Sample multiple points along horizontal center of text, avoiding edges
        val cy = ((b.top + b.bottom) / 2).coerceIn(0, bh - 1)
        var rS = 0L; var gS = 0L; var bS = 0L; var aS = 0L; var n = 0
        val step = ((b.width()) / 15).coerceAtLeast(1)
        val startX = (b.left + b.width() * 0.2f).toInt()
        val endX = (b.left + b.width() * 0.8f).toInt()
        for (x in startX..endX step step) {
            val px = bmp.getPixel(x.coerceIn(0, bw - 1), cy)
            rS += Color.red(px); gS += Color.green(px); bS += Color.blue(px)
            aS += Color.alpha(px); n++
        }
        if (n > 0) {
            block.textColor = Color.rgb((rS / n).toInt(), (gS / n).toInt(), (bS / n).toInt())
            block.textAlpha = (aS / n).toInt().coerceIn(50, 255)
        }

        // ── Determine brightness contrast to detect heading vs body ──
        val textLum = luminance(block.textColor)
        val bgLum = luminance(block.bgColor)
        val contrast = abs(textLum - bgLum)

        // Bold detection: larger text blocks with high contrast are likely headings
        val avgHeight = allBlocks.map { it.bounds.height() }.average().toFloat()
        block.isBold = block.bounds.height() > avgHeight * 1.2f && contrast > 0.35f
    }

    private fun sampleAreaColor(bmp: Bitmap, left: Int, right: Int, centerY: Int, sampleHeight: Int, bw: Int): Int {
        var rS = 0L; var gS = 0L; var bS = 0L; var n = 0
        val halfHeight = sampleHeight / 2
        val startY = (centerY - halfHeight).coerceAtLeast(0)
        val endY = (centerY + halfHeight).coerceAtMost(bmp.height - 1)
        val stepY = ((endY - startY) / 3).coerceAtLeast(1)
        val stepX = ((right - left) / 10).coerceAtLeast(1)
        
        for (y in startY..endY step stepY) {
            for (x in left..right step stepX) {
                val px = bmp.getPixel(x.coerceIn(0, bw - 1), y)
                rS += Color.red(px); gS += Color.green(px); bS += Color.blue(px); n++
            }
        }
        return if (n > 0) Color.rgb((rS / n).toInt(), (gS / n).toInt(), (bS / n).toInt())
        else Color.rgb(30, 30, 30)
    }

    private fun sampleEdgeRow(bmp: Bitmap, left: Int, right: Int, y: Int, bw: Int): Int {
        var rS = 0L; var gS = 0L; var bS = 0L; var n = 0
        val step = ((right - left) / 10).coerceAtLeast(1)
        for (x in left..right step step) {
            val px = bmp.getPixel(x.coerceIn(0, bw - 1), y)
            rS += Color.red(px); gS += Color.green(px); bS += Color.blue(px); n++
        }
        return if (n > 0) Color.rgb((rS / n).toInt(), (gS / n).toInt(), (bS / n).toInt())
        else Color.rgb(30, 30, 30)
    }

    // ═══════════════════════════════════════════════════════════════
    // Stage 2: Gradient-Aware Inpainting with Dilated Mask
    // ═══════════════════════════════════════════════════════════════

    private fun inpaintWithGradient(bitmap: Bitmap, canvas: Canvas, blocks: List<TextBlock>) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val bw = bitmap.width; val bh = bitmap.height

        for (block in blocks) {
            val r = block.bounds
            // Dilate mask by MASK_DILATION_PX to cover anti-aliased text edges
            val dilated = Rect(
                (r.left - MASK_DILATION_PX).coerceAtLeast(0),
                (r.top - MASK_DILATION_PX).coerceAtLeast(0),
                (r.right + MASK_DILATION_PX).coerceAtMost(bw),
                (r.bottom + MASK_DILATION_PX).coerceAtMost(bh)
            )

            // Draw vertical gradient (top-color → bottom-color) to reconstruct bg
            val shader = LinearGradient(
                dilated.left.toFloat(), dilated.top.toFloat(),
                dilated.left.toFloat(), dilated.bottom.toFloat(),
                block.bgGradientTop, block.bgGradientBottom,
                Shader.TileMode.CLAMP
            )
            paint.shader = shader
            paint.style = Paint.Style.FILL
            canvas.drawRect(RectF(dilated), paint)
            paint.shader = null

            // Feather all 4 edges for seamless blending
            featherRect(bitmap, dilated, bw, bh)
        }
    }

    /**
     * Feather all edges of a rect by blending inpainted pixels
     * with surrounding original pixels using distance-based alpha.
     */
    private fun featherRect(bitmap: Bitmap, r: Rect, bw: Int, bh: Int) {
        // Top edge
        for (x in r.left.coerceAtLeast(0) until r.right.coerceAtMost(bw)) {
            for (f in 0 until FEATHER_PX) {
                val y = (r.top + f).coerceIn(0, bh - 1)
                val srcY = (r.top - FEATHER_PX + f).coerceIn(0, bh - 1)
                val ratio = (f + 1).toFloat() / (FEATHER_PX + 1)
                bitmap.setPixel(x, y, blendColors(bitmap.getPixel(x, srcY), bitmap.getPixel(x, y), ratio))
            }
        }
        // Bottom edge
        for (x in r.left.coerceAtLeast(0) until r.right.coerceAtMost(bw)) {
            for (f in 0 until FEATHER_PX) {
                val y = (r.bottom - 1 - f).coerceIn(0, bh - 1)
                val srcY = (r.bottom + FEATHER_PX - 1 - f).coerceIn(0, bh - 1)
                val ratio = (f + 1).toFloat() / (FEATHER_PX + 1)
                bitmap.setPixel(x, y, blendColors(bitmap.getPixel(x, srcY), bitmap.getPixel(x, y), ratio))
            }
        }
        // Left edge
        for (y in r.top.coerceAtLeast(0) until r.bottom.coerceAtMost(bh)) {
            for (f in 0 until FEATHER_PX) {
                val x = (r.left + f).coerceIn(0, bw - 1)
                val srcX = (r.left - FEATHER_PX + f).coerceIn(0, bw - 1)
                val ratio = (f + 1).toFloat() / (FEATHER_PX + 1)
                bitmap.setPixel(x, y, blendColors(bitmap.getPixel(srcX, y), bitmap.getPixel(x, y), ratio))
            }
        }
        // Right edge
        for (y in r.top.coerceAtLeast(0) until r.bottom.coerceAtMost(bh)) {
            for (f in 0 until FEATHER_PX) {
                val x = (r.right - 1 - f).coerceIn(0, bw - 1)
                val srcX = (r.right + FEATHER_PX - 1 - f).coerceIn(0, bw - 1)
                val ratio = (f + 1).toFloat() / (FEATHER_PX + 1)
                bitmap.setPixel(x, y, blendColors(bitmap.getPixel(srcX, y), bitmap.getPixel(x, y), ratio))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Stage 3: Translation
    // ═══════════════════════════════════════════════════════════════

    private suspend fun translateBlocks(
        blocks: MutableList<TextBlock>, engine: Engine?, targetLanguage: String
    ) = withContext(Dispatchers.Default) {  // Use Default for better parallelism
        // Use TranslationManager which handles all sources with priority
        // Note: TranslationManager is initialized in activities, so we need to handle it differently here
        
        val texts = blocks.map { it.text }
        
        // Try Hugging Face API first (highest quality)
        try {
            val translations = HuggingFaceApi.translateBatch(texts, targetLanguage)
            blocks.forEachIndexed { i, b ->
                b.translatedText = translations.getOrElse(i) { b.text }
                    .trim()
                    .ifBlank { b.text }
            }
            Log.d(TAG, "Hugging Face API translation successful for ${blocks.size} blocks")
            return@withContext
        } catch (e: Exception) {
            Log.e(TAG, "Hugging Face API translation failed: ${e.message}")
        }
        
        // Fallback to Gemma if available
        if (engine != null) {
            try {
                val numbered = blocks.mapIndexed { i, b -> "${i + 1}. ${b.text}" }.joinToString("\n")
                val prompt = """You are a precise translation assistant. 
Translate each numbered line COMPLETELY into $targetLanguage using only Devanagari script.

IMPORTANT RULES:
1. Translate EVERYTHING into $targetLanguage - do NOT keep any English words
2. Use only Devanagari script for ALL output
3. Output exactly ${blocks.size} numbered lines
4. Only output the translations, no explanations, no extra text
5. Be accurate and natural in $targetLanguage

$numbered"""
                val sb = StringBuilder()
                engine.createConversation().use { c ->
                    c.sendMessageAsync(prompt).collect { sb.append(it) }
                }
                val lines = sb.toString().trim().lines()
                    .map { it.trim() }.filter { it.isNotBlank() }
                    .map { it.replace(Regex("""^\d+\.\s*"""), "") }
                blocks.forEachIndexed { i, b ->
                    b.translatedText = lines.getOrElse(i) { b.text }
                }
                Log.d(TAG, "Gemma translation successful for ${blocks.size} blocks")
            } catch (e: Exception) {
                Log.e(TAG, "Gemma translation failed: ${e.message}")
                for (b in blocks) b.translatedText = b.text
            }
        } else {
            // No engine, no API - use original text
            for (b in blocks) b.translatedText = b.text
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Stage 4+5: Hierarchy-Aware Typography + Compositing
    // ═══════════════════════════════════════════════════════════════

    private fun renderWithHierarchy(canvas: Canvas, blocks: List<TextBlock>) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

        for (block in blocks) {
            if (block.translatedText.isBlank()) continue

            val r = block.bounds

            // ── Inner padding (keep-out zone for icons) - reduced for Google Assistant match ──
            val padX = (r.width() * 0.04f).roundToInt().coerceAtLeast(1)  // Reduced from 8% to 4%
            val padY = (r.height() * 0.04f).roundToInt().coerceAtLeast(1)  // Reduced from 8% to 4%
            val innerLeft = r.left + padX
            val innerTop = r.top + padY
            val availW = (r.width() - padX * 2).coerceAtLeast(10)
            val availH = (r.height() - padY * 2).coerceAtLeast(10)

            // ── Font weight from hierarchy analysis ──
            paint.typeface = if (block.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            // ── Enhanced text color calculation for better contrast ──
            val bgLum = luminance(block.bgColor)
            val txtLum = luminance(block.textColor)
            val contrast = abs(bgLum - txtLum)

            val renderColor = if (contrast > 0.15f) {
                // Use original text color with sampled alpha
                Color.argb(block.textAlpha, Color.red(block.textColor),
                    Color.green(block.textColor), Color.blue(block.textColor))
            } else {
                // Force high contrast - Google Assistant style (pure black/white)
                if (bgLum > 0.5f) Color.argb(block.textAlpha, 0, 0, 0)
                else Color.argb(block.textAlpha, 255, 255, 255)
            }
            paint.color = renderColor

            // ── Dynamic font sizing with Indic line-height ──
            // Indic scripts need ~20% more line height for matras
            val lineSpacingMult = 1.3f  // Higher for Devanagari/Indic

            // Start with LARGER font size to match Google Assistant appearance
            // Use 85% of available height for better visibility
            var fontSize = (availH * 0.85f).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
            var layout: StaticLayout
            var attempts = 0

            do {
                paint.textSize = fontSize
                layout = StaticLayout.Builder.obtain(
                    block.translatedText, 0, block.translatedText.length, paint, availW
                )
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, lineSpacingMult)
                    .setIncludePad(true)  // Include padding for Indic ascenders/descenders
                    .build()

                // Allow text to be slightly larger than available height for better Google Assistant match
                if (layout.height <= availH * 1.1f || fontSize <= MIN_FONT_SIZE) break
                // Gentle scaling down
                fontSize -= (fontSize * 0.08f).coerceAtLeast(0.5f)
                attempts++
            } while (attempts < 30)

            // Center vertically
            val yOffset = ((availH - layout.height) / 2f).coerceAtLeast(0f)

            // Add subtle text shadow for better readability (Google Assistant style)
            paint.setShadowLayer(2f, 0f, 1f, Color.argb(80, 0, 0, 0))

            canvas.save()
            canvas.translate(innerLeft.toFloat(), innerTop + yOffset)
            layout.draw(canvas)
            canvas.restore()
            
            // Reset shadow for next block
            paint.clearShadowLayer()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════════

    private fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
        val r = (Color.red(c1) * (1 - ratio) + Color.red(c2) * ratio).toInt()
        val g = (Color.green(c1) * (1 - ratio) + Color.green(c2) * ratio).toInt()
        val b = (Color.blue(c1) * (1 - ratio) + Color.blue(c2) * ratio).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private fun luminance(color: Int): Float {
        return 0.299f * (Color.red(color) / 255f) +
               0.587f * (Color.green(color) / 255f) +
               0.114f * (Color.blue(color) / 255f)
    }
}
