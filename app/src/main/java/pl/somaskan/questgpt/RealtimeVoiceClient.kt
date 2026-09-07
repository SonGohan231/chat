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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import pl.somaskan.questgpt.adb.AdbAgent
import pl.somaskan.questgpt.adb.AdbVisionMonitor
import pl.somaskan.questgpt.adb.AgentToolCall
import pl.somaskan.questgpt.adb.QuestAgentRuntime

class RealtimeVoiceClient(
    private val context: Context,
    private val onAssistantDelta: (String) -> Unit,
    private val onUserTranscript: (String) -> Unit,
    private val onState: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val http = OkHttpClient()
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var scope: CoroutineScope? = null
    private var recordJob: Job? = null
    private var visionJob: Job? = null
    @Volatile private var lastVisionSentAt = 0L
    @Volatile private var lastVisionHash: String? = null
    @Volatile private var startupPrompt: String? = null

    fun start(baseUrl: String, initialPrompt: String? = null) {
        if (socket != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Brak uprawnienia do mikrofonu")
            return
        }

        startupPrompt = initialPrompt?.takeIf { it.isNotBlank() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        onState("Łączenie z OpenAI...")
        scope?.launch {
            runCatching { fetchRealtimeCredential(baseUrl) }
                .onSuccess { credential -> connectDirect(credential) }
                .onFailure {
                    onError(it.message ?: "Nie udało się uzyskać tokenu Realtime")
                    stop()
                }
        }
    }

    private data class RealtimeCredential(val token: String, val model: String)

    private fun fetchRealtimeCredential(baseUrl: String): RealtimeCredential {
        val backend = QuestEndpoints.resolveBackend(baseUrl)
        val request = Request.Builder()
            .url(backend + "/api/realtime-token")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).optString("error") }.getOrNull().orEmpty()
                error(if (message.isNotBlank()) message else "Realtime backend ${response.code}: $raw")
            }
            val json = JSONObject(raw)
            val token = json.optString("value")
            val model = json.optString("model", "gpt-realtime")
            check(token.isNotBlank()) { "Backend nie zwrócił krótkotrwałego tokenu Realtime" }
            return RealtimeCredential(token, model)
        }
    }

    private fun connectDirect(credential: RealtimeCredential) {
        if (socket != null) return
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=${credential.model}")
            .header("Authorization", "Bearer ${credential.token}")
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onState("Połączono z OpenAI • Vision/Agent aktywny")
                configureSession(webSocket)
                startAudio(webSocket)
                refreshVisionContext(webSocket, force = true)
                startVisionPump(webSocket)
                speakStartupPrompt(webSocket)
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
                        "error" -> onError(json.optJSONObject("error")?.optString("message") ?: "Realtime error")
                    }
                }.onFailure { onError(it.message ?: "Błąd Realtime") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError(t.message ?: "Utracono połączenie głosowe")
                stop()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onState("Głos rozłączony")
            }
        })
    }

    private fun speakStartupPrompt(webSocket: WebSocket) {
        val prompt = startupPrompt?.takeIf { it.isNotBlank() } ?: return
        startupPrompt = null
        scope?.launch {
            delay(450L)
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
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "input_text")
                                        .put("text", prompt)
                                )
                            )
                    )
                    .toString()
            )
            webSocket.send(JSONObject().put("type", "response.create").toString())
        }
    }

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
                    .getOrElse { "BŁĄD: ${it.message}" }
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
            delay(350L)
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
                put(JSONObject().apply {
                    put("type", "input_text")
                    put(
                        "text",
                        "Aktualny kontekst Meta Quest 3. Aktywne okno: ${observation.currentActivity}. " +
                            "Rozmiar: ${observation.displaySize}. Drzewo UI:\n${observation.uiSummary.take(12_000)}"
                    )
                })
                observation.imageDataUrl?.let { image ->
                    put(JSONObject().apply {
                        put("type", "input_image")
                        put("image_url", image)
                        put("detail", "auto")
                    })
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
                delay(700L)
                if (!QuestAgentRuntime.autoVisionEnabled) continue
                val frame = AdbVisionMonitor.latest() ?: continue
                if (frame.hash == lastVisionHash) continue
                val now = System.currentTimeMillis()
                if (now - lastVisionSentAt < 2_000L) continue
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
                                        .put(JSONObject().put("type", "input_text").put("text", "Widok Questa zmienił się. Oto najnowsza klatka Auto Vision."))
                                        .put(JSONObject().put("type", "input_image").put("image_url", frame.dataUrl).put("detail", "auto"))
                                )
                        )
                        .toString()
                )
            }
        }
    }

    private fun configureSession(webSocket: WebSocket) {
        val event = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("type", "realtime")
                put(
                    "instructions",
                    "Jesteś asystentem działającym na Meta Quest 3. Odpowiadaj naturalnie i zwięźle w języku użytkownika. " +
                        "Obrazy i drzewo UI opisują aktualny widok użytkownika. Gdy użytkownik prosi o wykonanie czynności na urządzeniu, używaj dostępnych narzędzi i po każdej akcji sprawdzaj nowy widok. " +
                        "Nie wykonuj zakupów, wysyłania wiadomości, zmian konta, instalacji/usuwania aplikacji, resetu urządzenia ani innych istotnych działań bez jednoznacznej prośby użytkownika."
                )
                put("output_modalities", JSONArray().put("audio"))
                put("tools", AdbAgent.realtimeTools())
                put("tool_choice", "auto")
                put("audio", JSONObject().apply {
                    put("input", JSONObject().apply {
                        put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
                        put("transcription", JSONObject().put("model", "gpt-realtime-whisper"))
                        put("turn_detection", JSONObject().apply {
                            put("type", "semantic_vad")
                            put("create_response", true)
                            put("interrupt_response", true)
                            put("eagerness", "auto")
                        })
                    })
                    put("output", JSONObject().apply {
                        put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
                        put("voice", "marin")
                    })
                })
            })
        }
        webSocket.send(event.toString())
    }

    private fun startAudio(webSocket: WebSocket) {
        val inputFormat = AudioFormat.Builder()
            .setSampleRate(24000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val outputFormat = AudioFormat.Builder()
            .setSampleRate(24000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val minIn = AudioRecord.getMinBufferSize(24000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(inputFormat)
            .setBufferSizeInBytes(maxOf(minIn, 4096))
            .build()

        val minOut = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(outputFormat)
            .setBufferSizeInBytes(maxOf(minOut, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }

        recorder?.startRecording()
        recordJob = scope?.launch {
            val buffer = ByteArray(4096)
            while (isActive) {
                val n = recorder?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: -1
                if (n > 0) {
                    val b64 = Base64.encodeToString(buffer.copyOf(n), Base64.NO_WRAP)
                    webSocket.send(
                        JSONObject()
                            .put("type", "input_audio_buffer.append")
                            .put("audio", b64)
                            .toString()
                    )
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
}
