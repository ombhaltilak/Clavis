package com.example.mhetranslator

import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.image.cropview.CropType
import com.image.cropview.EdgeType
import com.image.cropview.ImageCropView
import com.image.cropview.rememberSaveableImageCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * CropActivity with ImageCropView for improved UI
 * Uses ImageCropView library for rectangle selection
 */
class CropActivity : ComponentActivity() {

    private var engine: Engine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bitmap = ScreenshotHolder.bitmap
        if (bitmap == null) {
            Toast.makeText(this, "Failed to load screenshot.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Initialize Gemma 4 E2B model in background
        lifecycleScope.launch(Dispatchers.IO) {
            initGemmaModel()
        }

        setContent {
            var isLoading by remember { mutableStateOf(false) }
            var translatedText by remember { mutableStateOf("") }
            var statusMessage by remember { mutableStateOf("") }
            var translationRect by remember { mutableStateOf<ComposeRect?>(null) }
            var showTranslation by remember { mutableStateOf(false) }
            var compositedBitmap by remember { mutableStateOf<Bitmap?>(null) }

            val prefs = getSharedPreferences("mhe_prefs", MODE_PRIVATE)
            val targetLanguage = prefs.getString("selected_language", "Hindi") ?: "Hindi"

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    ImprovedCropScreen(
                        bitmap = bitmap,
                        compositedBitmap = compositedBitmap,
                        isLoading = isLoading,
                        statusMessage = statusMessage,
                        translatedText = translatedText,
                        translationRect = translationRect,
                        targetLanguage = targetLanguage,
                        onCropAreaSelected = { composeRect ->
                            isLoading = true
                            showTranslation = false
                            compositedBitmap = null
                            translationRect = null
                            lifecycleScope.launch {
                                val result = TranslationPipeline.process(
                                    originalBitmap = bitmap,
                                    cropRect = composeRect,
                                    engine = engine,
                                    targetLanguage = targetLanguage,
                                    onStatus = { status ->
                                        statusMessage = status
                                    }
                                )
                                compositedBitmap = result
                                isLoading = false
                                statusMessage = ""
                                showTranslation = true
                                translationRect = composeRect
                            }
                        },
                        onSelectAgain = {
                            showTranslation = false
                            translatedText = ""
                            translationRect = null
                            compositedBitmap = null
                        },
                        onCancel = { finish() }
                    )
                }
            }
        }
    }

    private fun initGemmaModel() {
        if (!GemmaModelManager.isModelDownloaded(this)) {
            return
        }
        try {
            val modelPath = GemmaModelManager.getModelPath(this)
            val config = EngineConfig(modelPath = modelPath)
            val eng = Engine(config)
            eng.initialize()
            engine = eng
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.close()
    }
    
    private fun findCropRect(original: Bitmap, cropped: Bitmap): ComposeRect {
        val ow = original.width
        val oh = original.height
        val cw = cropped.width
        val ch = cropped.height

        if (cw > ow || ch > oh || cw == 0 || ch == 0) return ComposeRect(0f, 0f, cw.toFloat(), ch.toFloat())

        val p1 = cropped.getPixel(0, 0)
        val p2 = cropped.getPixel(cw - 1, 0)
        val p3 = cropped.getPixel(0, ch - 1)
        val p4 = cropped.getPixel(cw - 1, ch - 1)
        val pCenter = cropped.getPixel(cw / 2, ch / 2)

        for (y in 0 .. oh - ch) {
            for (x in 0 .. ow - cw) {
                if (original.getPixel(x, y) == p1 &&
                    original.getPixel(x + cw - 1, y) == p2 &&
                    original.getPixel(x, y + ch - 1) == p3 &&
                    original.getPixel(x + cw - 1, y + ch - 1) == p4 &&
                    original.getPixel(x + cw / 2, y + ch / 2) == pCenter) {
                    
                    var match = true
                    for (cx in 0 until cw step 10) {
                        if (original.getPixel(x + cx, y) != cropped.getPixel(cx, 0)) {
                            match = false
                            break
                        }
                    }
                    if (match) {
                        return ComposeRect(x.toFloat(), y.toFloat(), (x + cw).toFloat(), (y + ch).toFloat())
                    }
                }
            }
        }
        return ComposeRect(0f, 0f, cw.toFloat(), ch.toFloat())
    }
}

