package pl.somaskan.questgpt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import pl.somaskan.questgpt.adb.AdbAgent
import pl.somaskan.questgpt.adb.AdbVisionMonitor
import pl.somaskan.questgpt.adb.AgentToolCall
import pl.somaskan.questgpt.adb.QuestAgentRuntime
import java.util.concurrent.TimeUnit

class RealtimeVoiceClient(
    private val context: Context,
    private val onAssistantDelta: (String) -> Unit,
    private val onUserTranscript: (String) -> Unit,
    private val onState: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val allowBackgroundHandoff: Boolean = true,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var scope: CoroutineScope? = null
    private var recordJob: Job? = null
    private var visionJob: Job? = null
    @Volatile private var lastVisionSentAt = 0L
    @Volatile private var lastVisionHash: String? = null
    @Volatile private var startupPrompt: String? = null

    fun start(initialPrompt: String? = null) {
        if (socket != null || recordJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Brak uprawnienia do mikrofonu. Zezwól na mikrofon w Ustawieniach Questa.")
            return
        }
        if (OpenAICredentialStore(context).readKey().isNullOrBlank()) {
            onError("Brak klucza OpenAI API. Wejdź w Ustawienia > OpenAI i zapisz klucz.")
            return
        }

        if (allowBackgroundHandoff) {
            onState("Uruchamianie GPT Live…")
            VoiceAgentController.start(context.applicationContext, initialPrompt)
            return
        }

        startupPrompt = initialPrompt?.takeIf { it.isNotBlank() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        onState("Łączenie bezpośrednio z OpenAI…")
        scope?.launch {
            runCatching { connectDirect() }
                .onFailure {
                    onError(it.message ?: "Nie udało się uruchomić GPT Live")
                    stop()
                }
        }
    }

    private fun connectDirect() {
        val store = OpenAICredentialStore(context)
        val apiKey = store.readKey() ?: error("Brak klucza OpenAI API.")
        val model = store.realtimeModel()
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$model")
            .header("Authorization", "Bearer $apiKey")
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onState("Połączono z OpenAI • $model")
                runCatching {
                    configureSession(webSocket)
                    startAudio(webSocket)
                    refreshVisionContext(webSocket, force = true)
                    startVisionPump(webSocket)
                    speakStartupPrompt(webSocket)
                }.onFailure {
                    onError("Audio/Realtime: ${it.message ?: it.javaClass.simpleName}")
                    stop()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "response.output_audio.delta" -> {
                            val bytes = Base64.decode(json.getString("delta"), Base64.DEFAULT)
                            player?.write(bytes, 0, bytes.size)
                        }
                        "response.output_audio_transcript.delta" -> onAssistantDelta(json.optString("delta"))
                        "conversation.item.input_audio_transcription.completed" -> onUserTranscript(json.optString("transcript"))
                        "input_audio_buffer.speech_started" -> refreshVisionContext(webSocket)
                        "response.function_call_arguments.done" -> handleToolCall(webSocket, json)
                        "error" -> {
                            val error = json.optJSONObject("error")
                            val message = error?.optString("message").orEmpty().ifBlank { "Błąd OpenAI Realtime" }
                            onError(message)
                        }
                    }
                }.onFailure { onError(it.message ?: "Błąd danych Realtime") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                val suffix = response?.let { " • HTTP ${it.code}" }.orEmpty()
                onError("Połączenie GPT Live nie powiodło się$suffix: ${t.message ?: t.javaClass.simpleName}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                onState("Głos rozłączony • $code${reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
            }
        })
    }

    private fun speakStartupPrompt(webSocket: WebSocket) {
        val prompt = startupPrompt?.takeIf { it.isNotBlank() } ?: return
        startupPrompt = null
        scope?.launch {
            delay(450L)
            webSocket.send(userTextEvent(prompt).toString())
            webSocket.send(JSONObject().put("type", "response.create").toString())
        }
    }

    private fun userTextEvent(text: String): JSONObject = JSONObject()
        .put("type", "conversation.item.create")
        .put(
            "item",
            JSONObject()
                .put("type", "message")
                .put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text)))
        )

    private fun handleToolCall(webSocket: WebSocket, event: JSONObject) {
        val callId = event.optString("call_id")
        val name = event.optString("name")
        if (callId.isBlank() || name.isBlank()) return
        val arguments = runCatching { JSONObject(event.optString("arguments", "{}")) }.getOrDefault(JSONObject())
        scope?.launch {
            val output = if (!QuestAgentRuntime.agentControlEnabled) {
                "Sterowanie GPT zostało wyłączone przez użytkownika."
            } else {
                runCatching { AdbAgent.execute(AgentToolCall(callId, name, arguments)) }
                    .getOrElse { "BŁĄD: ${it.message ?: "akcja nie powiodła się"}" }
            }
            webSocket.send(
                JSONObject()
                    .put("type", "conversation.item.create")
                    .put(
                        "item",
                        JSONObject()
                            .put("type", "function_call_output")
                            .put("call_id", callId)
                            .put("output", output.take(4_000))
                    )
                    .toString()
            )
            delay(300L)
            refreshVisionContext(webSocket, force = true)
            webSocket.send(JSONObject().put("type", "response.create").toString())
        }
    }

    private fun refreshVisionContext(webSocket: WebSocket, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastVisionSentAt < 1_500L) return
        lastVisionSentAt = now
        scope?.launch {
            val observation = runCatching { AdbAgent.observe() }.getOrNull() ?: return@launch
            val content = JSONArray().apply {
                put(
                    JSONObject()
                        .put("type", "input_text")
                        .put(
                            "text",
                            "Aktualny kontekst Meta Quest 3. Aktywne okno: ${observation.currentActivity}. " +
                                "Rozmiar: ${observation.displaySize}. Drzewo UI:\n${observation.uiSummary.take(10_000)}"
                        )
                )
                observation.imageDataUrl?.takeIf { it.startsWith("data:image/") }?.let { image ->
                    put(JSONObject().put("type", "input_image").put("image_url", image).put("detail", "auto"))
                }
            }
            webSocket.send(
                JSONObject()
                    .put("type", "conversation.item.create")
                    .put(
                        "item",
                        JSONObject()
                            .put("type", "message")
                            .put("role", "user")
                            .put("content", content)
                    )
                    .toString()
            )
            lastVisionHash = AdbVisionMonitor.latest()?.hash
        }
    }

    private fun startVisionPump(webSocket: WebSocket) {
        visionJob?.cancel()
        visionJob = scope?.launch {
            while (isActive) {
                delay(900L)
                if (!QuestAgentRuntime.autoVisionEnabled) continue
                val frame = AdbVisionMonitor.latest() ?: continue
                if (frame.hash == lastVisionHash) continue
                val now = System.currentTimeMillis()
                if (now - lastVisionSentAt < 2_500L) continue
                lastVisionSentAt = now
                lastVisionHash = frame.hash
                webSocket.send(
                    JSONObject()
                        .put("type", "conversation.item.create")
                        .put(
                            "item",
                            JSONObject()
                                .put("type", "message")
                                .put("role", "user")
                                .put(
                                    "content",
                                    JSONArray()
                                        .put(JSONObject().put("type", "input_text").put("text", "Widok Meta Quest 3 zmienił się. Oto najnowsza klatka Auto Vision."))
                                        .put(JSONObject().put("type", "input_image").put("image_url", frame.dataUrl).put("detail", "auto"))
                                )
                        )
                        .toString()
                )
            }
        }
    }

    private fun configureSession(webSocket: WebSocket) {
        val session = JSONObject()
            .put("type", "realtime")
            .put(
                "instructions",
                "Jesteś QuestGPT działającym na Meta Quest 3. Odpowiadaj naturalnie i zwięźle w języku użytkownika. " +
                    "Obrazy i drzewo UI opisują aktualny widok. Używaj narzędzi tylko gdy użytkownik prosi o działanie na urządzeniu. " +
                    "Nie wykonuj zakupów, wysyłania wiadomości, zmian konta, instalacji/usuwania aplikacji ani resetu bez jednoznacznej prośby i wymaganego potwierdzenia."
            )
            .put("output_modalities", JSONArray().put("audio"))
            .put("audio", JSONObject().apply {
                put("input", JSONObject().apply {
                    put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
                    put("transcription", JSONObject().put("model", "gpt-realtime-whisper"))
                    put("turn_detection", JSONObject().apply {
                        put("type", "semantic_vad")
                        put("create_response", true)
                        put("interrupt_response", true)
                        put("eagerness", "auto")
                    })
                })
                put("output", JSONObject().apply {
                    put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
                    put("voice", "marin")
                })
            })

        val tools = AdbAgent.realtimeTools()
        if (tools.length() > 0) {
            session.put("tools", tools)
            session.put("tool_choice", "auto")
        }
        webSocket.send(JSONObject().put("type", "session.update").put("session", session).toString())
    }

    private fun startAudio(webSocket: WebSocket) {
        val minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minIn > 0) { "Quest nie zwrócił prawidłowego bufora wejścia audio ($minIn)." }
        val inputFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val createdRecorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(inputFormat)
            .setBufferSizeInBytes(maxOf(minIn * 2, 8192))
            .build()
        check(createdRecorder.state == AudioRecord.STATE_INITIALIZED) { "Nie udało się zainicjalizować mikrofonu." }
        recorder = createdRecorder

        val minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minOut > 0) { "Quest nie zwrócił prawidłowego bufora wyjścia audio ($minOut)." }
        val outputFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val createdPlayer = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(outputFormat)
            .setBufferSizeInBytes(maxOf(minOut * 2, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(createdPlayer.state == AudioTrack.STATE_INITIALIZED) { "Nie udało się zainicjalizować dźwięku odpowiedzi." }
        player = createdPlayer
        createdPlayer.play()
        createdRecorder.startRecording()
        check(createdRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Mikrofon nie rozpoczął nagrywania." }

        recordJob = scope?.launch {
            val buffer = ByteArray(4096)
            while (isActive) {
                val n = recorder?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: -1
                if (n > 0) {
                    webSocket.send(
                        JSONObject()
                            .put("type", "input_audio_buffer.append")
                            .put("audio", Base64.encodeToString(buffer, 0, n, Base64.NO_WRAP))
                            .toString()
                    )
                } else if (n < 0) {
                    onError("Mikrofon zwrócił błąd odczytu: $n")
                    break
                }
            }
        }
    }

    fun stop() {
        recordJob?.cancel()
        recordJob = null
        visionJob?.cancel()
        visionJob = null
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        runCatching { player?.stop() }
        player?.release()
        player = null
        socket?.close(1000, "user")
        socket = null
        scope?.cancel()
        scope = null
        lastVisionSentAt = 0L
        lastVisionHash = null
        startupPrompt = null
        onState("Głos wyłączony")
    }

    companion object {
        private const val SAMPLE_RATE = 24_000
    }
}
