package com.example.combotracker

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.*
import coil.compose.AsyncImage
import com.example.combotracker.ui.theme.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.io.InputStreamReader
import java.io.OutputStreamWriter

// ============================================================================
// 1. DATABASE & DATA MODELS
// ============================================================================

data class ComboStep(
    val id: Int = 0,
    val description: String,
    val image1Uri: Uri? = null,
    val image2Uri: Uri? = null,
    val image3Uri: Uri? = null
)

@Entity(tableName = "combos")
data class Combo(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val card1Uri: Uri? = null,
    val card2Uri: Uri? = null,
    val card3Uri: Uri? = null,
    val currentStepIndex: Int = -1,
    val steps: List<ComboStep> = emptyList()
)

// DTOs to safely Export/Import without crashing Gson on complex Uri objects
data class ExportCombo(val name: String, val card1Uri: String?, val card2Uri: String?, val card3Uri: String?, val steps: List<ExportStep>)
data class ExportStep(val description: String, val image1Uri: String?, val image2Uri: String?, val image3Uri: String?)

class Converters {
    private val gson = Gson()
    @TypeConverter fun fromStepsList(steps: List<ComboStep>?): String = gson.toJson(steps)
    @TypeConverter fun toStepsList(stepsString: String?): List<ComboStep> {
        val type = object : TypeToken<List<ComboStep>>() {}.type
        return gson.fromJson(stepsString, type) ?: emptyList()
    }
    @TypeConverter fun fromUri(uri: Uri?): String? = uri?.toString()
    @TypeConverter fun toUri(uriString: String?): Uri? = uriString?.let { Uri.parse(it) }
}

@Dao
interface ComboDao {
    @Query("SELECT * FROM combos") suspend fun getAllCombos(): List<Combo>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCombo(combo: Combo): Long
    @Update suspend fun updateCombo(combo: Combo)
    @Delete suspend fun deleteCombo(combo: Combo)
}

@Database(entities = [Combo::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun comboDao(): ComboDao
}

// ============================================================================
// 2. SETTINGS MANAGER
// ============================================================================

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("combo_settings", Context.MODE_PRIVATE)
    var appColor: Int get() = prefs.getInt("appColor", 0xFFB71C1C.toInt()); set(value) = prefs.edit().putInt("appColor", value).apply()
    var isDarkMode: Boolean get() = prefs.getBoolean("isDarkMode", true); set(value) = prefs.edit().putBoolean("isDarkMode", value).apply()
    var bgImageUri: String? get() = prefs.getString("bgImageUri", null); set(value) = prefs.edit().putString("bgImageUri", value).apply()
    var onlyHighlightCurrent: Boolean get() = prefs.getBoolean("onlyHighlightCurrent", false); set(value) = prefs.edit().putBoolean("onlyHighlightCurrent", value).apply()
}

data class AppThemeColors(val bg: Color, val surface: Color, val text: Color, val accent: Color)

// ============================================================================
// 3. MAIN ACTIVITY & UI ROUTING
// ============================================================================

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "combo-database").fallbackToDestructiveMigration().build()
        settings = SettingsManager(applicationContext)
        setContent { ComboTrackerTheme { ComboApp(db, settings) } }
    }
}

