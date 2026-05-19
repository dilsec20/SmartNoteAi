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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// ─── Room Database ───

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
        @Volatile private var INSTANCE: NoteDatabase? = null
        fun getDatabase(context: Context): NoteDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, NoteDatabase::class.java, "ainote_db")
                    .fallbackToDestructiveMigration(dropAllTables = true).build().also { INSTANCE = it }
            }
        }
    }
}

// ─── Data Classes ───

data class ChatMsg(val role: String, val text: String)

// ─── ViewModel ───

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val noteDao = NoteDatabase.getDatabase(application).noteDao()
    val allNotes = noteDao.getAllNotes()

    private val generativeModel = GenerativeModel(
        modelName = "gemini-3.1-flash-lite",
        apiKey = "AIzaSyD84LGIuMf1zh2MIjp4sWbl_aOdqrv-TGY"
    )
    private var chatSession = generativeModel.startChat()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val _extractedText = MutableStateFlow("")
    val extractedText: StateFlow<String> = _extractedText
    private val _summary = MutableStateFlow("")
    val summary: StateFlow<String> = _summary
    private val _mcqs = MutableStateFlow("")
    val mcqs: StateFlow<String> = _mcqs
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing
    private val _chatHistory = MutableStateFlow<List<ChatMsg>>(emptyList())
    val chatHistory: StateFlow<List<ChatMsg>> = _chatHistory

    fun saveNote(title: String, content: String, summary: String = "", mcqs: String = "") {
        viewModelScope.launch {
            noteDao.insertNote(Note(title = title, content = content, summary = summary, mcqs = mcqs))
        }
    }

    fun deleteNote(note: Note) { viewModelScope.launch { noteDao.deleteNote(note) } }

    fun processImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val image = InputImage.fromFilePath(context, uri)
                val result = textRecognizer.process(image).await()
                _extractedText.value = result.text
            } catch (e: Exception) { _extractedText.value = "Error: ${e.message}" }
            finally { _isProcessing.value = false }
        }
    }

    fun processPdf(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val fd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "r")
                if (fd != null) {
                    val renderer = PdfRenderer(fd)
                    val sb = StringBuilder()
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        val result = textRecognizer.process(InputImage.fromBitmap(bmp, 0)).await()
                        sb.append(result.text).append("\n")
                    }
                    renderer.close(); fd.close()
                    _extractedText.value = sb.toString()
                }
            } catch (e: Exception) { _extractedText.value = "Error: ${e.message}" }
            finally { _isProcessing.value = false }
        }
    }

    fun generateSummary(text: String) {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                chatSession = generativeModel.startChat()
                val prompt = "Summarize the following text with key bullet points and a study tip:\n$text"
                val response = chatSession.sendMessage(prompt)
                _summary.value = response.text ?: "Could not generate summary."
                _chatHistory.value = listOf(ChatMsg("model", _summary.value))
            } catch (e: Exception) { _summary.value = "Error: ${e.message}" }
            finally { _isProcessing.value = false }
        }
    }

    fun generateMCQs(text: String) {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val response = generativeModel.generateContent("Generate 5 MCQs with answers from:\n$text")
                _mcqs.value = response.text ?: "Could not generate MCQs."
            } catch (e: Exception) { _mcqs.value = "Error: ${e.message}" }
            finally { _isProcessing.value = false }
        }
    }

    fun sendFollowUp(question: String) {
        viewModelScope.launch {
            _chatHistory.value = _chatHistory.value + ChatMsg("user", question)
            _isProcessing.value = true
            try {
                val response = chatSession.sendMessage(content("user") { text(question) })
                _chatHistory.value = _chatHistory.value + ChatMsg("model", response.text ?: "")
            } catch (e: Exception) {
                _chatHistory.value = _chatHistory.value + ChatMsg("model", "Error: ${e.message}")
            } finally { _isProcessing.value = false }
        }
    }

    fun clearExtraction() {
        _extractedText.value = ""; _summary.value = ""; _mcqs.value = ""
        _chatHistory.value = emptyList()
        chatSession = generativeModel.startChat()
    }
}

