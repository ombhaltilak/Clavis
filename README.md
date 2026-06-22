# Clavis

MHETranslator (Clavis AI Screen Translator) is an advanced Android application that integrates AI-powered OCR and translation to provide a seamless, non-intrusive screen translation experience.

## Features
- **On-Device OCR:** Uses Google ML Kit for fast and accurate text recognition.
- **AI Translation:** Integrates Gemini AI and Hugging Face inference for high-quality translations.
- **Screen Overlay:** Uses MediaProjection and a floating UI to translate text directly on the screen without leaving the active application.
- **Voice Commands:** Supports wake-word detection and intuitive voice feedback (Google Assistant style).

## Setup Instructions

1. **Clone the repository:**
   ```bash
   git clone <repository_url>
   ```

2. **Configure API Keys:**
   - Create a `local.properties` file in the root directory if it doesn't exist.
   - Add your API key to `local.properties`:
     ```properties
     apiKey=YOUR_API_KEY_HERE
     ```
   - Ensure your Android SDK path is also set correctly: `sdk.dir=/path/to/android/sdk`.

3. **Firebase Setup:**
   - Go to the [Firebase Console](https://console.firebase.google.com/) and create a new project.
   - Add an Android app with the package name `com.example.mhetranslator`.
   - Download the `google-services.json` file.
   - Place the `google-services.json` file inside the `app/` directory.

4. **Build and Run:**
   - Open the project in Android Studio.
   - Sync the project with Gradle files.
   - Build and run the application on your Android device or emulator.