@Composable
fun ComboApp(db: AppDatabase, settings: SettingsManager) {
    val scope = rememberCoroutineScope()
    var currentCombo by remember { mutableStateOf<Combo?>(null) }
    val comboList = remember { mutableStateListOf<Combo>() }

    var appColor by remember { mutableStateOf(Color(settings.appColor)) }
    var bgImageUri by remember { mutableStateOf(settings.bgImageUri?.let { Uri.parse(it) }) }
    var isDarkMode by remember { mutableStateOf(settings.isDarkMode) }
    var onlyHighlightCurrent by remember { mutableStateOf(settings.onlyHighlightCurrent) }

    LaunchedEffect(Unit) {
        val savedCombos = db.comboDao().getAllCombos()
        comboList.addAll(savedCombos)
    }

    val theme = AppThemeColors(
        bg = if (isDarkMode) RedHoodDarkGrey else Color(0xFFF5F5F5),
        surface = if (isDarkMode) RedHoodBlack else Color(0xFFFFFFFF),
        text = if (isDarkMode) TextSilver else Color(0xFF121212),
        accent = appColor
    )

    Box(modifier = Modifier.fillMaxSize().background(theme.bg)) {
        if (bgImageUri != null) {
            AsyncImage(
                model = bgImageUri, contentDescription = "App Background", contentScale = ContentScale.Crop,
                alpha = if (isDarkMode) 0.2f else 0.4f, modifier = Modifier.fillMaxSize()
            )
        }

        if (currentCombo == null) {
            MainMenuScreen(
                comboList = comboList, theme = theme, bgImageUri = bgImageUri, isDarkMode = isDarkMode, onlyHighlightCurrent = onlyHighlightCurrent,
                onNavigateToDetail = { currentCombo = it },
                onAddCombo = { name, uri1, uri2, uri3 ->
                    scope.launch {
                        val newCombo = Combo(name = name, card1Uri = uri1, card2Uri = uri2, card3Uri = uri3)
                        val generatedId = db.comboDao().insertCombo(newCombo).toInt()
                        val finalCombo = newCombo.copy(id = generatedId)
                        comboList.add(finalCombo)
                        currentCombo = finalCombo
                    }
                },
                onUpdateCombo = { updatedCombo ->
                    scope.launch {
                        db.comboDao().updateCombo(updatedCombo)
                        val index = comboList.indexOfFirst { it.id == updatedCombo.id }
                        if (index != -1) comboList[index] = updatedCombo
                    }
                },
                onDeleteCombo = { comboToDelete ->
                    scope.launch {
                        db.comboDao().deleteCombo(comboToDelete)
                        comboList.remove(comboToDelete)
                    }
                },
                onImportCombo = { importedCombo ->
                    scope.launch {
                        val generatedId = db.comboDao().insertCombo(importedCombo).toInt()
                        val finalCombo = importedCombo.copy(id = generatedId)
                        comboList.add(finalCombo)
                    }
                },
                onColorChange = { color -> appColor = color; settings.appColor = color.toArgb() },
                onBgChange = { uri -> bgImageUri = uri; settings.bgImageUri = uri?.toString() },
                onThemeToggle = { dark -> isDarkMode = dark; settings.isDarkMode = dark },
                onHighlightToggle = { onlyCurrent -> onlyHighlightCurrent = onlyCurrent; settings.onlyHighlightCurrent = onlyCurrent }
            )
        } else {
            ComboDetailScreen(
                combo = currentCombo!!, theme = theme, onlyHighlightCurrent = onlyHighlightCurrent,
                onSaveCombo = { savedCombo ->
                    scope.launch {
                        db.comboDao().updateCombo(savedCombo)
                        val index = comboList.indexOfFirst { it.id == savedCombo.id }
                        if (index != -1) comboList[index] = savedCombo
                    }
                },
                onBackClicked = { currentCombo = null }
            )
        }
    }
}

