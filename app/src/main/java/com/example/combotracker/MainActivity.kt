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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.room.*
import coil.compose.AsyncImage
import com.example.combotracker.ui.theme.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch

// ============================================================================
// 1. DATABASE & DATA MODELS
// ============================================================================

// Added the imageUri to our step!
data class ComboStep(
    val id: Int = 0,
    val description: String,
    val isChecked: Boolean = false,
    val imageUri: Uri? = null
)

@Entity(tableName = "combos")
data class Combo(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val card1Uri: Uri? = null,
    val card2Uri: Uri? = null,
    val steps: List<ComboStep> = emptyList()
)

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromStepsList(steps: List<ComboStep>?): String = gson.toJson(steps)

    @TypeConverter
    fun toStepsList(stepsString: String?): List<ComboStep> {
        val type = object : TypeToken<List<ComboStep>>() {}.type
        return gson.fromJson(stepsString, type) ?: emptyList()
    }

    @TypeConverter
    fun fromUri(uri: Uri?): String? = uri?.toString()

    @TypeConverter
    fun toUri(uriString: String?): Uri? = uriString?.let { Uri.parse(it) }
}

@Dao
interface ComboDao {
    @Query("SELECT * FROM combos")
    suspend fun getAllCombos(): List<Combo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCombo(combo: Combo): Long

    @Update
    suspend fun updateCombo(combo: Combo)

    @Delete
    suspend fun deleteCombo(combo: Combo)
}

@Database(entities = [Combo::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun comboDao(): ComboDao
}

// ============================================================================
// 2. SETTINGS MANAGER
// ============================================================================

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("combo_settings", Context.MODE_PRIVATE)

    var appColor: Int
        get() = prefs.getInt("appColor", 0xFFB71C1C.toInt())
        set(value) = prefs.edit().putInt("appColor", value).apply()

    var isDarkMode: Boolean
        get() = prefs.getBoolean("isDarkMode", true)
        set(value) = prefs.edit().putBoolean("isDarkMode", value).apply()

    var bgImageUri: String?
        get() = prefs.getString("bgImageUri", null)
        set(value) = prefs.edit().putString("bgImageUri", value).apply()
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

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "combo-database").build()
        settings = SettingsManager(applicationContext)

        setContent {
            ComboTrackerTheme {
                ComboApp(db, settings)
            }
        }
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
                alpha = if (isDarkMode) 0.15f else 0.3f, modifier = Modifier.fillMaxSize()
            )
        }

        if (currentCombo == null) {
            MainMenuScreen(
                comboList = comboList, theme = theme, bgImageUri = bgImageUri, isDarkMode = isDarkMode,
                onNavigateToDetail = { currentCombo = it },
                onAddCombo = { name, uri1, uri2 ->
                    scope.launch {
                        val newCombo = Combo(name = name, card1Uri = uri1, card2Uri = uri2)
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
                        currentCombo = updatedCombo
                    }
                },
                onDeleteCombo = { comboToDelete ->
                    scope.launch {
                        db.comboDao().deleteCombo(comboToDelete)
                        comboList.remove(comboToDelete)
                    }
                },
                onColorChange = { color ->
                    appColor = color
                    settings.appColor = color.toArgb()
                },
                onBgChange = { uri ->
                    bgImageUri = uri
                    settings.bgImageUri = uri?.toString()
                },
                onThemeToggle = { dark ->
                    isDarkMode = dark
                    settings.isDarkMode = dark
                }
            )
        } else {
            ComboDetailScreen(
                combo = currentCombo!!, theme = theme,
                onSaveCombo = { savedCombo ->
                    scope.launch {
                        db.comboDao().updateCombo(savedCombo)
                        val index = comboList.indexOfFirst { it.id == savedCombo.id }
                        if (index != -1) comboList[index] = savedCombo
                        currentCombo = null
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
    comboList: List<Combo>, theme: AppThemeColors, bgImageUri: Uri?, isDarkMode: Boolean,
    onNavigateToDetail: (Combo) -> Unit, onAddCombo: (String, Uri?, Uri?) -> Unit, onUpdateCombo: (Combo) -> Unit, onDeleteCombo: (Combo) -> Unit,
    onColorChange: (Color) -> Unit, onBgChange: (Uri?) -> Unit, onThemeToggle: (Boolean) -> Unit
) {
    var comboToEdit by remember { mutableStateOf<Combo?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Combo Tracker", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = theme.accent),
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
                        ComboListItem(combo, theme, onClick = { onNavigateToDetail(combo) }, onEditClick = { comboToEdit = combo }, onDeleteClick = { onDeleteCombo(combo) })
                    }
                }
            }
        }

        comboToEdit?.let { combo ->
            ComboInfoDialog(initialCombo = combo, theme = theme, onDismiss = { comboToEdit = null }, onStart = { name, uri1, uri2 ->
                if (combo.id == 0) onAddCombo(name, uri1, uri2) else onUpdateCombo(combo.copy(name = name, card1Uri = uri1, card2Uri = uri2))
                comboToEdit = null
            })
        }

        if (showSettings) {
            SettingsDialog(theme = theme, currentBgUri = bgImageUri, isDarkMode = isDarkMode, onDismiss = { showSettings = false }, onColorChange = onColorChange, onBgChange = onBgChange, onThemeToggle = onThemeToggle)
        }
    }
}

