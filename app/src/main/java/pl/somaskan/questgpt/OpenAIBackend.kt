package pl.somaskan.questgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pl.somaskan.questgpt.adb.AdbAgent
import pl.somaskan.questgpt.adb.AdbObservation
import pl.somaskan.questgpt.adb.QuestAgentRuntime
import pl.somaskan.questgpt.adb.WirelessAdbController
import java.util.concurrent.TimeUnit

/**
 * Direct OpenAI Responses API transport for a private sideloaded Quest build.
 * The user's key is read from Android Keystore-backed local storage at request time;
 * no AppDeploy/backend bridge is involved and the key is never compiled into the APK.
 */
class OpenAIBackend(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build(),
) {
    data class Reply(val text: String, val responseId: String?)

    suspend fun respond(
        text: String,
        imageDataUrl: String?,
        previousResponseId: String?,
    ): Reply = withContext(Dispatchers.IO) {
        val store = OpenAICredentialStore(QuestApp.appContext)
        val apiKey = runCatching { store.readKey() }.getOrNull()
            ?: error("Brak klucza OpenAI API. Wejdź w Ustawienia > OpenAI, zapisz swój klucz i uruchom test połączenia.")
        val model = store.textModel()

        QuestAgentRuntime.resetTurn()
        val firstObservation = currentObservation()
        var step = postResponse(
            apiKey = apiKey,
            model = model,
            input = JSONArray().put(userMessage(text, imageDataUrl, firstObservation)),
            previousResponseId = previousResponseId,
            allowTools = firstObservation != null && QuestAgentRuntime.agentControlEnabled,
        )

        var rounds = 0
        while (step.toolCalls.isNotEmpty() && rounds < MAX_AGENT_ROUNDS) {
            rounds++
            val nextInput = JSONArray()
            step.toolCalls.take(MAX_TOOLS_PER_ROUND).forEach { call ->
                val output = if (!QuestAgentRuntime.agentControlEnabled) {
                    "Sterowanie GPT zostało wyłączone przez użytkownika."
                } else {
                    runCatching { AdbAgent.execute(call) }
                        .getOrElse { "BŁĄD: ${it.message ?: "akcja nie powiodła się"}" }
                }
                nextInput.put(
                    JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", call.callId)
                        .put("output", output.take(4_000))
                )
            }

            delay(300L)
            val observation = currentObservation()
            if (observation != null) {
                nextInput.put(
                    userMessage(
                        text = "Stan Meta Quest 3 po wykonaniu narzędzi. Oceń rezultat i kontynuuj tylko jeśli to potrzebne.",
                        explicitImage = null,
                        observation = observation,
                    )
                )
            }
            step = postResponse(
                apiKey = apiKey,
                model = model,
                input = nextInput,
                previousResponseId = step.id,
                allowTools = observation != null && QuestAgentRuntime.agentControlEnabled,
            )
        }

        val finalText = when {
            step.text.isNotBlank() -> step.text
            step.toolCalls.isNotEmpty() -> "Zatrzymałem sterowanie po $MAX_AGENT_ROUNDS rundach, aby uniknąć pętli."
            else -> "OpenAI zwrócił odpowiedź bez tekstu. Spróbuj ponownie lub uruchom diagnostykę OpenAI."
        }
        Reply(finalText, step.id)
    }

    private fun postResponse(
        apiKey: String,
        model: String,
        input: JSONArray,
        previousResponseId: String?,
        allowTools: Boolean,
    ): ParsedOpenAIResponse {
        val body = JSONObject()
            .put("model", model)
            .put("instructions", SYSTEM_INSTRUCTIONS)
            .put("input", input)
            .put("max_output_tokens", 1800)

        if (!previousResponseId.isNullOrBlank()) body.put("previous_response_id", previousResponseId)
        if (allowTools) {
            val tools = AdbAgent.realtimeTools()
            if (tools.length() > 0) {
                body.put("tools", tools)
                body.put("tool_choice", "auto")
                body.put("parallel_tool_calls", true)
            }
        }

        val request = Request.Builder()
            .url(RESPONSES_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(OpenAIProtocol.errorMessage(response.code, raw))
                OpenAIProtocol.parseResponse(raw)
            }
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Nie udało się połączyć z api.openai.com: ${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }
    }

    private suspend fun currentObservation(): AdbObservation? {
        val connected = runCatching { WirelessAdbController(QuestApp.appContext).isConnected() }.getOrDefault(false)
        if (!connected) return null
        return runCatching { AdbAgent.observe() }.getOrNull()
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
                append("\nAktywne okno: ").append(observation.currentActivity.take(700))
                append("\nRozmiar: ").append(observation.displaySize.take(250))
                append("\nScreenshot pusty/chroniony: ").append(observation.likelyBlank)
                append("\nDrzewo UI:\n").append(observation.uiSummary.take(12_000))
            }
        }
        content.put(JSONObject().put("type", "input_text").put("text", contextText))
        explicitImage?.takeIf { it.startsWith("data:image/") }?.let {
            content.put(JSONObject().put("type", "input_image").put("image_url", it).put("detail", "auto"))
        }
        observation?.imageDataUrl
            ?.takeIf { it != explicitImage && it.startsWith("data:image/") }
            ?.let { content.put(JSONObject().put("type", "input_image").put("image_url", it).put("detail", "auto")) }

        return JSONObject()
            .put("type", "message")
            .put("role", "user")
            .put("content", content)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val RESPONSES_URL = "https://api.openai.com/v1/responses"
        private const val MAX_AGENT_ROUNDS = 6
        private const val MAX_TOOLS_PER_ROUND = 3
        private const val SYSTEM_INSTRUCTIONS =
            "Jesteś QuestGPT działającym bezpośrednio na Meta Quest 3. Odpowiadaj po polsku, chyba że użytkownik używa innego języka. " +
                "Jeśli otrzymasz obraz lub drzewo UI, traktuj je jako aktualny widok użytkownika. " +
                "Gdy użytkownik jednoznacznie prosi o wykonanie czynności na urządzeniu, możesz użyć dostępnych narzędzi ADB. " +
                "Po akcji oceń nową obserwację. Nie wykonuj zakupów, wysyłania wiadomości, zmian konta, instalacji/usuwania aplikacji, " +
                "resetu urządzenia ani innych istotnych działań bez jednoznacznej prośby i wymaganego potwierdzenia użytkownika."
    }
}
