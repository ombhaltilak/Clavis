package com.example.mhetranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

data class TranslatedBlock(val rect: Rect, val translatedText: String)

class TranslationOverlayView(context: Context) : View(context) {

    private var blocks = listOf<TranslatedBlock>()

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#CC000000") // 80% opacity black
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    fun updateBlocks(newBlocks: List<TranslatedBlock>) {
        blocks = newBlocks
        invalidate() // Request redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        for (block in blocks) {
            // Draw background rectangle
            canvas.drawRect(block.rect, bgPaint)
            
            // Draw text centered in the rectangle
            // Basic text fitting (can be improved)
            val textX = block.rect.exactCenterX()
            val textY = block.rect.exactCenterY() - (textPaint.descent() + textPaint.ascent()) / 2
            
            // Draw translated text
            canvas.drawText(block.translatedText, textX, textY, textPaint)
        }
    }
}
