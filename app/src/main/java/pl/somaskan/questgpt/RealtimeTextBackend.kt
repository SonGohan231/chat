package pl.somaskan.questgpt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import pl.somaskan.questgpt.adb.AdbAgent
import pl.somaskan.questgpt.adb.AdbObservation
import pl.somaskan.questgpt.adb.AgentToolCall
import pl.somaskan.questgpt.adb.QuestAgentRuntime
import pl.somaskan.questgpt.adb.WirelessAdbController

/**
 * Native Quest text transport that talks directly to OpenAI Realtime with a short-lived
 * client secret. This avoids relying on CloudFront POST support while keeping the
 * long-lived OpenAI API key on the server.
 */
class RealtimeTextBackend(
    private val http: OkHttpClient = OkHttpClient(),
) {
    private data class Credential(val token: String, val model: String)

    private class TurnState {
        val result = CompletableDeferred<String>()
        val text = StringBuilder()
        val toolCalls = mutableListOf<AgentToolCall>()
        var rounds = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val turnMutex = Mutex()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connectDeferred: CompletableDeferred<Unit>? = null
    @Volatile private var currentTurn: TurnState? = null
    @Volatile private var connectedBackend: String? = null

    suspend fun respond(
        baseUrl: String,
        text: String,
        imageDataUrl: String?,
    ): String = turnMutex.withLock {
        val backend = QuestEndpoints.resolveBackend(baseUrl)
        ensureConnected(backend)
        val webSocket = socket ?: error("Brak połączenia Realtime")
        configureSession(webSocket)

        QuestAgentRuntime.resetTurn()
        val observation = currentObservation()
        val turn = TurnState()
        currentTurn = turn

        try {
            webSocket.send(userMessage(text, imageDataUrl, observation).toString())
            webSocket.send(JSONObject().put("type", "response.create").toString())
            withTimeout(TURN_TIMEOUT_MS) { turn.result.await() }
        } finally {
            if (currentTurn === turn) currentTurn = null
        }
    }

    private suspend fun ensureConnected(backend: String) {
        val existing = connectDeferred
        if (socket != null && existing?.isCompleted == true && connectedBackend == backend) return

        if (socket != null && connectedBackend != backend) {
            socket?.close(1000, "backend changed")
            socket = null
            connectDeferred = null
        }

        val inFlight = connectDeferred
        if (inFlight != null && !inFlight.isCompleted) {
            withTimeout(CONNECT_TIMEOUT_MS) { inFlight.await() }
            return
        }

        val deferred = CompletableDeferred<Unit>()
        connectDeferred = deferred
        val credential = fetchCredential(backend)
        connectedBackend = backend
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=${credential.model}")
            .header("Authorization", "Bearer ${credential.token}")
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                configureSession(webSocket)
                deferred.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerEvent(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                connectedBackend = null
                if (!deferred.isCompleted) deferred.completeExceptionally(t)
                currentTurn?.result?.completeExceptionally(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                connectedBackend = null
                currentTurn?.result?.completeExceptionally(
                    IllegalStateException("Realtime rozłączony: $code $reason")
                )
            }
        })

        withTimeout(CONNECT_TIMEOUT_MS) { deferred.await() }
    }

    private suspend fun fetchCredential(backend: String): Credential {
        val credential = RealtimeCredentialProvider.fetch(backend, "text")
        return Credential(credential.token, credential.model)
    }

    private fun configureSession(webSocket: WebSocket) {
        val tools = AdbAgent.realtimeTools()
        val session = JSONObject()
            .put("type", "realtime")
            .put(
                "instructions",
                "Jesteś QuestGPT działającym na Meta Quest 3. Odpowiadaj po polsku, chyba że użytkownik używa innego języka. " +
                    "Masz aktualny obraz ekranu i drzewo UI, gdy ADB jest połączone. Jeśli użytkownik prosi o wykonanie czynności na urządzeniu, użyj dostępnych narzędzi, a po każdej akcji oceń nową obserwację. " +
                    "Nie wykonuj zakupów, wysyłania wiadomości, zmian konta, resetu urządzenia ani innych istotnych działań bez jednoznacznej prośby użytkownika."
            )
            .put("output_modalities", JSONArray().put("text"))
            .put("tools", tools)
        if (tools.length() > 0) session.put("tool_choice", "auto")
        webSocket.send(
            JSONObject()
                .put("type", "session.update")
                .put("session", session)
                .toString()
        )
    }

    private fun userMessage(
        text: String,
        explicitImage: String?,
        observation: AdbObservation?,
    ): JSONObject {
        val content = JSONArray()
        val contextText = buildString {
            append(text)
            if (observation != null) {
                append("\n\nAktualny kontekst Meta Quest 3:")
                append("\nAktywne okno: ").append(observation.currentActivity)
                append("\nRozmiar: ").append(observation.displaySize)
                append("\nScreenshot pusty/chroniony: ").append(observation.likelyBlank)
                append("\nDrzewo UI:\n").append(observation.uiSummary.take(14_000))
            }
        }
        content.put(JSONObject().put("type", "input_text").put("text", contextText))
        explicitImage?.let {
            content.put(JSONObject().put("type", "input_image").put("image_url", it).put("detail", "auto"))
        }
        observation?.imageDataUrl?.takeIf { it != explicitImage }?.let {
            content.put(JSONObject().put("type", "input_image").put("image_url", it).put("detail", "auto"))
        }
        return JSONObject()
            .put("type", "conversation.item.create")
            .put(
                "item",
                JSONObject()
                    .put("type", "message")
                    .put("role", "user")
                    .put("content", content)
            )
    }

    private fun handleServerEvent(webSocket: WebSocket, raw: String) {
        runCatching {
            val event = JSONObject(raw)
            val turn = currentTurn
            when (event.optString("type")) {
                "response.output_text.delta", "response.text.delta" -> {
                    turn?.text?.append(event.optString("delta"))
                }

                "response.function_call_arguments.done" -> {
                    if (turn != null) {
                        val callId = event.optString("call_id")
                        val name = event.optString("name")
                        if (callId.isNotBlank() && name.isNotBlank()) {
                            val args = runCatching {
                                JSONObject(event.optString("arguments", "{}"))
                            }.getOrDefault(JSONObject())
                            turn.toolCalls += AgentToolCall(callId, name, args)
                        }
                    }
                }

                "response.done" -> {
                    if (turn == null || turn.result.isCompleted) return@runCatching
                    if (turn.text.isEmpty()) extractDoneText(event)?.let { turn.text.append(it) }
                    if (turn.toolCalls.isNotEmpty()) {
                        continueAfterTools(webSocket, turn)
                    } else {
                        turn.result.complete(turn.text.toString().trim().ifBlank { "Gotowe." })
                    }
                }

                "error" -> {
                    val message = event.optJSONObject("error")?.optString("message") ?: "Błąd OpenAI Realtime"
                    turn?.result?.completeExceptionally(IllegalStateException(message))
                }
            }
        }.onFailure { error ->
            currentTurn?.result?.completeExceptionally(error)
        }
    }

    private fun continueAfterTools(webSocket: WebSocket, turn: TurnState) {
        if (turn.rounds >= MAX_AGENT_ROUNDS) {
            turn.result.complete(
                turn.text.toString().trim().ifBlank {
                    "Zatrzymałem automatyczne sterowanie po $MAX_AGENT_ROUNDS rundach, aby uniknąć pętli."
                }
            )
            return
        }
        turn.rounds += 1
        val calls = turn.toolCalls.take(MAX_TOOLS_PER_ROUND)
        turn.toolCalls.clear()

        scope.launch {
            calls.forEach { call ->
                val output = if (!QuestAgentRuntime.agentControlEnabled) {
                    "Sterowanie GPT zostało wyłączone przez użytkownika."
                } else {
                    runCatching { AdbAgent.execute(call) }
                        .getOrElse { "BŁĄD: ${it.message ?: "akcja nie powiodła się"}" }
                }
                webSocket.send(
                    JSONObject()
                        .put("type", "conversation.item.create")
                        .put(
                            "item",
                            JSONObject()
                                .put("type", "function_call_output")
                                .put("call_id", call.callId)
                                .put("output", output.take(4_000))
                        )
                        .toString()
                )
            }

            delay(350L)
            currentObservation()?.let { observation ->
                webSocket.send(
                    userMessage(
                        "Stan Questa po wykonaniu poprzednich narzędzi. Sprawdź rezultat i kontynuuj tylko jeśli to potrzebne.",
                        null,
                        observation,
                    ).toString()
                )
            }
            configureSession(webSocket)
            webSocket.send(JSONObject().put("type", "response.create").toString())
        }
    }

    private suspend fun currentObservation(): AdbObservation? {
        val connected = runCatching {
            WirelessAdbController(QuestApp.appContext).isConnected()
        }.getOrDefault(false)
        if (!connected) return null
        return runCatching { AdbAgent.observe() }.getOrNull()
    }

    private fun extractDoneText(event: JSONObject): String? {
        val response = event.optJSONObject("response") ?: return null
        val output = response.optJSONArray("output") ?: return null
        val pieces = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") {
                    part.optString("text").takeIf { it.isNotBlank() }?.let(pieces::add)
                }
            }
        }
        return pieces.joinToString("\n").takeIf { it.isNotBlank() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val TURN_TIMEOUT_MS = 90_000L
        private const val MAX_AGENT_ROUNDS = 8
        private const val MAX_TOOLS_PER_ROUND = 3
    }
}
