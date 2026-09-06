package pl.somaskan.questgpt

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.somaskan.questgpt.files.FileWorkspace
import pl.somaskan.questgpt.navigation.AppScreen
import pl.somaskan.questgpt.ui.*
import pl.somaskan.questgpt.update.UpdateClient
import pl.somaskan.questgpt.update.UpdateManager
import java.io.File

class MainActivity : ComponentActivity() {
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val backend = OpenAIBackend()
    private val messages = mutableStateListOf<ChatLine>()
    private var previousResponseId: String? = null
    private var backendUrl by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var voiceState by mutableStateOf("Głos wyłączony")
    private var assistantVoiceDraft by mutableStateOf("")
    private var currentScreen by mutableStateOf(AppScreen.CHAT)

    private var openedFileUri by mutableStateOf<Uri?>(null)
    private var openedFileLabel by mutableStateOf("brak")
    private var fileText by mutableStateOf("")
    private var folderLabel by mutableStateOf("nie wybrano")
    private var downloadUrl by mutableStateOf("")
    private var pendingDownloadUrl: String? = null

    private var updateStatus by mutableStateOf("Sprawdzanie wersji...")
    private var pendingApk by mutableStateOf<File?>(null)

    private lateinit var voice: RealtimeVoiceClient
    private lateinit var files: FileWorkspace
    private lateinit var updateClient: UpdateClient
    private lateinit var updateManager: UpdateManager

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uiScope.launch {
            runCatching { uriToDataUrl(uri.toString()) }
                .onSuccess { sendMessage("Co widzisz na tym obrazie?", it) }
                .onFailure { messages += ChatLine("system", "Błąd obrazu: ${it.message}") }
        }
    }

    private val openTextFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            files.persistUri(uri)
            openedFileUri = uri
            openedFileLabel = uri.lastPathSegment ?: "dokument"
            uiScope.launch { runCatching { files.readText(uri) }.onSuccess { fileText = it }.onFailure { fileText = "Błąd odczytu: ${it.message}" } }
        }
    }

    private val createTextFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            files.persistUri(uri)
            openedFileUri = uri
            openedFileLabel = uri.lastPathSegment ?: "nowy dokument"
            fileText = ""
        }
    }

    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            files.persistUri(uri)
            folderLabel = uri.lastPathSegment ?: "wybrany folder"
            getSharedPreferences("questgpt", MODE_PRIVATE).edit().putString("folder", uri.toString()).apply()
        }
    }

    private val createDownloadedFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val url = pendingDownloadUrl
        if (uri != null && !url.isNullOrBlank()) {
            files.persistUri(uri)
            uiScope.launch {
                runCatching { files.downloadUrl(url, uri) }
                    .onSuccess { messages += ChatLine("system", "Plik pobrany") }
                    .onFailure { messages += ChatLine("system", "Pobieranie: ${it.message}") }
            }
        }
        pendingDownloadUrl = null
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else messages += ChatLine("system", "Mikrofon nie został udostępniony")
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            uiScope.launch {
                busy = true
                runCatching {
                    val png = CaptureHelper.capturePng(this@MainActivity, result.resultCode, result.data!!)
                    "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
                }.onSuccess { sendMessage("Przeanalizuj ten screenshot z mojego Questa.", it) }
                    .onFailure { messages += ChatLine("system", "Screenshot: ${it.message}"); busy = false }
                stopService(Intent(this@MainActivity, CaptureService::class.java))
            }
        } else stopService(Intent(this, CaptureService::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backendUrl = getSharedPreferences("questgpt", MODE_PRIVATE).getString("backend", "http://10.0.2.2:8787") ?: "http://10.0.2.2:8787"
        folderLabel = getSharedPreferences("questgpt", MODE_PRIVATE).getString("folder", null)?.let { Uri.parse(it).lastPathSegment } ?: "nie wybrano"
        files = FileWorkspace(applicationContext)
        updateClient = UpdateClient(applicationContext)
        updateManager = UpdateManager(applicationContext)

        voice = RealtimeVoiceClient(
            applicationContext,
            onAssistantDelta = { delta -> runOnUiThread {
                assistantVoiceDraft += delta
                val index = messages.indexOfLast { it.role == "assistant_live" }
                if (index >= 0) messages[index] = ChatLine("assistant_live", assistantVoiceDraft) else messages += ChatLine("assistant_live", assistantVoiceDraft)
            } },
            onUserTranscript = { text -> runOnUiThread { if (text.isNotBlank()) messages += ChatLine("user", text) } },
            onState = { state -> runOnUiThread { voiceState = state } },
            onError = { error -> runOnUiThread { messages += ChatLine("system", error) } }
        )

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    MultiBoardShell(currentScreen, { currentScreen = it }) {
                        when (currentScreen) {
                            AppScreen.CHAT -> ChatScreen()
                            AppScreen.VOICE -> VoiceScreen(voiceState, ::toggleVoice)
                            AppScreen.FILES -> FilesScreen(
                                openedFileLabel, fileText, { fileText = it }, folderLabel,
                                downloadUrl, { downloadUrl = it },
                                { openTextFile.launch(arrayOf("text/*", "application/json", "application/xml")) },
                                { createTextFile.launch("questgpt-note.txt") },
                                ::saveOpenedFile,
                                { chooseFolder.launch(null) },
                                ::startDownload,
                            )
                            AppScreen.BOARDS -> BoardsScreen()
                            AppScreen.UPDATES -> UpdatesScreen(updateStatus, updateManager.canRequestPackageInstalls(), ::openInstallPermission, { checkForUpdate(false) }, ::installPendingUpdate)
                            AppScreen.SETTINGS -> SettingsScreen(
                                backendUrl,
                                { backendUrl = it; getSharedPreferences("questgpt", MODE_PRIVATE).edit().putString("backend", it).apply() },
                                hasPermission(Manifest.permission.RECORD_AUDIO),
                                Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS),
                                updateManager.canRequestPackageInstalls(),
                                { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                                ::requestNotifications,
                                ::openInstallPermission,
                            )
                        }
                    }
                }
            }
        }

        checkForUpdate(true)
    }

    override fun onDestroy() {
        voice.stop()
        super.onDestroy()
    }

    @Composable
    private fun ChatScreen() {
        var draft by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("QuestGPT", style = MaterialTheme.typography.headlineSmall); Text(if (busy) "Przetwarzanie..." else "Gotowy") }
                TextButton(onClick = { currentScreen = AppScreen.SETTINGS }) { Text("Ustawienia") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { line ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (line.role == "user") "Ty" else if (line.role.startsWith("assistant")) "GPT" else "System", style = MaterialTheme.typography.labelSmall)
                            Text(line.text)
                        }
                    }
                }
            }
            OutlinedTextField(draft, { draft = it }, label = { Text("Napisz wiadomość") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { val t = draft.trim(); if (t.isNotEmpty()) { draft = ""; sendMessage(t, null) } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Wyślij") }
                OutlinedButton(onClick = { pickImage.launch("image/*") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Zdjęcie") }
                OutlinedButton(onClick = ::requestScreenshot, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Screenshot") }
            }
        }
    }

    private fun sendMessage(text: String, imageDataUrl: String?) {
        if (backendUrl.isBlank()) { messages += ChatLine("system", "Ustaw Backend URL"); return }
        messages += ChatLine("user", if (imageDataUrl == null) text else "$text [obraz]")
        busy = true
        uiScope.launch {
            runCatching { backend.respond(backendUrl, text, imageDataUrl, previousResponseId) }
                .onSuccess { previousResponseId = it.responseId; messages += ChatLine("assistant", it.text) }
                .onFailure { messages += ChatLine("system", it.message ?: "Błąd") }
            busy = false
        }
    }

    private fun saveOpenedFile() {
        val uri = openedFileUri ?: run { messages += ChatLine("system", "Najpierw otwórz lub utwórz plik"); return }
        uiScope.launch { runCatching { files.writeText(uri, fileText) }.onSuccess { messages += ChatLine("system", "Plik zapisany") }.onFailure { messages += ChatLine("system", "Zapis: ${it.message}") } }
    }

    private fun startDownload() {
        val url = downloadUrl.trim()
        if (url.isBlank()) return
        pendingDownloadUrl = url
        createDownloadedFile.launch(url.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" })
    }

    private fun checkForUpdate(autoInstall: Boolean) {
        updateStatus = "Sprawdzanie wersji..."
        uiScope.launch {
            runCatching { updateClient.findUpdate() }.onSuccess { info ->
                if (info == null) updateStatus = "Masz najnowszą wersję (build ${updateClient.currentBuild()})."
                else {
                    updateStatus = "Znaleziono build ${info.build}. Pobieranie..."
                    runCatching { updateClient.download(info) }.onSuccess { apk ->
                        pendingApk = apk
                        updateStatus = "Pobrano build ${info.build}. Gotowy do instalacji."
                        if (autoInstall && updateManager.canRequestPackageInstalls()) updateManager.launchInstaller(apk)
                    }.onFailure { updateStatus = "Błąd pobierania: ${it.message}" }
                }
            }.onFailure { updateStatus = "Błąd sprawdzania: ${it.message}" }
        }
    }

    private fun installPendingUpdate() { pendingApk?.let(updateManager::launchInstaller) }
    private fun openInstallPermission() { startActivity(updateManager.buildUnknownSourcesIntent()) }

    private fun toggleVoice() {
        if (voiceState.startsWith("Połączono")) { voice.stop(); assistantVoiceDraft = ""; return }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) micPermission.launch(Manifest.permission.RECORD_AUDIO) else startVoice()
    }

    private fun startVoice() { assistantVoiceDraft = ""; voice.start(backendUrl) }

    private fun requestScreenshot() {
        ContextCompat.startForegroundService(this, Intent(this, CaptureService::class.java))
        capturePermission.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun uriToDataUrl(uriString: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Nie można odczytać obrazu")
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
        "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

data class ChatLine(val role: String, val text: String)
