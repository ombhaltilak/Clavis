# Clavis

MHETranslator (Clavis AI Screen Translator) is an advanced Android application that integrates AI-powered OCR and translation to provide a seamless, non-intrusive screen translation experience.

## Features
- **On-Device OCR:** Uses Google ML Kit for fast and accurate text recognition.
- **AI Translation:** Integrates Gemini AI and Hugging Face inference for high-quality translations.
- **Crop translation:** Lets the user select an area from any app or image, then returns the recognized text to Clavis for translation.
- **Voice Commands:** Supports wake-word detection and intuitive voice feedback (Google Assistant style).

## Setup Instructions

1. **Clone the repository:**
   ```bash
   git clone <repository_url>
   ```

2. **Configure providers in the app:**
   - Open **Settings** in Clavis after installing the app.
   - Add your Gemini API key and, optionally, your Hugging Face token.
   - For Qwen, the Hugging Face token needs **Inference Providers** permission.
   - Keys stay only on the device and are never stored in this repository.

3. **Build and run:**
   - Open the project in Android Studio.
   - Sync the project with Gradle files.
   - Build and run the application on your Android device or emulator.
