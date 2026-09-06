package pl.somaskan.questgpt

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val backend = OpenAIBackend()
    private val messages = mutableStateListOf<ChatLine>()
    private var previousResponseId: String? = null
    private var backendUrl by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var voiceState by mutableStateOf("Głos wyłączony")
    private var assistantVoiceDraft by mutableStateOf("")

    private lateinit var voice: RealtimeVoiceClient

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uiScope.launch {
            runCatching { uriToDataUrl(uri.toString()) }
                .onSuccess { sendMessage("Co widzisz na tym obrazie?", it) }
                .onFailure { messages += ChatLine("system", "Błąd obrazu: ${it.message}") }
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else messages += ChatLine("system", "Mikrofon nie został udostępniony")
    }

    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            uiScope.launch {
                busy = true
                runCatching {
                    val png = CaptureHelper.capturePng(this@MainActivity, result.resultCode, result.data!!)
                    "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
                }.onSuccess {
                    sendMessage("Przeanalizuj ten screenshot z mojego Questa.", it)
                }.onFailure {
                    messages += ChatLine("system", "Screenshot: ${it.message}")
                    busy = false
                }
                stopService(Intent(this@MainActivity, CaptureService::class.java))
            }
        } else {
            stopService(Intent(this, CaptureService::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backendUrl = getSharedPreferences("questgpt", MODE_PRIVATE)
            .getString("backend", "http://10.0.2.2:8787") ?: "http://10.0.2.2:8787"

        voice = RealtimeVoiceClient(
            applicationContext,
            onAssistantDelta = { delta -> runOnUiThread {
                assistantVoiceDraft += delta
                val index = messages.indexOfLast { it.role == "assistant_live" }
                if (index >= 0) messages[index] = ChatLine("assistant_live", assistantVoiceDraft)
                else messages += ChatLine("assistant_live", assistantVoiceDraft)
            } },
            onUserTranscript = { text -> runOnUiThread { if (text.isNotBlank()) messages += ChatLine("user", text) } },
            onState = { state -> runOnUiThread { voiceState = state } },
            onError = { error -> runOnUiThread { messages += ChatLine("system", error) } }
        )

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    QuestPanel()
                }
            }
        }
    }

    override fun onDestroy() {
        voice.stop()
        super.onDestroy()
    }

    @Composable
    private fun QuestPanel() {
        var draft by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("QuestGPT", style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = backendUrl,
                onValueChange = {
                    backendUrl = it
                    getSharedPreferences("questgpt", MODE_PRIVATE).edit().putString("backend", it).apply()
                },
                label = { Text("Backend URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(voiceState, style = MaterialTheme.typography.labelMedium)

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { line ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                when (line.role) {
                                    "user" -> "Ty"
                                    "assistant", "assistant_live" -> "GPT"
                                    else -> "System"
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(line.text)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Napisz wiadomość") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        sendMessage(text, null)
                    }
                }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (busy) "..." else "Wyślij") }

                Button(onClick = { toggleVoice() }, modifier = Modifier.weight(1f)) {
                    Text(if (voiceState.startsWith("Połączono")) "Stop mic" else "Mikrofon")
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickImage.launch("image/*") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("Zdjęcie")
                }
                Button(onClick = { requestScreenshot() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("Screenshot")
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }

    private fun sendMessage(text: String, imageDataUrl: String?) {
        if (backendUrl.isBlank()) {
            messages += ChatLine("system", "Ustaw Backend URL")
            return
        }
        messages += ChatLine("user", if (imageDataUrl == null) text else "$text [obraz]")
        busy = true
        uiScope.launch {
            runCatching { backend.respond(backendUrl, text, imageDataUrl, previousResponseId) }
                .onSuccess {
                    previousResponseId = it.responseId
                    messages += ChatLine("assistant", it.text)
                }
                .onFailure { messages += ChatLine("system", it.message ?: "Błąd") }
            busy = false
        }
    }

    private fun toggleVoice() {
        if (voiceState.startsWith("Połączono")) {
            voice.stop()
            assistantVoiceDraft = ""
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else startVoice()
    }

    private fun startVoice() {
        assistantVoiceDraft = ""
        voice.start(backendUrl)
    }

    private fun requestScreenshot() {
        ContextCompat.startForegroundService(this, Intent(this, CaptureService::class.java))
        val manager = getSystemService(MediaProjectionManager::class.java)
        capturePermission.launch(manager.createScreenCaptureIntent())
    }

    private suspend fun uriToDataUrl(uriString: String): String = withContext(Dispatchers.IO) {
        val uri = android.net.Uri.parse(uriString)
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Nie można odczytać obrazu")
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
        "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

data class ChatLine(val role: String, val text: String)