// ============================================================================
// 5. SETTINGS DIALOG
// ============================================================================
@Composable
fun SettingsDialog(
    theme: AppThemeColors, currentBgUri: Uri?, isDarkMode: Boolean,
    onDismiss: () -> Unit, onColorChange: (Color) -> Unit, onBgChange: (Uri?) -> Unit, onThemeToggle: (Boolean) -> Unit
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
        Surface(shape = RoundedCornerShape(16.dp), color = theme.surface, border = BorderStroke(1.dp, theme.accent)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Settings", color = theme.text, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Dark Mode", color = theme.text)
                    Switch(checked = isDarkMode, onCheckedChange = onThemeToggle, colors = SwitchDefaults.colors(checkedThumbColor = theme.accent, checkedTrackColor = theme.accent.copy(alpha = 0.5f)))
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

// ============================================================================
// 6. SHARED UI COMPONENTS
// ============================================================================
@Composable
fun ComboListItem(combo: Combo, theme: AppThemeColors, onClick: () -> Unit, onEditClick: () -> Unit, onDeleteClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = theme.surface), border = BorderStroke(1.dp, theme.accent.copy(alpha = 0.5f))) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { MiniCard(uri = combo.card1Uri, theme = theme); MiniCard(uri = combo.card2Uri, theme = theme) }
            Spacer(modifier = Modifier.width(16.dp))
            Text(combo.name, color = theme.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onEditClick) { Icon(Icons.Default.Edit, contentDescription = "Edit", tint = theme.text) }
            IconButton(onClick = onDeleteClick) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = theme.accent) }
        }
    }
}

@Composable
fun MiniCard(uri: Uri?, theme: AppThemeColors) {
    Box(modifier = Modifier.height(48.dp).aspectRatio(59f / 86f).clip(RoundedCornerShape(4.dp)).background(theme.bg).border(1.dp, theme.text.copy(alpha = 0.3f), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
        if (uri != null) AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Default.Add, contentDescription = null, tint = theme.text.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
    }
}

// ============================================================================
// 7. COMBO CREATION/EDIT DIALOG
// ============================================================================
@Composable
fun ComboInfoDialog(initialCombo: Combo, theme: AppThemeColors, onDismiss: () -> Unit, onStart: (String, Uri?, Uri?) -> Unit) {
    var comboName by remember { mutableStateOf(initialCombo.name) }
    var card1Uri by remember { mutableStateOf(initialCombo.card1Uri) }
    var card2Uri by remember { mutableStateOf(initialCombo.card2Uri) }
    val context = LocalContext.current

    val photoPicker1 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); card1Uri = uri }
    }
    val photoPicker2 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); card2Uri = uri }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = theme.surface, border = BorderStroke(1.dp, theme.accent)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (initialCombo.id == 0) "New Combo" else "Edit Combo", color = theme.text, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(value = comboName, onValueChange = { comboName = it }, label = { Text("Combo Name", color = theme.text.copy(alpha = 0.7f)) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = theme.text, unfocusedTextColor = theme.text, focusedBorderColor = theme.accent, unfocusedBorderColor = theme.text.copy(alpha = 0.5f)), singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    CardPlaceholder(imageUri = card1Uri, theme = theme, onClick = { photoPicker1.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f))
                    CardPlaceholder(imageUri = card2Uri, theme = theme, onClick = { photoPicker2.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { onStart(if (comboName.isBlank()) "Unnamed" else comboName, card1Uri, card2Uri) }, colors = ButtonDefaults.buttonColors(containerColor = theme.accent), modifier = Modifier.fillMaxWidth()) { Text(if (initialCombo.id == 0) "Start" else "Save & Continue", color = Color.White) }
            }
        }
    }
}

@Composable
fun CardPlaceholder(imageUri: Uri?, theme: AppThemeColors, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.aspectRatio(59f / 86f).clip(RoundedCornerShape(8.dp)).background(theme.bg).border(1.dp, theme.text.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).clickable { onClick() }, contentAlignment = Alignment.Center) {
        if (imageUri != null) AsyncImage(model = imageUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Default.Add, contentDescription = "Add Card", tint = theme.text)
    }
}

