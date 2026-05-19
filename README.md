# SmartNotes AI – Notes Summarizer & Study Assistant

## App Features

| Feature | Description |
|---------|-------------|
| **Notes Management** | Create, view, and delete study notes stored locally on the device |
| **Document Scanner** | Upload images or PDFs and extract text using OCR (Optical Character Recognition) |
| **AI Summary Generator** | Automatically generate concise summaries from notes or scanned documents using Gemini AI |
| **AI MCQ Generator** | Create 5 multiple-choice questions with answers from any text for self-assessment |

---

## Concepts Used

### 1. Jetpack Compose

**Definition:** Jetpack Compose is Android's modern declarative UI toolkit. Instead of writing XML layout files, you write UI directly in Kotlin using `@Composable` functions.

**Why we used it:** It eliminates XML boilerplate, makes UI reactive (auto-updates when data changes), and keeps everything in one Kotlin file.

**Code example from our app:**
```kotlin
@Composable
fun MainApp() {
    var currentScreen by remember { mutableStateOf(Screen.NoteList) }

    when (currentScreen) {
        Screen.NoteList -> { /* Show notes list UI */ }
        Screen.AddNote -> { /* Show add note form */ }
        Screen.DocumentScanner -> { /* Show scanner UI */ }
        Screen.NoteDetail -> { /* Show note details */ }
    }
}
```
**What it does:** `MainApp()` is the root composable that switches between different screens based on the `currentScreen` state variable. When `currentScreen` changes, Compose automatically redraws the correct screen.

---

### 2. Room Database (Local Storage)

**Definition:** Room is an abstraction layer over SQLite that provides compile-time verified SQL queries and easy database access using Kotlin annotations.

**Why we used it:** Notes need to be saved permanently on the device so they persist even after the app is closed or the phone restarts.

**Code example from our app:**
```kotlin
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String = "",
    val content: String = "",
    val summary: String = "",
    val mcqs: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun getAllNotes(): Flow<List<Note>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note): Long

    @Delete
    suspend fun deleteNote(note: Note)
}
```
**What it does:**
- `@Entity` — Defines the database table structure. Each `Note` object becomes a row in the `notes` table.
- `@Dao` — Defines the database operations (insert, delete, query). Room auto-generates the SQL implementation at compile time.
- `Flow<List<Note>>` — Returns a reactive stream. Whenever a note is added or deleted, the UI automatically updates.

---

### 3. ViewModel (MVVM Architecture)

**Definition:** ViewModel is a class that stores and manages UI-related data. It survives configuration changes like screen rotations, so data is not lost.

**Why we used it:** It separates business logic (database calls, API calls) from the UI code, keeping the architecture clean and testable.

**Code example from our app:**
```kotlin
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val noteDao = NoteDatabase.getDatabase(application).noteDao()
    val allNotes = noteDao.getAllNotes()

    fun saveNote(title: String, content: String) {
        viewModelScope.launch {
            noteDao.insertNote(Note(title = title, content = content))
        }
    }

    fun deleteNote(note: Note) {
        viewModelScope.launch { noteDao.deleteNote(note) }
    }
}
```
**What it does:** The `MainViewModel` holds references to the database and AI model. The UI calls `viewModel.saveNote()` or `viewModel.deleteNote()` — the ViewModel handles the actual work in the background.

---

### 4. Kotlin Coroutines & StateFlow

**Definition:** Coroutines are lightweight threads for running asynchronous code. StateFlow is a reactive data holder that emits updates to collectors (the UI).

**Why we used it:** Database queries and API calls are slow operations. Running them on the main thread would freeze the app. Coroutines run them in the background, and StateFlow notifies the UI when results are ready.

**Code example from our app:**
```kotlin
private val _isProcessing = MutableStateFlow(false)
val isProcessing: StateFlow<Boolean> = _isProcessing

fun generateSummary(text: String) {
    viewModelScope.launch {       // Runs in background
        _isProcessing.value = true
        val response = generativeModel.generateContent("Summarize:\n$text")
        _summary.value = response.text ?: "Error"
        _isProcessing.value = false
    }
}

// In UI:
val isProcessing by viewModel.isProcessing.collectAsState()
if (isProcessing) {
    CircularProgressIndicator()   // Shows spinner while processing
}
```
**What it does:** `viewModelScope.launch` starts background work. `_isProcessing` is set to `true` so the UI shows a loading spinner. When the AI finishes, `_isProcessing` becomes `false` and the spinner disappears automatically.

---

### 5. Google Generative AI SDK (Gemini API)

**Definition:** Google's Generative AI SDK allows Android apps to send text prompts to Google's Gemini large language model and receive AI-generated responses.

**Why we used it:** To provide intelligent AI features — generating study summaries and MCQ quizzes from user notes without manual effort.