// ============================================================================
// 4. MAIN MENU SCREEN
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuScreen(
    comboList: List<Combo>, theme: AppThemeColors, bgImageUri: Uri?, isDarkMode: Boolean, onlyHighlightCurrent: Boolean,
    onNavigateToDetail: (Combo) -> Unit, onAddCombo: (String, Uri?, Uri?, Uri?) -> Unit, onUpdateCombo: (Combo) -> Unit, onDeleteCombo: (Combo) -> Unit,
    onImportCombo: (Combo) -> Unit, onColorChange: (Color) -> Unit, onBgChange: (Uri?) -> Unit, onThemeToggle: (Boolean) -> Unit, onHighlightToggle: (Boolean) -> Unit
) {
    var comboToEdit by remember { mutableStateOf<Combo?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var comboToExport by remember { mutableStateOf<Combo?>(null) }
    val context = LocalContext.current

    // SAFE EXPORT LOGIC
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { destUri ->
            comboToExport?.let { combo ->
                try {
                    context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
                        OutputStreamWriter(outputStream).use { writer ->
                            val exportData = ExportCombo(
                                name = combo.name, card1Uri = combo.card1Uri?.toString(), card2Uri = combo.card2Uri?.toString(), card3Uri = combo.card3Uri?.toString(),
                                steps = combo.steps.map { ExportStep(it.description, it.image1Uri?.toString(), it.image2Uri?.toString(), it.image3Uri?.toString()) }
                            )
                            writer.write(Gson().toJson(exportData))
                        }
                    }
                    Toast.makeText(context, "Combo exported successfully!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { Toast.makeText(context, "Export failed.", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // SAFE IMPORT LOGIC
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { srcUri ->
            try {
                context.contentResolver.openInputStream(srcUri)?.use { inputStream ->
                    InputStreamReader(inputStream).use { reader ->
                        val importedData = Gson().fromJson(reader, ExportCombo::class.java)
                        if (importedData.name == null) throw Exception("Invalid format")

                        val newCombo = Combo(
                            id = 0, name = importedData.name,
                            card1Uri = importedData.card1Uri?.let { Uri.parse(it) }, card2Uri = importedData.card2Uri?.let { Uri.parse(it) }, card3Uri = importedData.card3Uri?.let { Uri.parse(it) },
                            currentStepIndex = -1,
                            steps = importedData.steps?.map {
                                ComboStep(
                                    description = it.description ?: "",
                                    image1Uri = it.image1Uri?.let { uriStr -> Uri.parse(uriStr) },
                                    image2Uri = it.image2Uri?.let { uriStr -> Uri.parse(uriStr) },
                                    image3Uri = it.image3Uri?.let { uriStr -> Uri.parse(uriStr) }
                                )
                            } ?: emptyList()
                        )
                        onImportCombo(newCombo)
                        Toast.makeText(context, "Combo imported!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) { Toast.makeText(context, "Invalid .cbt file", Toast.LENGTH_SHORT).show() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Combo Tracker", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = theme.accent.copy(alpha = 0.9f)),
                actions = { IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Settings", tint = Color.White) } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { comboToEdit = Combo(id = 0, name = "") }, containerColor = theme.accent) { Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White) }
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (comboList.isEmpty()) {
                Text("Start writing your combo", color = theme.text.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(comboList) { combo ->
                        ComboListItem(
                            combo = combo, theme = theme,
                            onClick = { onNavigateToDetail(combo) }, onEditClick = { comboToEdit = combo }, onDeleteClick = { onDeleteCombo(combo) },
                            onExportClick = { comboToExport = combo; exportLauncher.launch("${combo.name.replace(" ", "_")}.cbt") }
                        )
                    }
                }
            }
        }

        comboToEdit?.let { combo ->
            ComboInfoDialog(
                initialCombo = combo, theme = theme, onDismiss = { comboToEdit = null },
                onStart = { name, uri1, uri2, uri3 ->
                    if (combo.id == 0) onAddCombo(name, uri1, uri2, uri3) else onUpdateCombo(combo.copy(name = name, card1Uri = uri1, card2Uri = uri2, card3Uri = uri3))
                    comboToEdit = null
                },
                onImportRequest = { comboToEdit = null; importLauncher.launch(arrayOf("*/*")) }
            )
        }

        if (showSettings) {
            SettingsDialog(theme = theme, currentBgUri = bgImageUri, isDarkMode = isDarkMode, onlyHighlightCurrent = onlyHighlightCurrent, onDismiss = { showSettings = false }, onColorChange = onColorChange, onBgChange = onBgChange, onThemeToggle = onThemeToggle, onHighlightToggle = onHighlightToggle)
        }
    }
}

// ============================================================================
// 5. SHARED COMPONENTS & COMBO LIST ITEM
// ============================================================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComboListItem(combo: Combo, theme: AppThemeColors, onClick: () -> Unit, onEditClick: () -> Unit, onDeleteClick: () -> Unit, onExportClick: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).combinedClickable(onClick = onClick, onLongClick = { showMenu = true }),
        colors = CardDefaults.cardColors(containerColor = theme.surface.copy(alpha = 0.65f)), border = BorderStroke(1.dp, theme.accent.copy(alpha = 0.5f))
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (combo.card1Uri != null) MiniCard(uri = combo.card1Uri, theme = theme)
                if (combo.card2Uri != null) MiniCard(uri = combo.card2Uri, theme = theme)
                if (combo.card3Uri != null) MiniCard(uri = combo.card3Uri, theme = theme)
            }
            Spacer(modifier = Modifier.width(16.dp))

            Text(combo.name, color = theme.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))

            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = theme.text) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(theme.surface)) {
                    DropdownMenuItem(text = { Text("Edit", color = theme.text) }, onClick = { onEditClick(); showMenu = false })
                    DropdownMenuItem(text = { Text("Copy to Clipboard", color = theme.text) }, onClick = {
                        // Copies Title + Steps!
                        val copyText = "${combo.name}\n" + combo.steps.mapIndexed { index, step -> "${index + 1}. ${step.description}" }.joinToString("\n")
                        clipboardManager.setText(AnnotatedString(copyText))
                        Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                        showMenu = false
                    })
                    DropdownMenuItem(text = { Text("Export (.cbt)", color = theme.text) }, onClick = { onExportClick(); showMenu = false })
                    DropdownMenuItem(text = { Text("Delete", color = theme.accent) }, onClick = { onDeleteClick(); showMenu = false })
                }
            }
        }
    }
}

