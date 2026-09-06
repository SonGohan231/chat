package pl.somaskan.questgpt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

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

    fun start(baseUrl: String) {
        if (socket != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Brak uprawnienia do mikrofonu")
            return
        }

        val wsUrl = baseUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/api/realtime"

        val request = Request.Builder().url(wsUrl).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onState("Połączono — mów")
                configureSession(webSocket)
                startAudio(webSocket)
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

    private fun configureSession(webSocket: WebSocket) {
        val event = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("type", "realtime")
                put("instructions", "Odpowiadaj naturalnie i krótko. Używaj języka użytkownika.")
                put("output_modalities", org.json.JSONArray().put("audio"))
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
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
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
                    webSocket.send(JSONObject().put("type", "input_audio_buffer.append").put("audio", b64).toString())
                }
            }
        }
    }

    fun stop() {
        recordJob?.cancel()
        recordJob = null
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
        onState("Głos wyłączony")
    }
}
