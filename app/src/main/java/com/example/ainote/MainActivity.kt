package com.example.ainote

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import com.google.ai.client.generativeai.GenerativeModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// --- Room Database ---

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

@Database(entities = [Note::class], version = 3, exportSchema = false)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var INSTANCE: NoteDatabase? = null
        fun getDatabase(context: Context): NoteDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NoteDatabase::class.java,
                    "ainote_db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// --- ViewModel ---

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val noteDao = NoteDatabase.getDatabase(application).noteDao()
    val allNotes = noteDao.getAllNotes()

    private val generativeModel = GenerativeModel(
        modelName = "gemini-3.1-flash-lite",
        apiKey = "AIzaSyD84LGIuMf1zh2MIjp4sWbl_aOdqrv-TGY"
    )

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val _extractedText = MutableStateFlow("")
    val extractedText: StateFlow<String> = _extractedText

    private val _summary = MutableStateFlow("")
    val summary: StateFlow<String> = _summary

    private val _mcqs = MutableStateFlow("")
    val mcqs: StateFlow<String> = _mcqs

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    fun saveNote(title: String, content: String, summary: String = "", mcqs: String = "") {
        viewModelScope.launch {
            noteDao.insertNote(Note(title = title, content = content, summary = summary, mcqs = mcqs))
        }
    }

    fun deleteNote(note: Note) {
        viewModelScope.launch { noteDao.deleteNote(note) }
    }

    fun processImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val image = InputImage.fromFilePath(context, uri)
                val result = textRecognizer.process(image).await()
                _extractedText.value = result.text
            } catch (e: Exception) {
                _extractedText.value = "Error extracting text: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun processPdf(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val fileDescriptor: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "r")
                if (fileDescriptor != null) {
                    val renderer = PdfRenderer(fileDescriptor)
                    val textBuilder = java.lang.StringBuilder()
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        
                        val image = InputImage.fromBitmap(bitmap, 0)
                        val result = textRecognizer.process(image).await()
                        textBuilder.append(result.text).append("\n")
                    }
                    renderer.close()
                    fileDescriptor.close()
                    _extractedText.value = textBuilder.toString()
                }
            } catch (e: Exception) {
                _extractedText.value = "Error reading PDF: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun generateSummary(text: String) {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val response = generativeModel.generateContent("Summarize the following text:\n$text")
                _summary.value = response.text ?: "Could not generate summary."
            } catch (e: Exception) {
                _summary.value = "Error: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun generateMCQs(text: String) {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val response = generativeModel.generateContent("Generate 5 multiple-choice questions with answers from the following text:\n$text")
                _mcqs.value = response.text ?: "Could not generate MCQs."
            } catch (e: Exception) {
                _mcqs.value = "Error: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }
    
    fun clearExtraction() {
        _extractedText.value = ""
        _summary.value = ""
        _mcqs.value = ""
    }
}

// --- UI / Activity ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainApp()
                }
            }
        }
    }
}

enum class Screen {
    NoteList, AddNote, DocumentScanner, NoteDetail
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    var currentScreen by remember { mutableStateOf(Screen.NoteList) }
    var selectedNote by remember { mutableStateOf<Note?>(null) }
    val viewModel: MainViewModel = viewModel()

