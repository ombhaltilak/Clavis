package com.example.mhetranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.graphics.Typeface
import android.view.View

/**
 * Overlay that paints translated text at exact text node positions.
 *
 * Key design: The view is fully TRANSPARENT. Only tiny rectangles
 * at each text node's bounds are painted with the REAL sampled
 * background color from the screenshot. Everything else (images,
 * icons, buttons) shows through untouched.
 */
class ScreenTranslationOverlay(context: Context) : View(context) {

    private var nodes = listOf<TranslatedTextNode>()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
    }

    fun setTranslatedNodes(translatedNodes: List<TranslatedTextNode>) {
        nodes = translatedNodes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (node in nodes) {
            if (node.translatedText.isBlank()) continue

            val rect = node.bounds

            // ── 1. Paint the REAL background color (sampled from screenshot) ──
            // This covers ONLY the text area — everything else is transparent
            bgPaint.color = node.bgColor
            canvas.drawRect(
                rect.left.toFloat(),
                rect.top.toFloat(),
                rect.right.toFloat(),
                rect.bottom.toFloat(),
                bgPaint
            )

            // ── 2. Pick text color based on background luminance ──
            val r = Color.red(node.bgColor) / 255f
            val g = Color.green(node.bgColor) / 255f
            val b = Color.blue(node.bgColor) / 255f
            val lum = 0.299f * r + 0.587f * g + 0.114f * b

            textPaint.color = if (lum > 0.5f) {
                Color.argb(235, 15, 15, 15)      // Dark text on light bg
            } else {
                Color.argb(240, 245, 245, 245)   // Light text on dark bg
            }
            textPaint.textSize = node.textSizePx

            // ── 3. Draw translated text with wrapping inside the rect ──
            val padding = 4f
            val availWidth = (rect.width() - padding * 2).toInt().coerceAtLeast(10)

            val staticLayout = StaticLayout.Builder.obtain(
                node.translatedText, 0, node.translatedText.length, textPaint, availWidth
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.1f)
                .setIncludePad(false)
                .build()

            canvas.save()
            canvas.translate(rect.left + padding, rect.top + padding)
            staticLayout.draw(canvas)
            canvas.restore()
        }
    }
}
