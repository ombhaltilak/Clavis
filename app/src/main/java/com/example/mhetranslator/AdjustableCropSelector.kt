package com.example.mhetranslator

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

private enum class DragTarget { NONE, CREATE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

@Composable
fun AdjustableCropSelector(bitmap: Bitmap, onCropSelected: (Rect) -> Unit) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var crop by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    val latestCrop by rememberUpdatedState(crop)
    Box(modifier = Modifier.fillMaxSize()) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Captured screen", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(
            modifier = Modifier.fillMaxSize()
                .onSizeChanged { size -> if (size != canvasSize) { canvasSize = size; crop = Rect(0f, 0f, 0f, 0f) } }
                .pointerInput(canvasSize) {
                    var target = DragTarget.NONE
                    var anchor = Offset.Zero
                    detectDragGestures(
                        onDragStart = { point -> val current = latestCrop; target = hitTarget(point, current); if (target == DragTarget.CREATE) { anchor = point; crop = Rect(point.x, point.y, point.x, point.y) } },
                        onDragEnd = { target = DragTarget.NONE },
                        onDragCancel = { target = DragTarget.NONE }
                    ) { change, amount ->
                        change.consume()
                        if (target == DragTarget.CREATE) crop = Rect(minOf(anchor.x, change.position.x).coerceIn(0f, canvasSize.width.toFloat()), minOf(anchor.y, change.position.y).coerceIn(0f, canvasSize.height.toFloat()), maxOf(anchor.x, change.position.x).coerceIn(0f, canvasSize.width.toFloat()), maxOf(anchor.y, change.position.y).coerceIn(0f, canvasSize.height.toFloat())) else if (target != DragTarget.NONE && canvasSize != IntSize.Zero) crop = updateCrop(latestCrop, target, amount, canvasSize)
                    }
                }
        ) {
            if (crop.width > 0f) {
                drawRect(Color(0xFF00E5FF), topLeft = crop.topLeft, size = crop.size, style = Stroke(width = 4.dp.toPx()))
                drawLine(Color(0xAA00E5FF), Offset(crop.left + crop.width / 3f, crop.top), Offset(crop.left + crop.width / 3f, crop.bottom), 1.dp.toPx())
                drawLine(Color(0xAA00E5FF), Offset(crop.left + crop.width * 2f / 3f, crop.top), Offset(crop.left + crop.width * 2f / 3f, crop.bottom), 1.dp.toPx())
                drawLine(Color(0xAA00E5FF), Offset(crop.left, crop.top + crop.height / 3f), Offset(crop.right, crop.top + crop.height / 3f), 1.dp.toPx())
                drawLine(Color(0xAA00E5FF), Offset(crop.left, crop.top + crop.height * 2f / 3f), Offset(crop.right, crop.top + crop.height * 2f / 3f), 1.dp.toPx())
                listOf(crop.topLeft, Offset(crop.right, crop.top), Offset(crop.left, crop.bottom), crop.bottomRight).forEach { drawCircle(Color(0xFF00E5FF), 18.dp.toPx(), it) }
            }
        }
        Button(
            enabled = crop.width >= 120f && crop.height >= 90f,
            onClick = { if (canvasSize.width > 0) onCropSelected(Rect(crop.left * bitmap.width / canvasSize.width, crop.top * bitmap.height / canvasSize.height, crop.right * bitmap.width / canvasSize.width, crop.bottom * bitmap.height / canvasSize.height)) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp)
        ) { Text("Translate selected area") }
    }
}

private fun hitTarget(point: Offset, crop: Rect): DragTarget {
    if (crop.width < 1f || crop.height < 1f) return DragTarget.CREATE
    fun near(corner: Offset) = hypot((point.x - corner.x).toDouble(), (point.y - corner.y).toDouble()) < 72.0
    return when {
        near(crop.topLeft) -> DragTarget.TOP_LEFT
        near(Offset(crop.right, crop.top)) -> DragTarget.TOP_RIGHT
        near(Offset(crop.left, crop.bottom)) -> DragTarget.BOTTOM_LEFT
        near(crop.bottomRight) -> DragTarget.BOTTOM_RIGHT
        point.x in crop.left..crop.right && point.y in crop.top..crop.bottom -> DragTarget.MOVE
        else -> DragTarget.CREATE
    }
}

private fun updateCrop(crop: Rect, target: DragTarget, delta: Offset, size: IntSize): Rect {
    val minWidth = 120f; val minHeight = 90f; val maxX = size.width.toFloat(); val maxY = size.height.toFloat()
    return when (target) {
        DragTarget.TOP_LEFT -> Rect((crop.left + delta.x).coerceIn(0f, crop.right - minWidth), (crop.top + delta.y).coerceIn(0f, crop.bottom - minHeight), crop.right, crop.bottom)
        DragTarget.TOP_RIGHT -> Rect(crop.left, (crop.top + delta.y).coerceIn(0f, crop.bottom - minHeight), (crop.right + delta.x).coerceIn(crop.left + minWidth, maxX), crop.bottom)
        DragTarget.BOTTOM_LEFT -> Rect((crop.left + delta.x).coerceIn(0f, crop.right - minWidth), crop.top, crop.right, (crop.bottom + delta.y).coerceIn(crop.top + minHeight, maxY))
        DragTarget.BOTTOM_RIGHT -> Rect(crop.left, crop.top, (crop.right + delta.x).coerceIn(crop.left + minWidth, maxX), (crop.bottom + delta.y).coerceIn(crop.top + minHeight, maxY))
        DragTarget.MOVE -> { val left = (crop.left + delta.x).coerceIn(0f, maxX - crop.width); val top = (crop.top + delta.y).coerceIn(0f, maxY - crop.height); Rect(left, top, left + crop.width, top + crop.height) }
        DragTarget.CREATE, DragTarget.NONE -> crop
    }
}
