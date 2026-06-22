import java.util.Properties // ◄ ADDED THIS IMPORT AT THE VERY TOP

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.gms.google.services)
}

android {
    namespace = "com.example.mhetranslator"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.mhetranslator"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Read your apiKey from local.properties safely using the imported class
        val localProperties = Properties() // ◄ CHANGED THIS LINE
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        }
        val apiKeyStr = localProperties.getProperty("apiKey") ?: ""
        buildConfigField("String", "apiKey", "\"$apiKeyStr\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.firebase.ai)
    // Force all Ktor dependencies strictly to version 2.3.12 to avoid HttpTimeout conflict
    implementation(enforcedPlatform("io.ktor:ktor-bom:2.3.12"))

    // Core Google AI SDK for Gemini compatibility
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-android")

    // Google ML Kit for On-Device Text Recognition (OCR)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
    implementation("com.google.mlkit:translate:17.0.3")

    // LiteRT-LM for on-device Gemma E2B inference (replaces deprecated MediaPipe)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")

    // Firebase Storage for hosting Gemma model (auto-download for users)
    implementation("com.google.firebase:firebase-storage-ktx:21.0.1")
    
    // Hugging Face Inference API (Online only)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Image Crop View for improved selection UI
    implementation(libs.image.crop.view)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}