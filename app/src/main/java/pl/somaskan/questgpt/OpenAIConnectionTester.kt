package pl.somaskan.questgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class OpenAITestResult(val ok: Boolean, val detail: String)

/**
 * Tests the same public OpenAI endpoints used by Chat and GPT Live.
 * These are deliberately small live requests, not just a DNS/model-list check.
 */
class OpenAIConnectionTester(
    context: android.content.Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private val store = OpenAICredentialStore(context.applicationContext)

    suspend fun testText(): OpenAITestResult = withContext(Dispatchers.IO) {
        val key = keyOrResult() ?: return@withContext OpenAITestResult(false, "Brak zapisanego klucza OpenAI API.")
        val model = validatedModel(store.textModel())
            ?: return@withContext OpenAITestResult(false, "Nieprawidłowa nazwa modelu tekstowego.")

        val body = JSONObject()
            .put("model", model)
            .put("input", "Odpowiedz tylko jednym słowem: OK")
            .put("max_output_tokens", 128)
        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use OpenAITestResult(false, OpenAIProtocol.errorMessage(response.code, raw))
                }
                val parsed = runCatching { OpenAIProtocol.parseResponse(raw) }.getOrNull()
                val marker = parsed?.text?.take(80)?.ifBlank { null }
                    ?: parsed?.id?.take(32)
                    ?: "HTTP ${response.code}"
                OpenAITestResult(true, "Responses API działa • $model • odpowiedź: $marker")
            }
        }.getOrElse { error ->
            OpenAITestResult(false, networkError(error))
        }
    }

    suspend fun testRealtime(): OpenAITestResult = withContext(Dispatchers.IO) {
        val key = keyOrResult() ?: return@withContext OpenAITestResult(false, "Brak zapisanego klucza OpenAI API.")
        val model = validatedModel(store.realtimeModel())
            ?: return@withContext OpenAITestResult(false, "Nieprawidłowa nazwa modelu Realtime.")

        val done = CountDownLatch(1)
        val result = AtomicReference<OpenAITestResult?>(null)
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$model")
            .header("Authorization", "Bearer $key")
            .build()

        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Validate the exact audio/session shape used by QuestGPT Live, including tools.
                val session = JSONObject()
                    .put("type", "realtime")
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

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("type")) {
                    "session.updated" -> {
                        result.compareAndSet(null, OpenAITestResult(true, "Realtime WebSocket + konfiguracja audio działają • $model"))
                        done.countDown()
                        webSocket.close(1000, "diagnostic-complete")
                    }
                    "error" -> {
                        val error = json.optJSONObject("error")
                        val message = error?.optString("message").orEmpty().ifBlank { "OpenAI Realtime zwrócił błąd konfiguracji sesji." }
                        result.compareAndSet(null, OpenAITestResult(false, message))
                        done.countDown()
                        webSocket.close(1000, "diagnostic-error")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val raw = runCatching { response?.body?.string().orEmpty() }.getOrDefault("")
                val detail = if (response != null) OpenAIProtocol.errorMessage(response.code, raw) else networkError(t)
                result.compareAndSet(null, OpenAITestResult(false, detail))
                done.countDown()
            }
        })

        val completed = runCatching { done.await(12, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!completed) {
            socket.cancel()
            OpenAITestResult(false, "Realtime: przekroczono 12 s bez potwierdzenia session.updated.")
        } else {
            result.get() ?: OpenAITestResult(false, "Realtime zakończył test bez wyniku diagnostycznego.")
        }
    }

    /** Lightweight model lookup retained for manual/specialized diagnostics. */
    suspend fun testModel(model: String): OpenAITestResult = withContext(Dispatchers.IO) {
        val key = keyOrResult() ?: return@withContext OpenAITestResult(false, "Brak zapisanego klucza OpenAI API.")
        val safeModel = validatedModel(model)
            ?: return@withContext OpenAITestResult(false, "Nieprawidłowa nazwa modelu: ${model.trim()}")
        val request = Request.Builder()
            .url("https://api.openai.com/v1/models/$safeModel")
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .get()
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.isSuccessful) OpenAITestResult(true, "Model $safeModel jest dostępny")
                else OpenAITestResult(false, OpenAIProtocol.errorMessage(response.code, raw))
            }
        }.getOrElse { error -> OpenAITestResult(false, networkError(error)) }
    }

    private fun keyOrResult(): String? = runCatching { store.readKey() }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun validatedModel(model: String): String? = model.trim().takeIf { MODEL_PATTERN.matches(it) }

    private fun networkError(error: Throwable): String =
        "Nie udało się połączyć z api.openai.com: ${error.message ?: error.javaClass.simpleName}"

    companion object {
        private const val SAMPLE_RATE = 24_000
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val MODEL_PATTERN = Regex("[A-Za-z0-9._:-]{2,100}")
    }
}