@Composable
fun MiniCard(uri: Uri?, theme: AppThemeColors) {
    Box(modifier = Modifier.height(48.dp).aspectRatio(59f / 86f).clip(RoundedCornerShape(4.dp)).background(theme.bg).border(1.dp, theme.text.copy(alpha = 0.3f), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
        if (uri != null) AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

// ============================================================================
// 6. NEW COMBO / IMPORT DIALOG
// ============================================================================
@Composable
fun ComboInfoDialog(initialCombo: Combo, theme: AppThemeColors, onDismiss: () -> Unit, onStart: (String, Uri?, Uri?, Uri?) -> Unit, onImportRequest: () -> Unit) {
    var comboName by remember { mutableStateOf(initialCombo.name) }
    var card1Uri by remember { mutableStateOf(initialCombo.card1Uri) }
    var card2Uri by remember { mutableStateOf(initialCombo.card2Uri) }
    var card3Uri by remember { mutableStateOf(initialCombo.card3Uri) }
    val context = LocalContext.current

    val p1 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); card1Uri = uri } }
    val p2 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); card2Uri = uri } }
    val p3 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); card3Uri = uri } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = theme.surface.copy(alpha = 0.95f), border = BorderStroke(1.dp, theme.accent)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (initialCombo.id == 0) "New Combo" else "Edit Combo", color = theme.text, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(value = comboName, onValueChange = { comboName = it }, label = { Text("Combo Name", color = theme.text.copy(alpha = 0.7f)) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = theme.text, unfocusedTextColor = theme.text, focusedBorderColor = theme.accent, unfocusedBorderColor = theme.text.copy(alpha = 0.5f)), singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))

                Text(text = "Starter Cards", color = theme.text.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CardPlaceholder(imageUri = card1Uri, theme = theme, onClick = { p1.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f))
                    CardPlaceholder(imageUri = card2Uri, theme = theme, onClick = { p2.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f))
                    CardPlaceholder(imageUri = card3Uri, theme = theme, onClick = { p3.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(24.dp))

                Button(onClick = { onStart(if (comboName.isBlank()) "Unnamed" else comboName, card1Uri, card2Uri, card3Uri) }, colors = ButtonDefaults.buttonColors(containerColor = theme.accent), modifier = Modifier.fillMaxWidth()) { Text(if (initialCombo.id == 0) "Start" else "Save & Continue", color = Color.White) }

                if (initialCombo.id == 0) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = theme.text.copy(alpha = 0.3f))
                        Text("   or   ", color = theme.text.copy(alpha = 0.5f))
                        HorizontalDivider(modifier = Modifier.weight(1f), color = theme.text.copy(alpha = 0.3f))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = onImportRequest, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, theme.accent)) { Text("Import Combo (.cbt)", color = theme.accent) }
                }
            }
        }
    }
}