// ─── UI Colors ───

val Primary = Color(0xFF6C63FF)
val PrimaryDark = Color(0xFF4A42D1)
val Surface = Color(0xFFF7F7FC)
val CardBg = Color(0xFFFFFFFF)
val AccentGreen = Color(0xFF00C853)
val AccentOrange = Color(0xFFFF6D00)

// ─── Activity ───

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize(), color = Surface) { MainApp() } } }
    }
}

enum class Screen { NoteList, AddNote, Scanner, NoteDetail }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    var screen by remember { mutableStateOf(Screen.NoteList) }
    var selectedNote by remember { mutableStateOf<Note?>(null) }
    val vm: MainViewModel = viewModel()

    when (screen) {
        Screen.NoteList -> NoteListScreen(vm) { s, n -> selectedNote = n; screen = s }
        Screen.AddNote -> AddNoteScreen(vm) { screen = Screen.NoteList }
        Screen.Scanner -> ScannerScreen(vm) { screen = Screen.NoteList }
        Screen.NoteDetail -> NoteDetailScreen(vm, selectedNote) { screen = Screen.NoteList }
    }
}

// ─── Note List ───

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(vm: MainViewModel, navigate: (Screen, Note?) -> Unit) {
    val notes by vm.allNotes.collectAsState(initial = emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SmartNotes AI", fontWeight = FontWeight.Bold, fontSize = 22.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Primary, titleContentColor = Color.White)
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(onClick = { vm.clearExtraction(); navigate(Screen.Scanner, null) },
                    containerColor = AccentOrange, contentColor = Color.White, modifier = Modifier.padding(bottom = 12.dp)) {
                    Icon(Icons.Default.DocumentScanner, "Scan")
                }
                FloatingActionButton(onClick = { navigate(Screen.AddNote, null) },
                    containerColor = Primary, contentColor = Color.White) {
                    Icon(Icons.Default.Add, "Add")
                }
            }
        }
    ) { pad ->
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.NoteAdd, null, Modifier.size(64.dp), tint = Primary.copy(0.4f))
                    Spacer(Modifier.height(12.dp))
                    Text("No notes yet", color = Color.Gray, fontSize = 16.sp)
                    Text("Tap + to create one", color = Color.Gray, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(notes) { note ->
                    Card(
                        Modifier.fillMaxWidth().clickable { vm.clearExtraction(); navigate(Screen.NoteDetail, note) },
                        shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(2.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(note.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(note.content, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.Gray, fontSize = 13.sp)
                            if (note.summary.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(14.dp), tint = Primary)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Has AI Summary", fontSize = 11.sp, color = Primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Add Note ───

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNoteScreen(vm: MainViewModel, goBack: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(title = { Text("New Note") }, navigationIcon = {
            IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = {
            IconButton(onClick = { if (title.isNotBlank()) { vm.saveNote(title, content); goBack() } }) {
                Icon(Icons.Default.Check, "Save", tint = AccentGreen)
            }
        })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") },
                shape = RoundedCornerShape(12.dp), singleLine = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth().weight(1f),
                label = { Text("Write your notes here...") }, shape = RoundedCornerShape(12.dp))
        }
    }
}

// ─── Scanner ───

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(vm: MainViewModel, goBack: () -> Unit) {
    val ctx = LocalContext.current
    val extracted by vm.extractedText.collectAsState()
    val summary by vm.summary.collectAsState()
    val mcqs by vm.mcqs.collectAsState()
    val processing by vm.isProcessing.collectAsState()
    val chat by vm.chatHistory.collectAsState()
    var title by remember { mutableStateOf("") }
    var followUp by remember { mutableStateOf("") }

    val imgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { vm.processImage(ctx, it) } }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { vm.processPdf(ctx, it) } }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Document Scanner") },
            navigationIcon = { IconButton(onClick = { vm.clearExtraction(); goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (extracted.isNotBlank()) {
                    IconButton(onClick = {
                        vm.saveNote(title.ifBlank { "Scanned Doc" }, extracted, summary, mcqs); vm.clearExtraction(); goBack()
                    }) { Icon(Icons.Default.Save, "Save", tint = AccentGreen) }
                }
            })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { imgLauncher.launch("image/*") }, Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                    Icon(Icons.Default.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Image")
                }
                Button(onClick = { pdfLauncher.launch("application/pdf") }, Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = AccentOrange)) {
                    Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("PDF")
                }
            }
            if (processing) { Spacer(Modifier.height(16.dp)); LinearProgressIndicator(Modifier.fillMaxWidth(), color = Primary) }
            if (extracted.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") },
                    shape = RoundedCornerShape(12.dp), singleLine = true)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.generateSummary(extracted) }, Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Summary")
                    }
                    OutlinedButton(onClick = { vm.generateMCQs(extracted) }, Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.Quiz, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("MCQs")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chat.isNotEmpty()) {
                    items(chat) { msg -> ChatBubble(msg) }
                } else {
                    if (summary.isNotBlank()) { item { AIResultCard("Summary", summary) } }
                    if (mcqs.isNotBlank()) { item { AIResultCard("MCQs", mcqs) } }
                }
                if (extracted.isNotBlank() && summary.isBlank() && mcqs.isBlank()) {
                    item { AIResultCard("Extracted Text", extracted) }
                }
            }
            if (summary.isNotBlank() || chat.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(followUp, { followUp = it }, Modifier.weight(1f),
                        placeholder = { Text("Ask a follow-up question...") }, shape = RoundedCornerShape(24.dp), singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(onClick = { if (followUp.isNotBlank()) { vm.sendFollowUp(followUp); followUp = "" } },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Primary)) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
}