    when (currentScreen) {
        Screen.NoteList -> {
            val notes by viewModel.allNotes.collectAsState(initial = emptyList())
            Scaffold(
                topBar = { TopAppBar(title = { Text("AiNote") }) },
                floatingActionButton = {
                    Column {
                        FloatingActionButton(onClick = { currentScreen = Screen.DocumentScanner }, modifier = Modifier.padding(bottom = 8.dp)) {
                            Icon(Icons.Default.DocumentScanner, "Scan Document")
                        }
                        FloatingActionButton(onClick = { currentScreen = Screen.AddNote }) {
                            Icon(Icons.Default.Add, "Add Note")
                        }
                    }
                }
            ) { padding ->
                LazyColumn(modifier = Modifier.padding(padding)) {
                    items(notes) { note ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .clickable {
                                    selectedNote = note
                                    viewModel.clearExtraction()
                                    currentScreen = Screen.NoteDetail
                                }
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(text = note.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = note.content, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        Screen.AddNote -> {
            var title by remember { mutableStateOf("") }
            var content by remember { mutableStateOf("") }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Add Note") },
                        navigationIcon = {
                            IconButton(onClick = { currentScreen = Screen.NoteList }) {
                                Icon(Icons.Default.ArrowBack, "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                viewModel.saveNote(title, content)
                                currentScreen = Screen.NoteList
                            }) {
                                Icon(Icons.Default.Check, "Save")
                            }
                        }
                    )
                }
            ) { padding ->
                Column(modifier = Modifier.padding(padding).padding(16.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("Content") },
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
        }
        Screen.DocumentScanner -> {
            val context = LocalContext.current
            val extractedText by viewModel.extractedText.collectAsState()
            val summary by viewModel.summary.collectAsState()
            val mcqs by viewModel.mcqs.collectAsState()
            val isProcessing by viewModel.isProcessing.collectAsState()

            var title by remember { mutableStateOf("") }

            val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                uri?.let { viewModel.processImage(context, it) }
            }
            val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                uri?.let { viewModel.processPdf(context, it) }
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Scan Document") },
                        navigationIcon = {
                            IconButton(onClick = {
                                viewModel.clearExtraction()
                                currentScreen = Screen.NoteList
                            }) {
                                Icon(Icons.Default.ArrowBack, "Back")
                            }
                        },
                        actions = {
                            if (extractedText.isNotBlank()) {
                                IconButton(onClick = {
                                    viewModel.saveNote(
                                        title = title.ifBlank { "Scanned Document" },
                                        content = extractedText,
                                        summary = summary,
                                        mcqs = mcqs
                                    )
                                    viewModel.clearExtraction()
                                    currentScreen = Screen.NoteList
                                }) {
                                    Icon(Icons.Default.Save, "Save Note")
                                }
                            }
                        }
                    )
                }
            ) { padding ->
                Column(modifier = Modifier.padding(padding).padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Button(onClick = { imageLauncher.launch("image/*") }) {
                            Text("Upload Image")
                        }
                        Button(onClick = { pdfLauncher.launch("application/pdf") }) {
                            Text("Upload PDF")
                        }
                    }
                    if (isProcessing) {
                        Spacer(modifier = Modifier.height(16.dp))
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    if (extractedText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Note Title") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Button(onClick = { viewModel.generateSummary(extractedText) }) {
                                Text("Generate Summary")
                            }
                            Button(onClick = { viewModel.generateMCQs(extractedText) }) {
                                Text("Generate MCQs")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (summary.isNotBlank()) {
                            item {
                                Text("Summary:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(summary)
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                        if (mcqs.isNotBlank()) {
                            item {
                                Text("MCQs:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(mcqs)
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                        if (extractedText.isNotBlank()) {
                            item {
                                Text("Extracted Text:", fontWeight = FontWeight.Bold)
                                Text(extractedText)
                            }
                        }
                    }
                }
            }
        }
        Screen.NoteDetail -> {
            val note = selectedNote ?: return
            val summary by viewModel.summary.collectAsState()
            val mcqs by viewModel.mcqs.collectAsState()
            val isProcessing by viewModel.isProcessing.collectAsState()

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(note.title) },
                        navigationIcon = {
                            IconButton(onClick = { currentScreen = Screen.NoteList }) {
                                Icon(Icons.Default.ArrowBack, "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                viewModel.deleteNote(note)
                                currentScreen = Screen.NoteList
                            }) {
                                Icon(Icons.Default.Delete, "Delete")
                            }
                        }
                    )
                }
            ) { padding ->
                Column(modifier = Modifier.padding(padding).padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Button(onClick = { viewModel.generateSummary(note.content) }) {
                            Text("AI Summary")
                        }
                        Button(onClick = { viewModel.generateMCQs(note.content) }) {
                            Text("Generate MCQs")
                        }
                    }
                    if (isProcessing) {
                        Spacer(modifier = Modifier.height(16.dp))
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (summary.isNotBlank() || note.summary.isNotBlank()) {
                            item {
                                Text("Summary:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(summary.ifBlank { note.summary })
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                        if (mcqs.isNotBlank() || note.mcqs.isNotBlank()) {
                            item {
                                Text("MCQs:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(mcqs.ifBlank { note.mcqs })
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                        item {
                            Text("Content:", fontWeight = FontWeight.Bold)
                            Text(note.content)
                        }
                    }
                }
            }
        }
    }
}