@Composable
fun CardPlaceholder(imageUri: Uri?, theme: AppThemeColors, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.aspectRatio(59f / 86f).clip(RoundedCornerShape(8.dp)).background(theme.bg.copy(alpha = 0.5f)).border(1.dp, theme.text.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).clickable { onClick() }, contentAlignment = Alignment.Center) {
        if (imageUri != null) AsyncImage(model = imageUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Default.Add, contentDescription = "Add Card", tint = theme.text)
    }
}

// ============================================================================
// 7. COMBO DETAIL SCREEN (With Automated Saving)
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComboDetailScreen(combo: Combo, theme: AppThemeColors, onlyHighlightCurrent: Boolean, onSaveCombo: (Combo) -> Unit, onBackClicked: () -> Unit) {
    var draftCombo by remember { mutableStateOf(combo) }
    var stepToEdit by remember { mutableStateOf<ComboStep?>(null) }
    val listState = rememberLazyListState()

    // Auto-Scrolls beautifully when highlight changes
    LaunchedEffect(draftCombo.currentStepIndex) {
        if (draftCombo.currentStepIndex in draftCombo.steps.indices) {
            listState.animateScrollToItem(draftCombo.currentStepIndex)
        }
    }

    // Auto-Saves and resets highlight when app goes to background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                draftCombo = draftCombo.copy(currentStepIndex = -1) // Reset highlight
                onSaveCombo(draftCombo)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(draftCombo.name, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = theme.accent.copy(alpha = 0.9f)),
                navigationIcon = {
                    IconButton(onClick = {
                        val resetCombo = draftCombo.copy(currentStepIndex = -1) // Reset highlight
                        onSaveCombo(resetCombo) // Auto-save on back!
                        onBackClicked()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
                },
                actions = {
                    IconButton(onClick = {
                        val nextIndex = if (draftCombo.currentStepIndex == -1) 0 else draftCombo.currentStepIndex + 1
                        if (nextIndex < draftCombo.steps.size) draftCombo = draftCombo.copy(currentStepIndex = nextIndex)
                        else if (draftCombo.steps.isNotEmpty()) draftCombo = draftCombo.copy(currentStepIndex = 0)
                    }) { Icon(Icons.Default.Check, contentDescription = "Highlight Next", tint = Color.White) }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { stepToEdit = ComboStep(id = 0, description = "") }, containerColor = theme.accent) {
                Icon(Icons.Default.Add, contentDescription = "Add Step", tint = Color.White)
            }
        },
        containerColor = Color.Transparent
        // BottomBar REMOVED for fully automated saving!
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                itemsIndexed(draftCombo.steps) { index, step ->
                    val isHighlighted = if (onlyHighlightCurrent) index == draftCombo.currentStepIndex else draftCombo.currentStepIndex != -1 && index <= draftCombo.currentStepIndex
                    StepListItem(
                        orderNumber = index + 1, step = step, theme = theme, isHighlighted = isHighlighted,
                        onClick = { stepToEdit = step },
                        onMarkCurrent = { draftCombo = draftCombo.copy(currentStepIndex = index) },
                        onDeleteStep = {
                            val updatedSteps = draftCombo.steps.toMutableList().apply { removeAt(index) }
                            val newCurrentIndex = if (draftCombo.currentStepIndex >= index && draftCombo.currentStepIndex > -1) draftCombo.currentStepIndex - 1 else draftCombo.currentStepIndex
                            draftCombo = draftCombo.copy(steps = updatedSteps, currentStepIndex = newCurrentIndex)
                        }
                    )
                }
            }
        }

        stepToEdit?.let { currentStep ->
            StepInputDialog(
                initialStep = currentStep, theme = theme, onDismiss = { stepToEdit = null },
                onNextStep = { newText, uri1, uri2, uri3 ->
                    if (newText.isNotBlank() || uri1 != null || uri2 != null || uri3 != null) {
                        draftCombo = draftCombo.copy(steps = draftCombo.steps + currentStep.copy(id = draftCombo.steps.size + 1, description = newText, image1Uri = uri1, image2Uri = uri2, image3Uri = uri3))
                    }
                },
                onFinish = { newText, uri1, uri2, uri3 ->
                    if (newText.isNotBlank() || uri1 != null || uri2 != null || uri3 != null) {
                        val updatedSteps = if (currentStep.id == 0) draftCombo.steps + currentStep.copy(id = draftCombo.steps.size + 1, description = newText, image1Uri = uri1, image2Uri = uri2, image3Uri = uri3)
                        else draftCombo.steps.map { if (it.id == currentStep.id) it.copy(description = newText, image1Uri = uri1, image2Uri = uri2, image3Uri = uri3) else it }
                        draftCombo = draftCombo.copy(steps = updatedSteps)
                    }
                    stepToEdit = null
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StepListItem(orderNumber: Int, step: ComboStep, theme: AppThemeColors, isHighlighted: Boolean, onClick: () -> Unit, onMarkCurrent: () -> Unit, onDeleteStep: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val bgColor = if (isHighlighted) theme.accent.copy(alpha = 0.2f) else theme.surface.copy(alpha = 0.65f)
    val borderColor = if (isHighlighted) theme.accent else theme.accent.copy(alpha = 0.3f)

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).combinedClickable(onClick = onClick, onLongClick = { showMenu = true }),
        colors = CardDefaults.cardColors(containerColor = bgColor), border = BorderStroke(if (isHighlighted) 2.dp else 1.dp, borderColor)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(text = "($orderNumber). ${step.description}", color = theme.text, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = theme.text) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(theme.surface)) {
                        DropdownMenuItem(text = { Text("Mark as Current", color = theme.text) }, onClick = { onMarkCurrent(); showMenu = false })
                        DropdownMenuItem(text = { Text("Delete Step", color = theme.accent) }, onClick = { onDeleteStep(); showMenu = false })
                    }
                }
            }
            if (step.image1Uri != null || step.image2Uri != null || step.image3Uri != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step.image1Uri != null) MiniCard(uri = step.image1Uri, theme = theme)
                    if (step.image2Uri != null) MiniCard(uri = step.image2Uri, theme = theme)
                    if (step.image3Uri != null) MiniCard(uri = step.image3Uri, theme = theme)
                }
            }
        }
    }
}