// ─── Note Detail ───

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(vm: MainViewModel, note: Note?, goBack: () -> Unit) {
    if (note == null) { goBack(); return }
    val summary by vm.summary.collectAsState()
    val mcqs by vm.mcqs.collectAsState()
    val processing by vm.isProcessing.collectAsState()
    val chat by vm.chatHistory.collectAsState()
    var followUp by remember { mutableStateOf("") }

    Scaffold(topBar = {
        TopAppBar(title = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { vm.deleteNote(note); goBack() }) { Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFE53935)) } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.generateSummary(note.content) }, Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Summary")
                }
                Button(onClick = { vm.generateMCQs(note.content) }, Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = AccentOrange)) {
                    Icon(Icons.Default.Quiz, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("MCQs")
                }
            }
            if (processing) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth(), color = Primary) }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chat.isNotEmpty()) {
                    items(chat) { msg -> ChatBubble(msg) }
                } else {
                    if (summary.isNotBlank()) { item { AIResultCard("AI Summary", summary) } }
                    else if (note.summary.isNotBlank()) { item { AIResultCard("Saved Summary", note.summary) } }
                    if (mcqs.isNotBlank()) { item { AIResultCard("MCQs", mcqs) } }
                    else if (note.mcqs.isNotBlank()) { item { AIResultCard("Saved MCQs", note.mcqs) } }
                }
                item { AIResultCard("Content", note.content) }
            }
            if (summary.isNotBlank() || chat.isNotEmpty() || note.summary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(followUp, { followUp = it }, Modifier.weight(1f),
                        placeholder = { Text("Ask a follow-up question...") }, shape = RoundedCornerShape(24.dp), singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(onClick = { if (followUp.isNotBlank()) { vm.sendFollowUp(followUp); followUp = "" } },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Primary)) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
}

// ─── Shared UI Components ───

@Composable
fun AIResultCard(label: String, text: String) {
    Card(shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(1.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Primary)
            Spacer(Modifier.height(6.dp))
            Text(text, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMsg) {
    val isUser = msg.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isUser) Primary else Color(0xFFE8E8F0))
                .padding(12.dp)
        ) {
            Text(msg.text, color = if (isUser) Color.White else Color.Black, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}