@Composable
fun ImprovedCropScreen(
    bitmap: Bitmap,
    compositedBitmap: Bitmap?,
    isLoading: Boolean,
    statusMessage: String,
    translatedText: String,
    translationRect: ComposeRect?,
    targetLanguage: String,
    onCropAreaSelected: (ComposeRect) -> Unit,
    onSelectAgain: () -> Unit,
    onCancel: () -> Unit
) {
    val localContext = LocalContext.current
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Display the bitmap
        androidx.compose.foundation.Image(
            bitmap = (compositedBitmap ?: bitmap).asImageBitmap(),
            contentDescription = "Screenshot",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        
        // Selection mode - use ImageCropView
        if (compositedBitmap == null) {
            val imageCrop = rememberSaveableImageCrop(bitmap)
            
            ImageCropView(
                imageCrop = imageCrop,
                modifier = Modifier.fillMaxSize(),
                // Google Translate-style colors
                guideLineColor = Color(0xFF00E5FF),  // Cyan accent
                guideLineWidth = 2.dp,
                edgeCircleSize = 12.dp,  // Larger circular handles
                showGuideLines = true,  // Rule-of-thirds grid
                cropType = CropType.FREE_STYLE,
                edgeType = EdgeType.CIRCULAR,
                enableZoom = true
            )
            
            // Translate button - aligned to bottom
            Button(
                onClick = {
                    val croppedBitmap = imageCrop.onCrop(true)
                    val rect = (localContext as? CropActivity)?.findCropRect(bitmap, croppedBitmap) 
                        ?: ComposeRect(0f, 0f, croppedBitmap.width.toFloat(), croppedBitmap.height.toFloat())
                    onCropAreaSelected(rect)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(30.dp)
            ) {
                Text("⚡ Translate with Clavis", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        // Loading overlay
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xBB000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00E5FF), strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(statusMessage, color = Color(0xFF00E5FF), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Google Assistant-style In-Place Text Replacement
        if (translatedText.isNotEmpty() && translationRect != null) {
            val density = LocalDensity.current

            // Sample the background color from the bitmap crop area
            val bgColor = remember(translationRect) {
                try {
                    val bw = bitmap.width; val bh = bitmap.height
                    val l = translationRect.left.toInt().coerceIn(0, bw - 1)
                    val t = translationRect.top.toInt().coerceIn(0, bh - 1)
                    val r = translationRect.right.toInt().coerceIn(l + 1, bw)
                    val b = translationRect.bottom.toInt().coerceIn(t + 1, bh)
                    var rS = 0L; var gS = 0L; var bS = 0L; var n = 0
                    val sx = ((r - l) / 8).coerceAtLeast(1)
                    val sy = ((b - t) / 8).coerceAtLeast(1)
                    var py = t
                    while (py < b) {
                        var px = l
                        while (px < r) {
                            val px2 = bitmap.getPixel(px, py)
                            rS += android.graphics.Color.red(px2)
                            gS += android.graphics.Color.green(px2)
                            bS += android.graphics.Color.blue(px2)
                            n++; px += sx
                        }
                        py += sy
                    }
                    if (n > 0) Color(
                        red = (rS / n).toInt(),
                        green = (gS / n).toInt(),
                        blue = (bS / n).toInt(),
                        alpha = 255
                    ) else Color(0xFF121212)
                } catch (e: Exception) { Color(0xFF121212) }
            }

            // Auto text color: pure black on light bg, pure white on dark bg - Google Assistant style
            val lum = 0.299f * bgColor.red + 0.587f * bgColor.green + 0.114f * bgColor.blue
            val textColor = if (lum > 0.5f) Color.Black else Color.White

            // Infer font size from crop height vs line count (match original text size)
            val lineCount = translatedText.lines().count { it.isNotBlank() }.coerceAtLeast(1)
            val rectHeightDp = with(density) { (translationRect.bottom - translationRect.top).toDp() }
            // Larger font size to match Google Assistant - use 80% of line height
            val inferredFontSp = ((rectHeightDp.value / lineCount) * 0.8f)
                .coerceIn(14f, 32f)

            with(density) {
                // Paint translated text exactly over the crop rect - replacing original
                // Google Assistant style: clean, natural appearance without breaking UI
                Box(
                    modifier = Modifier
                        .offset(
                            x = translationRect.left.toDp(),
                            y = translationRect.top.toDp()
                        )
                        .width((translationRect.right - translationRect.left).toDp())
                        .height(rectHeightDp)  // Fixed height to match original
                        .background(
                            bgColor,  // Use solid background for Google Assistant match
                            shape = RoundedCornerShape(2.dp)  // Subtle rounding
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)  // Minimal padding
                ) {
                    Text(
                        text = translatedText,
                        color = textColor,
                        fontSize = inferredFontSp.sp,
                        lineHeight = (inferredFontSp * 1.4f).sp,  // Tighter line spacing
                        fontWeight = FontWeight.Normal,
                        maxLines = 10,  // Prevent excessive expansion
                        overflow = TextOverflow.Clip
                    )
                }
            }

            // Google Assistant-style bottom bar
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xF81E1E1E))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("✦", color = Color(0xFF00E5FF), fontSize = 14.sp)
                    Text("English", color = Color(0xFFB0B0B0), fontSize = 14.sp)
                    Text("→", color = Color(0xFF00E5FF), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(targetLanguage, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Save button
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF00E5FF))
                            .clickable {
                                // Save the composited bitmap
                                compositedBitmap?.let { bmp ->
                                    val contentValues = android.content.ContentValues().apply {
                                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "Clavis_Translation_${System.currentTimeMillis()}.png")
                                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                                    }
                                    val resolver = localContext.contentResolver
                                    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                                    uri?.let {
                                        resolver.openOutputStream(it)?.use { os ->
                                            bmp.compress(Bitmap.CompressFormat.PNG, 90, os)
                                        }
                                    }
                                }
                            }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("💾", fontSize = 16.sp, color = Color.Black)
                    }
                    TextButton(onClick = {
                        onSelectAgain()
                    }) {
                        Text("Select Again", color = Color(0xFF00E5FF), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    TextButton(onClick = onCancel) {
                        Text("✕", color = Color(0xFF888888), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Top instruction bar - only during crop mode
        if (compositedBitmap == null && !isLoading) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 50.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "✋ Drag handles to select text area",
                    color = Color.White,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(onClick = onCancel) {
                    Text("CANCEL", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