@Composable
fun StepInputDialog(initialStep: ComboStep, theme: AppThemeColors, onDismiss: () -> Unit, onNextStep: (String, Uri?, Uri?, Uri?) -> Unit, onFinish: (String, Uri?, Uri?, Uri?) -> Unit) {
    var text by remember { mutableStateOf(initialStep.description) }
    var image1 by remember { mutableStateOf(initialStep.image1Uri) }
    var image2 by remember { mutableStateOf(initialStep.image2Uri) }
    var image3 by remember { mutableStateOf(initialStep.image3Uri) }
    val context = LocalContext.current
    val p1 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); image1 = uri } }
    val p2 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); image2 = uri } }
    val p3 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); image3 = uri } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = theme.surface.copy(alpha = 0.95f), border = BorderStroke(1.dp, theme.accent)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Target Cards (Optional)", color = theme.text.copy(alpha = 0.7f))
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CardPlaceholder(imageUri = image1, theme = theme, onClick = { p1.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f))
                    CardPlaceholder(imageUri = image2, theme = theme, onClick = { p2.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f))
                    CardPlaceholder(imageUri = image3, theme = theme, onClick = { p3.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text("What to do in this step?", color = theme.text.copy(alpha = 0.7f)) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = theme.text, unfocusedTextColor = theme.text, focusedBorderColor = theme.accent, unfocusedBorderColor = theme.text.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onNextStep(text, image1, image2, image3); text = ""; image1 = null; image2 = null; image3 = null }) { Text("Next step", color = theme.accent) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onFinish(text, image1, image2, image3) }, colors = ButtonDefaults.buttonColors(containerColor = theme.accent)) { Text("Finish", color = Color.White) }
                }
            }
        }
    }
}

