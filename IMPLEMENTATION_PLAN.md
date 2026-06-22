# Implementation Plan: ImageCropView Integration

## Goal
Replace the current custom crop selection UI with ImageCropView library while maintaining all existing functionality (Hugging Face API, TranslationPipeline, OCR).

## Steps

### 1. Add Dependency
Add ImageCropView to app/build.gradle.kts

### 2. Update CropActivity.kt
Replace custom Canvas-based crop with ImageCropView composable

### 3. Maintain Integration
- Keep ScreenshotHolder for screenshot capture
- Keep TranslationPipeline for processing
- Keep HuggingFaceApi for translation
- Only replace the UI layer

### 4. Customize Appearance
Apply Google Translate-style colors:
- Cyan accent: Color(0xFF00E5FF)
- Semi-transparent overlay: Color(0x55000000)
- Circular handles: 12.dp radius
- Rule-of-thirds grid

## Files to Modify
1. app/build.gradle.kts - Add ImageCropView dependency
2. app/src/main/java/com/example/mhetranslator/CropActivity.kt - Replace with ImageCropView

## Files to Keep Unchanged
- ScreenshotHolder.kt
- TranslationPipeline.kt
- HuggingFaceApi.kt
- MainActivity.kt
- All other files
