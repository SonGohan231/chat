package pl.somaskan.questgpt2

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

class VoiceService : Service() {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS).build()
    private val gate = SessionGate()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var audio: AudioEngine? = null
    @Volatile private var ended = false
    private var lastFrameSequence = -1L
    private var lastImageAt = 0L
    private val imageItems = ArrayDeque<String>()
    private val pendingImages = mutableSetOf<String>()
    private var replying = false
    private var greeting = false
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stopVoice("Live zatrzymane po uśpieniu urządzenia.") }
    }
    override fun onCreate() {
        super.onCreate(); Hub.voiceService = this
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent == null || intent.action == "STOP") { stopVoice(); return START_NOT_STICKY }
        if(socket != null) return START_NOT_STICKY
        runCatching {
            startForeground(202, Notifications.build(this, "QuestGPT · rozmowa Live", "Mikrofon aktywny. Dotknij, aby otworzyć mini panel."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            val store = CredentialStore(this)
            val key = store.readKey() ?: error("Zapisz klucz API w Połączeniu.")
            greeting = intent.getBooleanExtra("greeting", false)
            Hub.change { it.copy(voice = "Łączę z OpenAI…", voiceActive = true, voiceReady = false, muted = false, note = "") }
            val request = Request.Builder().url("wss://api.openai.com/v1/realtime?model=${store.realtimeModel()}")
                .header("Authorization", "Bearer $key").build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Hub.main.post {
                        if(ended) { webSocket.close(1000, "Stopped"); return@post }
                        webSocket.send(Protocol.session(store.realtimeModel()).toString())
                        Hub.change { it.copy(voice = "Konfiguruję rozmowę…") }
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    Hub.main.post { if(!ended) runCatching { event(JSONObject(text)) }.onFailure { stopVoice("Błąd Live: ${Protocol.safe(it.message.orEmpty())}") } }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val reason = response?.let { Protocol.error(it.code, "") } ?: "Połączenie Live przerwane. Sprawdź Wi-Fi i uruchom je ponownie."
                    Hub.main.post { stopVoice(reason) }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { Hub.main.post { stopVoice("Rozmowa zakończona. Możesz uruchomić Live ponownie.") } }
            })
            Hub.main.postDelayed({ if(!gate.ready && !ended) stopVoice("OpenAI nie potwierdziło sesji. Sprawdź klucz i model Live w Połączeniu.") }, 20000)
            Hub.main.postDelayed({ if(!ended) stopVoice("Sesja osiągnęła 55 minut. Włącz Live ponownie, aby kontynuować.") }, 55 * 60 * 1000L)
        }.onFailure { stopVoice("Nie można włączyć Live: ${Protocol.safe(it.message.orEmpty())}") }
        return START_NOT_STICKY
    }
    private fun send(event: JSONObject): Boolean {
        val ws = socket ?: return false
        if(ended || ws.queueSize() > 1024 * 1024) { Hub.main.post { stopVoice("Połączenie nie nadąża. Sesja zatrzymana, aby nie wysyłać opóźnionego dźwięku.") }; return false }
        return ws.send(event.toString())
    }
    private fun event(e: JSONObject) {
        when(e.optString("type")) {
            "session.updated" -> if(gate.acknowledge("session.updated")) {
                Hub.state.messages.filter { it.complete && it.role in setOf("user", "assistant") }.takeLast(12)
                    .forEach { send(Protocol.item(it.text.take(3000), role = it.role)) }
                val engine = AudioEngine(this, { pcm ->
                    if(gate.ready && !ended) send(JSONObject().put("type", "input_audio_buffer.append").put("audio", Base64.encodeToString(pcm, Base64.NO_WRAP)))
                }, { message -> Hub.main.post { stopVoice(message) } })
                audio = engine
                engine.start()
                Hub.change { it.copy(voice = "Słucham", voiceReady = true) }
                Hub.main.post(imagePump)
                if(greeting) sendText("Przywitaj mnie jednym zdaniem i zapytaj, w czym możesz pomóc.", emptyList())
            }
            "input_audio_buffer.speech_started" -> {
                interrupt(false); Hub.change { it.copy(voice = "Słucham…") }
                sendScreen(true)
            }
            "input_audio_buffer.committed" -> {
                sendScreen(true)
                createResponse()
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val text = e.optString("transcript").trim()
                if(text.isNotBlank()) Hub.message("user", text, "user-${e.optString("item_id")}")
            }
            "conversation.item.input_audio_transcription.failed" -> Hub.note("Nie udało się zapisać transkrypcji. Rozmowa audio może działać dalej.")
            "response.created" -> { replying = true; Hub.change { it.copy(voice = "Odpowiadam…") } }
            "response.output_audio.delta" -> {
                audio?.enqueue(e.getString("item_id"), Base64.decode(e.getString("delta"), Base64.DEFAULT))
            }
            "response.output_audio_transcript.delta", "response.output_text.delta" -> Hub.delta("live-${e.optString("item_id")}", e.optString("delta"))
            "response.output_audio_transcript.done", "response.output_text.done" -> Hub.complete("live-${e.optString("item_id")}")
            "response.done" -> {
                replying = false
                val response = e.optJSONObject("response")
                if(response?.optString("status") == "failed") {
                    val err = response.optJSONObject("status_details")?.optJSONObject("error")
                    stopVoice("OpenAI nie mogło odpowiedzieć: ${Protocol.safe(err?.optString("message").orEmpty())}")
                } else Hub.change { it.copy(voice = if(it.muted) "Mikrofon wyciszony" else "Słucham") }
            }
            "conversation.item.created" -> {
                val id = e.optJSONObject("item")?.optString("id")
                if(id != null && pendingImages.remove(id) && Hub.state.sharing) {
                    Hub.change { it.copy(sentFrames = it.sentFrames + 1, lastSentAt = SystemClock.elapsedRealtime()) }
                }
            }
            "error" -> {
                val error = e.optJSONObject("error") ?: JSONObject()
                val code = error.optString("code")
                if(code !in setOf("response_cancel_not_active", "item_delete_invalid_item", "conversation_already_has_active_response")) {
                    stopVoice("Live: ${Protocol.safe(error.optString("message"))}")
                } else if(code == "conversation_already_has_active_response") Hub.note("Poczekaj na odpowiedź lub użyj Przerwij.")
            }
        }
    }
    private val imagePump = object : Runnable {
        override fun run() { if(!ended && gate.ready) { sendScreen(false); Hub.main.postDelayed(this, 2000) } }
    }
    private fun sendScreen(force: Boolean) {
        val s = Hub.state
        val frame = s.frame
        val now = SystemClock.elapsedRealtime()
        if(!s.sharing || !gate.ready) return
        if(!Protocol.fresh(frame, now) || frame == null) {
            if(imageItems.isNotEmpty()) clearScreenContext("Aktualny ekran jest niedostępny lub pusty. Nie opisuj poprzednich klatek jako obecnego widoku.")
            return
        }
        if(frame.sequence == lastFrameSequence) return
        if(!force && now - lastImageAt < 1950L) return
        val id = "screen_${now}_${frame.sequence}"
        if(send(Protocol.item("Udostępniany ekran, klatka ${frame.sequence}. To obraz z chwili przechwycenia; odpowiedz dopiero na pytanie.", listOf(frame.dataUrl), id))) {
            lastFrameSequence = frame.sequence; lastImageAt = now
            pendingImages.add(id); imageItems.addLast(id)
            while(imageItems.size > 3) {
                val old = imageItems.removeFirst(); pendingImages.remove(old)
                send(JSONObject().put("type", "conversation.item.delete").put("item_id", old))
            }
        }
    }
    fun clearScreenContext(reason: String = "Udostępnianie ekranu zostało zatrzymane. Nie masz aktualnego widoku.") {
        val clear: () -> Unit = {
            while(imageItems.isNotEmpty()) send(JSONObject().put("type", "conversation.item.delete").put("item_id", imageItems.removeFirst()))
            pendingImages.clear(); lastFrameSequence = -1L
            if(gate.ready && !ended) send(Protocol.item(reason))
            Unit
        }
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) clear() else Hub.main.post(clear)
    }
    fun sendText(text: String, images: List<String>): Boolean {
        if(!gate.ready || ended) return false
        if(replying) { Hub.note("Najpierw użyj Przerwij albo poczekaj na koniec odpowiedzi."); return false }
        sendScreen(true)
        if(!send(Protocol.item(text, images))) return false
        createResponse(); return true
    }
    private fun createResponse() {
        if(!gate.ready || ended) return
        send(JSONObject().put("type", "response.create")); replying = true
    }
    fun mute() {
        val a = audio ?: return
        a.muted = !a.muted
        if(a.muted) send(JSONObject().put("type", "input_audio_buffer.clear"))
        Hub.change { it.copy(muted = a.muted, micLevel = 0, voice = if(a.muted) "Mikrofon wyciszony" else "Słucham") }
    }
    fun interrupt(cancel: Boolean = true) {
        if(cancel && replying) send(JSONObject().put("type", "response.cancel"))
        audio?.interrupt()?.let { (id, ms) -> send(JSONObject().put("type", "conversation.item.truncate").put("item_id", id)
            .put("content_index", 0).put("audio_end_ms", ms)) }
        replying = false
    }
    @Synchronized fun stopVoice(reason: String = "Głos wyłączony") {
        if(ended) return
        ended = true; gate.close()
        socket?.close(1000, "User session ended"); socket = null
        audio?.stop(); audio = null
        Hub.change { it.copy(voice = reason, voiceActive = false, voiceReady = false, micLevel = 0, muted = false) }
        Hub.main.removeCallbacks(imagePump)
        Hub.main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    override fun onDestroy() {
        stopVoice()
        if(Hub.voiceService === this) Hub.voiceService = null
        runCatching { unregisterReceiver(screenOff) }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
