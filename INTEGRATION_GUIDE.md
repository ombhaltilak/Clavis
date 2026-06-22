# ImageCropView Integration Guide

## Overview
This guide shows how to integrate ImageCropView library into your MHETranslator app to achieve Google Translate-style UI.

## Step 1: Add Dependency

### Update `gradle/libs.versions.toml`
Added:
```toml
image-crop-view = { group = "io.github.rroohit", name = "ImageCropView", version = "4.1.0" }
```

### Update `app/build.gradle.kts`
Added:
```kotlin
// Image Crop View for improved selection UI
implementation(libs.image.crop.view)
```

## Step 2: New CropActivity Implementation

Created `/home/om/AndroidStudioProjects/MHETranslator/app/src/main/java/com/example/mhetranslator/CropActivity_ImageCropView.kt`

### Key Features:
- ✅ ImageCropView for rectangle selection
- ✅ Google Translate-style cyan colors (0xFF00E5FF)
- ✅ Circular drag handles (12.dp)
- ✅ Rule-of-thirds grid
- ✅ Pinch-to-zoom support
- ✅ In-place text replacement (preserved from original)
- ✅ Bottom bar with language display
- ✅ Save/Select Again/Cancel buttons

### Migration Notes:

#### What Changed:
1. **Selection UI**: Custom Canvas → ImageCropView
2. **Crop Rectangle**: Custom Rect → android.graphics.Rect from ImageCropView
3. **Translate Button**: Moved to bottom center (over ImageCropView)

#### What Stayed the Same:
1. ✅ ScreenshotHolder integration
2. ✅ TranslationPipeline integration
3. ✅ Gemma model initialization
4. ✅ Translation result display (in-place)
5. ✅ Bottom bar UI
6. ✅ Loading overlay
7. ✅ Save functionality
8. ✅ Select Again functionality

## Step 3: Replace CropActivity.kt

To complete the integration:

```bash
# Backup current file
cp app/src/main/java/com/example/mhetranslator/CropActivity.kt \
   app/src/main/java/com/example/mhetranslator/CropActivity.kt.backup

# Copy new implementation
cp app/src/main/java/com/example/mhetranslator/CropActivity_ImageCropView.kt \
   app/src/main/java/com/example/mhetranslator/CropActivity.kt
```

## Step 4: Clean and Rebuild

```bash
# Clean Gradle cache
./gradlew clean

# Sync dependencies
./gradlew :app:dependencies

# Build
./gradlew :app:compileDebugKotlin
```

## Expected UI Improvements

| Feature | Before | After |
|---------|--------|-------|
| Crop Handles | Square brackets | Circular (12.dp) |
| Handle Color | Default | Cyan (0xFF00E5FF) |
| Grid Lines | None | Rule-of-thirds |
| Zoom | None | Pinch-to-zoom |
| Border Width | Default | 2.dp |
| Overlay | Custom | Library-managed |

## Troubleshooting

### If you get compilation errors:

1. **"Cannot find symbol ImageCropView"**
   - Make sure you ran `./gradlew clean`
   - Check internet connection for dependency download
   - Verify `libs.versions.toml` has the image-crop-view entry

2. **"Unresolved reference: rememberSaveableImageCrop"**
   - Make sure the import is present:
   ```kotlin
   import io.github.rroohit.imagecropview.rememberSaveableImageCrop
   ```

3. **Build fails with dependency resolution**
   - Try: `./gradlew clean build --refresh-dependencies`
   - Or manually add to build.gradle.kts:
   ```kotlin
   dependencies {
       implementation("io.github.rroohit:ImageCropView:4.1.0")
   }
   ```

## Files Modified

1. ✅ `gradle/libs.versions.toml` - Added image-crop-view dependency
2. ✅ `app/build.gradle.kts` - Added implementation
3. ✅ `CropActivity_ImageCropView.kt` - Created new implementation

## Files to Replace

1. `app/src/main/java/com/example/mhetranslator/CropActivity.kt` - Replace with new version

## Files Unchanged

All other files remain the same:
- ScreenshotHolder.kt
- TranslationPipeline.kt
- HuggingFaceApi.kt
- MainActivity.kt
- GemmaModelManager.kt
- All other files