**Code example from our app:**
```kotlin
private val generativeModel = GenerativeModel(
    modelName = "gemini-1.5-flash",
    apiKey = "YOUR_API_KEY"
)

fun generateSummary(text: String) {
    viewModelScope.launch {
        val response = generativeModel.generateContent("Summarize the following text:\n$text")
        _summary.value = response.text ?: "Could not generate summary."
    }
}

fun generateMCQs(text: String) {
    viewModelScope.launch {
        val response = generativeModel.generateContent(
            "Generate 5 multiple-choice questions with answers from the following text:\n$text"
        )
        _mcqs.value = response.text ?: "Could not generate MCQs."
    }
}
```
**What it does:** Creates a `GenerativeModel` instance connected to Gemini 1.5 Flash. When the user clicks "Generate Summary" or "Generate MCQs", the app sends the note text as a prompt to Gemini and displays the AI response.

---

### 6. Google ML Kit (OCR Text Recognition)

**Definition:** ML Kit is Google's on-device machine learning SDK. The Text Recognition API extracts text from images using OCR (Optical Character Recognition).

**Why we used it:** To let users upload photos of handwritten notes or printed pages and automatically extract the text — no manual typing needed.

**Code example from our app:**
```kotlin
private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

fun processImage(context: Context, uri: Uri) {
    viewModelScope.launch {
        val image = InputImage.fromFilePath(context, uri)
        val result = textRecognizer.process(image).await()
        _extractedText.value = result.text
    }
}
```
**What it does:** Takes an image URI, creates an `InputImage`, and passes it to ML Kit's text recognizer. ML Kit scans the image on-device (no internet needed for OCR) and returns the extracted text.

---

### 7. PdfRenderer (PDF Processing)

**Definition:** `PdfRenderer` is an Android API that renders PDF document pages as Bitmap images.

**Why we used it:** ML Kit only accepts images, not PDFs. So we first convert each PDF page to a Bitmap using PdfRenderer, then pass it to ML Kit for OCR.

**Code example from our app:**
```kotlin
fun processPdf(context: Context, uri: Uri) {
    viewModelScope.launch {
        val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
        val renderer = PdfRenderer(fileDescriptor!!)
        val textBuilder = StringBuilder()
        for (i in 0 until renderer.pageCount) {
            val page = renderer.openPage(i)
            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = textRecognizer.process(image).await()
            textBuilder.append(result.text)
        }
        _extractedText.value = textBuilder.toString()
    }
}
```
**What it does:** Opens a PDF file, loops through every page, renders each page as a Bitmap image, then passes each Bitmap to ML Kit for text extraction. All extracted text is combined into one string.

---

### 8. Activity Result API (File Picker)

**Definition:** The Activity Result API is the modern way to launch system activities (like file pickers or cameras) and receive their results.

**Why we used it:** Users need to pick images and PDFs from their device storage to scan them.

**Code example from our app:**
```kotlin
val imageLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent()
) { uri: Uri? ->
    uri?.let { viewModel.processImage(context, it) }
}

// When user clicks the button:
Button(onClick = { imageLauncher.launch("image/*") }) {
    Text("Upload Image")
}
```
**What it does:** `rememberLauncherForActivityResult` registers a file picker. When the user clicks "Upload Image", it opens the system file browser filtered to show only images. When the user selects a file, the callback receives the file URI and passes it to the ViewModel for OCR processing.

---

### 9. Scaffold, TopAppBar, FloatingActionButton (Material 3 Components)

**Definition:** Material 3 components are pre-built UI elements following Google's Material Design guidelines.

**Why we used it:** They provide a professional, consistent look with minimal code — app bars, floating buttons, cards, text fields, and loading indicators.

**Code example from our app:**
```kotlin
Scaffold(
    topBar = { TopAppBar(title = { Text("AiNote") }) },
    floatingActionButton = {
        FloatingActionButton(onClick = { currentScreen = Screen.AddNote }) {
            Icon(Icons.Default.Add, "Add Note")
        }
    }
) { padding ->
    LazyColumn(modifier = Modifier.padding(padding)) {
        items(notes) { note ->
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Text(text = note.title, fontWeight = FontWeight.Bold)
            }
        }
    }
}
```
**What it does:** `Scaffold` creates the screen layout with a top bar and floating action button. `LazyColumn` efficiently displays a scrollable list of note cards — it only renders items visible on screen (like RecyclerView).

---

## How to Run

1. Open project in **Android Studio**
2. Sync Gradle (File → Sync Project with Gradle Files)
3. Connect a device or start an emulator (min API 26 / Android 8.0)
4. Click **Run ▶**

> **Note:** This is an Android project. It cannot be compiled with `kotlinc` directly — it requires the Android SDK and must be built through Android Studio or Gradle.

---

## Dependencies

| Library | Purpose |
|---------|---------|
| `androidx.compose.*` | Jetpack Compose UI framework |
| `androidx.room:room-*` | Local SQLite database |
| `com.google.ai.client.generativeai` | Gemini AI API for summaries and MCQs |
| `com.google.mlkit:text-recognition` | On-device OCR text extraction |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | ViewModel integration with Compose |
| `kotlinx-coroutines-android` | Background task management |
