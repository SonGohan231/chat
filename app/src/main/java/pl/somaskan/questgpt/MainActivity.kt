package pl.somaskan.questgpt

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import pl.somaskan.questgpt.data.Board
import pl.somaskan.questgpt.data.BoardStore
import pl.somaskan.questgpt.files.FileWorkspace
import pl.somaskan.questgpt.live.LiveConfig
import pl.somaskan.questgpt.live.LiveConfigClient
import pl.somaskan.questgpt.navigation.AppScreen
import pl.somaskan.questgpt.ui.*
import pl.somaskan.questgpt.update.UpdateClient
import pl.somaskan.questgpt.update.UpdateManager
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : ComponentActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val backend = OpenAIBackend()
    private val messages = mutableStateListOf<ChatLine>()
    private val boards = mutableStateListOf<Board>()
    private var previousResponseId: String? = null
    private var selectedBoardId by mutableStateOf("")
    private var backendUrl by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var voiceState by mutableStateOf("Głos wyłączony")
    private var assistantVoiceDraft by mutableStateOf("")
    private var currentScreen by mutableStateOf(AppScreen.CHAT)

    private var visionImageDataUrl by mutableStateOf<String?>(null)
    private var imageTarget = ImageTarget.CHAT
    private var captureTarget = CaptureTarget.CHAT

    private var openedFileUri by mutableStateOf<Uri?>(null)
    private var openedFileLabel by mutableStateOf("brak")
    private var fileText by mutableStateOf("")
    private var folderLabel by mutableStateOf("nie wybrano")
    private var downloadUrl by mutableStateOf("")
    private var pendingDownloadUrl: String? = null

    private var updateStatus by mutableStateOf("Sprawdzanie wersji...")
    private var pendingApk by mutableStateOf<File?>(null)

    private var liveConfig by mutableStateOf(LiveConfig())
    private var liveStatus by mutableStateOf("Start synchronizacji")
    private var localLiveOverride by mutableStateOf(false)
    private val liveConfigClient = LiveConfigClient()
    private var liveRefreshJob: Job? = null

    private lateinit var voice: RealtimeVoiceClient
    private lateinit var files: FileWorkspace
    private lateinit var boardStore: BoardStore
    private lateinit var updateClient: UpdateClient
    private lateinit var updateManager: UpdateManager

    private enum class ImageTarget { CHAT, VISION, SKETCH }
    private enum class CaptureTarget { CHAT, VISION, SKETCH }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uiScope.launch {
            runCatching { uriToDataUrl(uri.toString()) }
                .onSuccess { dataUrl ->
                    when (imageTarget) {
                        ImageTarget.CHAT -> sendMessage("Co widzisz na tym obrazie?", dataUrl)
                        ImageTarget.VISION -> {
                            visionImageDataUrl = dataUrl
                            currentScreen = AppScreen.VISION
                        }
                        ImageTarget.SKETCH -> {
                            visionImageDataUrl = dataUrl
                            currentScreen = AppScreen.SKETCH
                        }
                    }
                }
                .onFailure { addSystemMessage("Błąd obrazu: ${it.message}") }
        }
    }

    private val openTextFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            files.persistUri(uri)
            openedFileUri = uri
            openedFileLabel = uri.lastPathSegment ?: "dokument"
            uiScope.launch {
                runCatching { files.readText(uri) }
                    .onSuccess { fileText = it }
                    .onFailure { fileText = "Błąd odczytu: ${it.message}" }
            }
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
                    .onSuccess { addSystemMessage("Plik pobrany") }
                    .onFailure { addSystemMessage("Pobieranie: ${it.message}") }
            }
        }
        pendingDownloadUrl = null
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else addSystemMessage("Mikrofon nie został udostępniony")
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            uiScope.launch {
                runCatching {
                    val png = CaptureHelper.capturePng(this@MainActivity, result.resultCode, result.data!!)
                    "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
                }.onSuccess { dataUrl ->
                    when (captureTarget) {
                        CaptureTarget.CHAT -> sendMessage("Przeanalizuj ten screenshot z mojego Questa.", dataUrl)
                        CaptureTarget.VISION -> {
                            visionImageDataUrl = dataUrl
                            currentScreen = AppScreen.VISION
                        }
                        CaptureTarget.SKETCH -> {
                            visionImageDataUrl = dataUrl
                            currentScreen = AppScreen.SKETCH
                        }
                    }
                }.onFailure { addSystemMessage("Screenshot: ${it.message}") }
                stopService(Intent(this@MainActivity, CaptureService::class.java))
            }
        } else stopService(Intent(this, CaptureService::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("questgpt", MODE_PRIVATE)
        backendUrl = prefs.getString("backend", "http://10.0.2.2:8787") ?: "http://10.0.2.2:8787"
        folderLabel = prefs.getString("folder", null)?.let { Uri.parse(it).lastPathSegment } ?: "nie wybrano"
        localLiveOverride = prefs.getBoolean("live_override_enabled", false)
        if (localLiveOverride) {
            prefs.getString("live_override_json", null)?.let { raw ->
                runCatching { LiveConfig.fromJson(raw) }.onSuccess { liveConfig = it }
            }
        }

        files = FileWorkspace(applicationContext)
        boardStore = BoardStore(applicationContext)
        updateClient = UpdateClient(applicationContext)
        updateManager = UpdateManager(applicationContext)

        boards += boardStore.loadBoards(liveConfig.boardTemplates)
        selectedBoardId = boardStore.selectedBoardId()?.takeIf { id -> boards.any { it.id == id } }
            ?: boards.firstOrNull()?.id.orEmpty()
        if (selectedBoardId.isNotBlank()) loadBoardSession(selectedBoardId)

        voice = RealtimeVoiceClient(
            applicationContext,
            onAssistantDelta = { delta -> runOnUiThread {
                assistantVoiceDraft += delta
                val index = messages.indexOfLast { it.role == "assistant_live" }
                if (index >= 0) messages[index] = ChatLine("assistant_live", assistantVoiceDraft)
                else messages += ChatLine("assistant_live", assistantVoiceDraft)
                saveCurrentSession()
            } },
            onUserTranscript = { text -> runOnUiThread {
                if (text.isNotBlank()) {
                    messages += ChatLine("user", text)
                    saveCurrentSession()
                }
            } },
            onState = { state -> runOnUiThread { voiceState = state } },
            onError = { error -> runOnUiThread { addSystemMessage(error) } }
        )

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    MultiBoardShell(currentScreen, { currentScreen = it }) {
                        when (currentScreen) {
                            AppScreen.CHAT -> ChatScreen()
                            AppScreen.VOICE -> VoiceScreen(voiceState, ::toggleVoice)
                            AppScreen.VISION -> VisionScreen(
                                imageDataUrl = visionImageDataUrl,
                                busy = busy,
                                quickActions = liveConfig.quickActions,
                                onPickImage = {
                                    imageTarget = ImageTarget.VISION
                                    pickImage.launch("image/*")
                                },
                                onScreenshot = { requestScreenshot(CaptureTarget.VISION) },
                                onAnalyze = { prompt ->
                                    visionImageDataUrl?.let { sendMessage(prompt, it) }
                                },
                                onOpenSketch = { currentScreen = AppScreen.SKETCH }
                            )
                            AppScreen.SKETCH -> SketchScreen(
                                backgroundDataUrl = visionImageDataUrl,
                                busy = busy,
                                onPickBackground = {
                                    imageTarget = ImageTarget.SKETCH
                                    pickImage.launch("image/*")
                                },
                                onScreenshotBackground = { requestScreenshot(CaptureTarget.SKETCH) },
                                onSendAnnotated = ::sendAnnotatedSketch
                            )
                            AppScreen.FILES -> FilesScreen(
                                fileLabel = openedFileLabel,
                                text = fileText,
                                onTextChange = { fileText = it },
                                folderLabel = folderLabel,
                                downloadUrl = downloadUrl,
                                onDownloadUrlChange = { downloadUrl = it },
                                onOpenFile = { openTextFile.launch(arrayOf("text/*", "application/json", "application/xml", "text/markdown")) },
                                onCreateFile = { createTextFile.launch("questgpt-note.txt") },
                                onSave = ::saveOpenedFile,
                                onRename = ::renameOpenedFile,
                                onDelete = ::deleteOpenedFile,
                                onOpenFolder = { chooseFolder.launch(null) },
                                onDownload = ::startDownload,
                            )
                            AppScreen.BOARDS -> BoardsScreen(
                                boards = boards,
                                selectedBoardId = selectedBoardId,
                                onSelect = { id -> selectBoard(id); currentScreen = AppScreen.CHAT },
                                onAdd = ::addBoard,
                                onDelete = ::deleteBoard,
                            )
                            AppScreen.LIVE -> LiveEditScreen(
                                config = liveConfig,
                                status = liveStatus,
                                localOverride = localLiveOverride,
                                onApplyLocal = ::applyLocalLiveConfig,
                                onUseRemote = ::useRemoteLiveConfig,
                                onRefresh = { refreshLiveConfigNow() }
                            )
                            AppScreen.UPDATES -> UpdatesScreen(
                                updateStatus,
                                updateManager.canRequestPackageInstalls(),
                                ::openInstallPermission,
                                { checkForUpdate(false) },
                                ::installPendingUpdate
                            )
                            AppScreen.SETTINGS -> SettingsScreen(
                                backendUrl,
                                { backendUrl = it; prefs.edit().putString("backend", it).apply() },
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

        startLiveConfigSync()
        checkForUpdate(true)
    }

    override fun onDestroy() {
        saveCurrentSession()
        liveRefreshJob?.cancel()
        voice.stop()
        uiScope.cancel()
        super.onDestroy()
    }

    @Composable
    private fun ChatScreen() {
        var draft by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        val boardName = boards.firstOrNull { it.id == selectedBoardId }?.name ?: "Rozmowa"
        LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(liveConfig.title, style = MaterialTheme.typography.headlineSmall)
                    Text("$boardName • ${liveConfig.subtitle}", style = MaterialTheme.typography.bodySmall)
                }
                AssistChip(onClick = { currentScreen = AppScreen.LIVE }, label = { Text(if (busy) "Przetwarzanie" else "Gotowy") })
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                liveConfig.quickActions.forEach { action ->
                    AssistChip(onClick = { if (!busy) sendMessage(action, null) }, enabled = !busy, label = { Text(action) })
                }
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { line ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                if (line.role == "user") "Ty" else if (line.role.startsWith("assistant")) "GPT" else "System",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(line.text)
                        }
                    }
                }
            }

            OutlinedTextField(
                draft,
                { draft = it },
                label = { Text("Napisz wiadomość") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                minLines = 2
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            draft = ""
                            sendMessage(text, null)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text("Wyślij") }
                OutlinedButton(onClick = { currentScreen = AppScreen.VOICE }, modifier = Modifier.weight(1f)) { Text("Mikrofon") }
                OutlinedButton(onClick = {
                    imageTarget = ImageTarget.CHAT
                    pickImage.launch("image/*")
                }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Zdjęcie") }
                OutlinedButton(onClick = { requestScreenshot(CaptureTarget.CHAT) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Screenshot") }
            }
        }
    }

    private fun sendMessage(text: String, imageDataUrl: String?) {
        if (backendUrl.isBlank()) {
            addSystemMessage("Ustaw Backend URL")
            return
        }
        messages += ChatLine("user", if (imageDataUrl == null) text else "$text [obraz]")
        saveCurrentSession()
        busy = true
        uiScope.launch {
            runCatching { backend.respond(backendUrl, text, imageDataUrl, previousResponseId) }
                .onSuccess {
                    previousResponseId = it.responseId
                    messages += ChatLine("assistant", it.text)
                    saveCurrentSession()
                }
                .onFailure { addSystemMessage(it.message ?: "Błąd") }
            busy = false
        }
    }

    private fun addSystemMessage(text: String) {
        messages += ChatLine("system", text)
        saveCurrentSession()
    }

    private fun selectBoard(id: String) {
        if (id == selectedBoardId || boards.none { it.id == id }) return
        saveCurrentSession()
        selectedBoardId = id
        boardStore.saveSelectedBoard(id)
        loadBoardSession(id)
        assistantVoiceDraft = ""
    }

    private fun loadBoardSession(id: String) {
        val session = boardStore.loadSession(id)
        messages.clear()
        messages.addAll(session.messages)
        previousResponseId = session.previousResponseId
    }

    private fun saveCurrentSession() {
        if (::boardStore.isInitialized && selectedBoardId.isNotBlank()) {
            boardStore.saveSession(selectedBoardId, messages.toList(), previousResponseId)
        }
    }

    private fun addBoard(name: String) {
        val board = Board("board-${System.currentTimeMillis()}", name, "Niezależna przestrzeń robocza")
        boards += board
        boardStore.saveBoards(boards)
        selectBoard(board.id)
    }

    private fun deleteBoard(id: String) {
        if (boards.size <= 1) return
        val wasSelected = id == selectedBoardId
        if (wasSelected) saveCurrentSession()
        boards.removeAll { it.id == id }
        boardStore.deleteSession(id)
        boardStore.saveBoards(boards)
        if (wasSelected) {
            selectedBoardId = boards.first().id
            boardStore.saveSelectedBoard(selectedBoardId)
            loadBoardSession(selectedBoardId)
        }
    }

    private fun saveOpenedFile() {
        val uri = openedFileUri ?: run { addSystemMessage("Najpierw otwórz lub utwórz plik"); return }
        uiScope.launch {
            runCatching { files.writeText(uri, fileText) }
                .onSuccess { addSystemMessage("Plik zapisany") }
                .onFailure { addSystemMessage("Zapis: ${it.message}") }
        }
    }

    private fun renameOpenedFile(newName: String) {
        val uri = openedFileUri ?: run { addSystemMessage("Brak otwartego pliku"); return }
        val renamed = files.renameDocument(uri, newName)
        if (renamed != null) {
            openedFileUri = renamed
            openedFileLabel = renamed.lastPathSegment ?: newName
            addSystemMessage("Zmieniono nazwę pliku")
        } else addSystemMessage("Nie udało się zmienić nazwy")
    }

    private fun deleteOpenedFile() {
        val uri = openedFileUri ?: run { addSystemMessage("Brak otwartego pliku"); return }
        if (files.deleteDocument(uri)) {
            openedFileUri = null
            openedFileLabel = "brak"
            fileText = ""
            addSystemMessage("Plik usunięty")
        } else addSystemMessage("Nie udało się usunąć pliku")
    }

    private fun startDownload() {
        val url = downloadUrl.trim()
        if (url.isBlank()) return
        pendingDownloadUrl = url
        createDownloadedFile.launch(url.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" })
    }

    private fun sendAnnotatedSketch(strokes: List<List<Offset>>, size: IntSize) {
        val background = visionImageDataUrl
        busy = true
        uiScope.launch {
            runCatching { withContext(Dispatchers.Default) { renderAnnotatedDataUrl(background, strokes, size) } }
                .onSuccess { annotated ->
                    visionImageDataUrl = annotated
                    busy = false
                    currentScreen = AppScreen.VISION
                    sendMessage("Przeanalizuj obraz. Szczególnie skup się na elementach, które zaznaczyłem czerwonym rysunkiem.", annotated)
                }
                .onFailure {
                    busy = false
                    addSystemMessage("Szkic: ${it.message}")
                }
        }
    }

    private fun renderAnnotatedDataUrl(backgroundDataUrl: String?, strokes: List<List<Offset>>, sourceSize: IntSize): String {
        val width = sourceSize.width.coerceIn(320, 2048)
        val height = sourceSize.height.coerceIn(320, 2048)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        backgroundDataUrl?.let(::decodeDataUrlBitmap)?.let { background ->
            canvas.drawBitmap(background, null, Rect(0, 0, width, height), Paint(Paint.ANTI_ALIAS_FLAG))
            background.recycle()
        }
        val scaleX = width.toFloat() / sourceSize.width.coerceAtLeast(1)
        val scaleY = height.toFloat() / sourceSize.height.coerceAtLeast(1)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.RED
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 7f * ((scaleX + scaleY) / 2f)
        }
        strokes.forEach { stroke ->
            for (index in 1 until stroke.size) {
                val a = stroke[index - 1]
                val b = stroke[index]
                canvas.drawLine(a.x * scaleX, a.y * scaleY, b.x * scaleX, b.y * scaleY, paint)
            }
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)
            bitmap.recycle()
            "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun decodeDataUrlBitmap(dataUrl: String): Bitmap? = runCatching {
        val bytes = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun startLiveConfigSync() {
        liveRefreshJob?.cancel()
        if (localLiveOverride) {
            liveStatus = "Lokalny override"
            updateBoardsFromConfig()
            return
        }
        liveRefreshJob = uiScope.launch {
            while (isActive) {
                fetchLiveConfigOnce()
                delay(liveConfig.refreshSeconds.coerceIn(5, 3600) * 1000L)
            }
        }
    }

    private suspend fun fetchLiveConfigOnce() {
        liveStatus = "Synchronizacja..."
        runCatching { liveConfigClient.fetch() }
            .onSuccess {
                liveConfig = it
                liveStatus = "Połączono • ${it.updatedAt}"
                updateBoardsFromConfig()
            }
            .onFailure { liveStatus = "Offline • ${it.message ?: "błąd"}" }
    }

    private fun refreshLiveConfigNow() {
        if (localLiveOverride) return
        uiScope.launch { fetchLiveConfigOnce() }
    }

    private fun applyLocalLiveConfig(config: LiveConfig) {
        liveConfig = config
        localLiveOverride = true
        getSharedPreferences("questgpt", MODE_PRIVATE).edit()
            .putBoolean("live_override_enabled", true)
            .putString("live_override_json", config.toJson())
            .apply()
        liveStatus = "Lokalny override"
        updateBoardsFromConfig()
        startLiveConfigSync()
    }

    private fun useRemoteLiveConfig() {
        localLiveOverride = false
        getSharedPreferences("questgpt", MODE_PRIVATE).edit()
            .putBoolean("live_override_enabled", false)
            .remove("live_override_json")
            .apply()
        liveStatus = "Włączanie synchronizacji..."
        startLiveConfigSync()
    }

    private fun updateBoardsFromConfig() {
        if (!::boardStore.isInitialized) return
        val ensured = boardStore.ensureTemplates(boards.toList(), liveConfig.boardTemplates)
        if (ensured != boards.toList()) {
            boards.clear()
            boards.addAll(ensured)
        }
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
        if (voiceState.startsWith("Połączono")) {
            voice.stop()
            assistantVoiceDraft = ""
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) micPermission.launch(Manifest.permission.RECORD_AUDIO) else startVoice()
    }

    private fun startVoice() {
        if (backendUrl.isBlank()) {
            addSystemMessage("Ustaw Backend URL")
            return
        }
        assistantVoiceDraft = ""
        voice.start(backendUrl)
    }

    private fun requestScreenshot(target: CaptureTarget) {
        captureTarget = target
        ContextCompat.startForegroundService(this, Intent(this, CaptureService::class.java))
        capturePermission.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun uriToDataUrl(uriString: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Nie można odczytać obrazu")
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
        "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

data class ChatLine(val role: String, val text: String)
