package com.example.mhetranslator

import android.graphics.Bitmap

object ScreenshotHolder {
    // Singleton object to hold the screenshot in memory to prevent TransactionTooLargeException
    var bitmap: Bitmap? = null
}
