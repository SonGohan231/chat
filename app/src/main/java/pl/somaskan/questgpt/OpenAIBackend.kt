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
import pl.somaskan.questgpt.adb.AgentAccessPolicy
import pl.somaskan.questgpt.adb.AgentPermissionLevel
import pl.somaskan.questgpt.adb.AgentToolCall
import pl.somaskan.questgpt.adb.QuestAgentRuntime
import pl.somaskan.questgpt.adb.WirelessAdbController

class OpenAIBackend(private val http: OkHttpClient = OkHttpClient()) {
    data class Reply(val text: String, val responseId: String?)

    private data class AgentStep(
        val text: String,
        val responseId: String?,
        val toolCalls: List<AgentToolCall>,
    )

    suspend fun respond(
        baseUrl: String,
        text: String,
        imageDataUrl: String?,
        previousResponseId: String?
    ): Reply = withContext(Dispatchers.IO) {
        val backend = QuestEndpoints.resolveBackend(baseUrl)
        val adbConnected = runCatching { WirelessAdbController(QuestApp.appContext).isConnected() }.getOrDefault(false)

        // Explicit photo/sketch input is analyzed as-is. Normal turns use the live Quest agent when ADB is available.
        if (imageDataUrl != null || !adbConnected) {
            return@withContext plainRespond(backend, text, imageDataUrl, previousResponseId)
        }

        QuestAgentRuntime.resetTurn()
        val firstObservation = runCatching { AdbAgent.observe() }.getOrElse { error ->
            QuestAgentRuntime.lastError = error.message
            return@withContext plainRespond(backend, text, null, previousResponseId)
        }
        var permissionLevel = AgentAccessPolicy.current()
        var actionsAllowed = QuestAgentRuntime.agentControlEnabled && permissionLevel != AgentPermissionLevel.OBSERVE

        var step = runCatching {
            postAgentStart(
                backend = backend,
                text = text,
                previousResponseId = previousResponseId,
                observation = firstObservation,
                allowActions = actionsAllowed,
                permissionLevel = permissionLevel.key,
            )
        }.getOrElse { error ->
            QuestAgentRuntime.lastError = "Agent backend: ${error.message}"
            return@withContext plainRespond(
                backend,
                "$text\n\nKontekst Questa:\n${observationText(firstObservation)}",
                firstObservation.imageDataUrl,
                previousResponseId,
            )
        }

        var rounds = 0
        while (step.toolCalls.isNotEmpty() && rounds < MAX_AGENT_ROUNDS) {
            rounds++
            val outputs = JSONArray()
            step.toolCalls.take(MAX_TOOLS_PER_ROUND).forEach { call ->
                val output = runCatching { AdbAgent.execute(call) }
                    .getOrElse { "BŁĄD: ${it.message ?: "akcja nie powiodła się"}" }
                outputs.put(
                    JSONObject()
                        .put("callId", call.callId)
                        .put("output", output.take(4_000))
                )
            }

            delay(350L)
            val observation = runCatching { AdbAgent.observe() }.getOrNull()
            permissionLevel = AgentAccessPolicy.current()
            actionsAllowed = QuestAgentRuntime.agentControlEnabled && permissionLevel != AgentPermissionLevel.OBSERVE
            step = postAgentContinue(
                backend = backend,
                previousResponseId = step.responseId ?: error("Agent nie zwrócił responseId."),
                outputs = outputs,
                observation = observation,
                allowActions = actionsAllowed,
                permissionLevel = permissionLevel.key,
            )
        }

        val finalText = when {
            step.text.isNotBlank() -> step.text
            step.toolCalls.isNotEmpty() -> "Zatrzymałem automatyczne sterowanie po $MAX_AGENT_ROUNDS rundach, aby uniknąć pętli. Sprawdź aktualny ekran i spróbuj ponownie."
            else -> "Gotowe."
        }
        Reply(finalText, step.responseId)
    }

    private fun plainRespond(
        backend: String,
        text: String,
        imageDataUrl: String?,
        previousResponseId: String?,
    ): Reply {
        val body = JSONObject().apply {
            put("text", text)
            if (imageDataUrl != null) put("imageDataUrl", imageDataUrl)
            if (previousResponseId != null) put("previousResponseId", previousResponseId)
        }
        val json = postJson(backend + "/api/respond", body)
        return Reply(
            json.optString("text", ""),
            json.optString("responseId").takeIf { it.isNotBlank() },
        )
    }

    private fun postAgentStart(
        backend: String,
        text: String,
        previousResponseId: String?,
        observation: AdbObservation,
        allowActions: Boolean,
        permissionLevel: String,
    ): AgentStep {
        val body = JSONObject()
            .put("text", text)
            .put("allowActions", allowActions)
            .put("permissionLevel", permissionLevel)
            .put("observation", observationJson(observation))
        if (previousResponseId != null) body.put("previousResponseId", previousResponseId)
        return parseAgentStep(postJson(backend + "/api/agent/start", body))
    }

    private fun postAgentContinue(
        backend: String,
        previousResponseId: String,
        outputs: JSONArray,
        observation: AdbObservation?,
        allowActions: Boolean,
        permissionLevel: String,
    ): AgentStep {
        val body = JSONObject()
            .put("previousResponseId", previousResponseId)
            .put("allowActions", allowActions)
            .put("permissionLevel", permissionLevel)
            .put("toolOutputs", outputs)
        if (observation != null) body.put("observation", observationJson(observation))
        return parseAgentStep(postJson(backend + "/api/agent/continue", body))
    }

    private fun observationJson(observation: AdbObservation): JSONObject = JSONObject()
        .put("imageDataUrl", observation.imageDataUrl)
        .put("capturedAt", observation.capturedAt)
        .put("currentActivity", observation.currentActivity)
        .put("displaySize", observation.displaySize)
        .put("uiSummary", observation.uiSummary.take(20_000))
        .put("likelyBlank", observation.likelyBlank)

    private fun observationText(observation: AdbObservation): String = buildString {
        append("Aktywne okno: ").append(observation.currentActivity).append('\n')
        append("Rozmiar: ").append(observation.displaySize).append('\n')
        append("UI hierarchy:\n").append(observation.uiSummary.take(12_000))
    }

    private fun parseAgentStep(json: JSONObject): AgentStep {
        val calls = mutableListOf<AgentToolCall>()
        val array = json.optJSONArray("toolCalls") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val callId = item.optString("callId")
            val name = item.optString("name")
            if (callId.isBlank() || name.isBlank()) continue
            val arguments = when (val raw = item.opt("arguments")) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                else -> JSONObject()
            }
            calls += AgentToolCall(callId, name, arguments)
        }
        return AgentStep(
            text = json.optString("text", ""),
            responseId = json.optString("responseId").takeIf { it.isNotBlank() },
            toolCalls = calls,
        )
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).optString("error") }.getOrNull().orEmpty()
                error(if (message.isNotBlank()) message else "Backend ${response.code}: $raw")
            }
            return JSONObject(raw.ifBlank { "{}" })
        }
    }

    companion object {
        private const val MAX_AGENT_ROUNDS = 8
        private const val MAX_TOOLS_PER_ROUND = 3
    }
}
