package com.example.mhetranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class CapturePermissionActivity : ComponentActivity() {
    private val startMediaProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val receiver = object : android.os.ResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    finish()
                }
            }
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("code", result.resultCode)
                putExtra("data", result.data)
                putExtra("receiver", receiver)
            }
            // Launch Foreground Service required for Android 14+
            startForegroundService(serviceIntent)
        } else {
            Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startMediaProjection.launch(mpm.createScreenCaptureIntent())
    }
}