// SETTINGS DIALOG (UNCHANGED)
@Composable
fun SettingsDialog(
    theme: AppThemeColors, currentBgUri: Uri?, isDarkMode: Boolean, onlyHighlightCurrent: Boolean,
    onDismiss: () -> Unit, onColorChange: (Color) -> Unit, onBgChange: (Uri?) -> Unit, onThemeToggle: (Boolean) -> Unit, onHighlightToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            onBgChange(uri)
        }
    }

    val colorOptions = listOf(
        RedHoodCrimson, Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7), Color(0xFF3F51B5),
        Color(0xFF2196F3), Color(0xFF03A9F4), Color(0xFF00BCD4), Color(0xFF009688), Color(0xFF4CAF50),
        Color(0xFF8BC34A), Color(0xFFFFC107), Color(0xFFFF9800), Color(0xFFFF5722), Color(0xFF607D8B)
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = theme.surface.copy(alpha = 0.9f), border = BorderStroke(1.dp, theme.accent)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Settings", color = theme.text, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Dark Mode", color = theme.text)
                    Switch(checked = isDarkMode, onCheckedChange = onThemeToggle, colors = SwitchDefaults.colors(checkedThumbColor = theme.accent, checkedTrackColor = theme.accent.copy(alpha = 0.5f)))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Only Highlight Current Step", color = theme.text)
                    Switch(checked = onlyHighlightCurrent, onCheckedChange = onHighlightToggle, colors = SwitchDefaults.colors(checkedThumbColor = theme.accent, checkedTrackColor = theme.accent.copy(alpha = 0.5f)))
                }
                Spacer(modifier = Modifier.height(16.dp))

                Text("Background Image", color = theme.text)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = { photoPicker.launch(arrayOf("image/*")) }, colors = ButtonDefaults.buttonColors(containerColor = theme.accent)) { Text("Pick Image", color = Color.White) }
                    Button(onClick = { onBgChange(null) }, colors = ButtonDefaults.buttonColors(containerColor = if (isDarkMode) RedHoodDarkGrey else Color.LightGray)) { Text("Reset", color = theme.text) }
                }
                Spacer(modifier = Modifier.height(24.dp))

                Text("App Theme Color", color = theme.text)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { colorOptions.take(5).forEach { color -> Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(color).clickable { onColorChange(color) }) } }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { colorOptions.drop(5).take(5).forEach { color -> Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(color).clickable { onColorChange(color) }) } }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { colorOptions.drop(10).take(5).forEach { color -> Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(color).clickable { onColorChange(color) }) } }

                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { onColorChange(RedHoodCrimson) }, colors = ButtonDefaults.buttonColors(containerColor = if (isDarkMode) RedHoodDarkGrey else Color.LightGray)) { Text("Reset Color", color = theme.text) }
            }
        }
    }
}