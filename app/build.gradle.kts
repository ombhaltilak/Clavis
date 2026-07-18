plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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

    // Google ML Kit for On-Device Text Recognition (OCR)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0") {
        exclude(group = "com.google.mlkit", module = "vision-interfaces")
    }
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1") {
        exclude(group = "com.google.mlkit", module = "text-recognition-bundled-common")
        exclude(group = "com.google.mlkit", module = "vision-interfaces")
    }
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1") {
        exclude(group = "com.google.mlkit", module = "text-recognition-bundled-common")
        exclude(group = "com.google.mlkit", module = "vision-interfaces")
    }
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1") {
        exclude(group = "com.google.mlkit", module = "text-recognition-bundled-common")
        exclude(group = "com.google.mlkit", module = "vision-interfaces")
    }
    implementation("com.google.mlkit:text-recognition-korean:16.0.1") {
        exclude(group = "com.google.mlkit", module = "text-recognition-bundled-common")
        exclude(group = "com.google.mlkit", module = "vision-interfaces")
    }
    implementation("com.google.mlkit:translate:17.0.3")
    // Bundled, on-device language detection for the offline translation pipeline.
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation(files("libs/text-recognition-bundled-common-17.0.0.aar"))
    implementation(files("libs/vision-interfaces-16.3.0.aar"))

    // LiteRT-LM for on-device Gemma E2B inference (replaces deprecated MediaPipe)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")

    // Online provider clients
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Adjustable crop selection
    implementation(libs.image.crop.view)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}