// ============================================================================
// 8. COMBO DETAIL SCREEN (Now With Step Images!)
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComboDetailScreen(combo: Combo, theme: AppThemeColors, onSaveCombo: (Combo) -> Unit, onBackClicked: () -> Unit) {
    var draftCombo by remember { mutableStateOf(combo) }
    var stepToEdit by remember { mutableStateOf<ComboStep?>(null) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(draftCombo.name, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = theme.accent),
                navigationIcon = {
                    IconButton(onClick = onBackClicked) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
                },
                actions = {
                    IconButton(onClick = {
                        val lastCheckedIndex = draftCombo.steps.indexOfLast { it.isChecked }
                        val nextIndex = if (lastCheckedIndex == -1) 0 else lastCheckedIndex + 1
                        if (nextIndex < draftCombo.steps.size) {
                            val updatedSteps = draftCombo.steps.toMutableList()
                            updatedSteps[nextIndex] = updatedSteps[nextIndex].copy(isChecked = true)
                            draftCombo = draftCombo.copy(steps = updatedSteps)
                        }
                    }) { Icon(Icons.Default.Check, contentDescription = "Auto Check Next", tint = Color.White) }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { stepToEdit = ComboStep(id = 0, description = "") }, containerColor = theme.accent) {
                Icon(Icons.Default.Add, contentDescription = "Add Step", tint = Color.White)
            }
        },
        bottomBar = {
            Button(
                onClick = {
                    onSaveCombo(draftCombo)
                    Toast.makeText(context, "Combo is saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = theme.accent)
            ) {
                Text("Save Combo", color = Color.White)
            }
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(draftCombo.steps) { index, step ->
                    StepListItem(
                        orderNumber = index + 1, step = step, theme = theme, onClick = { stepToEdit = step },
                        onCheckedChange = { isChecked ->
                            val updatedSteps = draftCombo.steps.toMutableList()
                            updatedSteps[index] = step.copy(isChecked = isChecked)
                            draftCombo = draftCombo.copy(steps = updatedSteps)
                        },
                        onDeleteStep = {
                            val updatedSteps = draftCombo.steps.toMutableList()
                            updatedSteps.removeAt(index)
                            draftCombo = draftCombo.copy(steps = updatedSteps)
                        }
                    )
                }
            }
        }

        stepToEdit?.let { currentStep ->
            StepInputDialog(
                initialText = currentStep.description,
                initialImageUri = currentStep.imageUri, // Pass the initial image!
                theme = theme,
                onDismiss = { stepToEdit = null },
                onNextStep = { newText, newImageUri ->
                    if (newText.isNotBlank() || newImageUri != null) {
                        draftCombo = draftCombo.copy(steps = draftCombo.steps + currentStep.copy(id = draftCombo.steps.size + 1, description = newText, imageUri = newImageUri))
                    }
                },
                onFinish = { newText, newImageUri ->
                    if (newText.isNotBlank() || newImageUri != null) {
                        val updatedSteps = if (currentStep.id == 0) draftCombo.steps + currentStep.copy(id = draftCombo.steps.size + 1, description = newText, imageUri = newImageUri)
                        else draftCombo.steps.map { if (it.id == currentStep.id) it.copy(description = newText, imageUri = newImageUri) else it }
                        draftCombo = draftCombo.copy(steps = updatedSteps)
                    }
                    stepToEdit = null
                }
            )
        }
    }
}

// Updated list item to cleanly show the mini card on the left of the delete button
@Composable
fun StepListItem(orderNumber: Int, step: ComboStep, theme: AppThemeColors, onClick: () -> Unit, onCheckedChange: (Boolean) -> Unit, onDeleteStep: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = theme.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "($orderNumber). ${step.description}", color = theme.text, modifier = Modifier.weight(1f))

            // Conditionally show the mini image! Zero space taken if it's null.
            if (step.imageUri != null) {
                Spacer(modifier = Modifier.width(8.dp))
                MiniCard(uri = step.imageUri, theme = theme)
                Spacer(modifier = Modifier.width(8.dp))
            }

            IconButton(onClick = onDeleteStep) { Icon(Icons.Default.Delete, contentDescription = "Delete Step", tint = theme.accent) }
            Checkbox(checked = step.isChecked, onCheckedChange = onCheckedChange, colors = CheckboxDefaults.colors(checkedColor = theme.accent, uncheckedColor = theme.text, checkmarkColor = Color.White))
        }
    }
}

// Updated Dialog to include the photo picker for individual steps
@Composable
fun StepInputDialog(initialText: String, initialImageUri: Uri?, theme: AppThemeColors, onDismiss: () -> Unit, onNextStep: (String, Uri?) -> Unit, onFinish: (String, Uri?) -> Unit) {
    var text by remember { mutableStateOf(initialText) }
    var imageUri by remember { mutableStateOf(initialImageUri) }

    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            imageUri = uri
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = theme.surface, border = BorderStroke(1.dp, theme.accent)) {
            Column(modifier = Modifier.padding(16.dp)) {

                // Add the mini image selector to the top of the dialog
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Target Card (Optional)", color = theme.text.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                    CardPlaceholder(
                        imageUri = imageUri,
                        theme = theme,
                        onClick = { photoPicker.launch(arrayOf("image/*")) },
                        modifier = Modifier.height(80.dp) // Keeps it appropriately sized for a step
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text("What to do in this step?", color = theme.text.copy(alpha = 0.7f)) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = theme.text, unfocusedTextColor = theme.text, focusedBorderColor = theme.accent, unfocusedBorderColor = theme.text.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().height(150.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        onNextStep(text, imageUri)
                        text = ""
                        imageUri = null // Immediately reset the image for the next step!
                    }) { Text("Next step", color = theme.accent) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onFinish(text, imageUri) }, colors = ButtonDefaults.buttonColors(containerColor = theme.accent)) { Text("Finish", color = Color.White) }
                }
            }
        }
    }
}