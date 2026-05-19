# 📘 SmartNotes AI – Simplified Study Assistant

## 📱 What This App Does

SmartNotes AI is an **AI-powered Android study assistant** designed for extreme simplicity and ease of use. It strips away complex navigation and authentication, focusing entirely on core educational features. Users can create notes, scan PDFs and images via OCR, generate AI summaries, and create multiple-choice questions (MCQs) for self-assessment — all within a single streamlined application.

---

## 🏗 Architecture: Single-File Simplicity

**Why a Single File?**
To make the project as easy to explain and demonstrate as possible, the entire application has been condensed into a single file: `MainActivity.kt`.

Despite being in one file, it logically follows a simplified **MVVM (Model-View-ViewModel)** structure:
- **Model** (Room Database): Handles local persistence with the `Note` entity, `NoteDao`, and `NoteDatabase`.
- **ViewModel** (`MainViewModel`): Manages the state and business logic, including database interactions, ML Kit text extraction, and Gemini AI prompt generation.
- **View** (`MainApp` Composable): Manages the UI using a simple `enum class Screen` for navigation state instead of complex routing libraries.

```text
User → View (MainApp) → ViewModel (MainViewModel) → Model (Room DB / Gemini API / ML Kit)
```

---

## 🔧 Technologies Used & Why

### 1. Jetpack Compose (UI Framework)
**Why:** Modern declarative UI toolkit that eliminates XML layouts. `MainActivity.kt` uses simple `@Composable` functions (`MainApp`, `Scaffold`, `LazyColumn`) to dynamically switch between screens based on the `currentScreen` state.

### 2. Room Database (Local Storage)
**Why:** Notes need to persist even when the app is closed. Room provides type-safe SQLite access. The `Note` data class and database singletons are defined inline for immediate reference.

### 3. Google Generative AI SDK (Gemini 1.5 Flash)
**Why:** Analyzes text to provide intelligent summaries and multiple-choice questions. Integrated directly into the `MainViewModel`.

### 4. Google ML Kit (OCR Text Recognition)
**Why:** Enables the "Scanner" feature. It uses `PdfRenderer` for PDFs and `InputImage` for pictures to extract plain text on-device without requiring an internet connection for the extraction phase.

---

## 💾 Data Flow: How the App Functions

### Scanning a Document (PDF/Image)
```text
User clicks "Scan Document" → Selects Image or PDF
    → ML Kit TextRecognition extracts text from the document
    → Text is displayed in the "Extracted Text" box
    → User can choose to "Generate Summary" or "Generate MCQs"
    → User saves the text/AI output as a new Note
```

### AI Generation
```text
User selects "Generate Summary" or "Generate MCQs"
    → MainViewModel sends the text to Gemini API
    → Gemini returns structured text
    → Response is instantly visible on the screen
```

---

## 🚀 Features Summary

1. **Notes Management**: View, add, and delete text notes instantly.
2. **Document Scanner**: Upload an image or PDF and extract text using OCR.
3. **AI Summarizer**: Condense long notes or scanned documents into brief summaries.
4. **MCQ Generator**: Automatically create 5-question multiple choice quizzes to test knowledge based on note content.

---

## 🛠 How to Run

1. Open project in **Android Studio**
2. Sync Gradle (File → Sync Project with Gradle Files)
3. Connect a physical device or start an emulator (min API 26 / Android 8.0)
4. Click **Run ▶**
5. Start adding notes and scanning documents immediately!
