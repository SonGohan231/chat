package pl.somaskan.questgpt

import org.json.JSONObject
import pl.somaskan.questgpt.adb.AgentToolCall

data class ParsedOpenAIResponse(
    val id: String?,
    val text: String,
    val toolCalls: List<AgentToolCall>,
)

object OpenAIProtocol {
    fun parseResponse(raw: String): ParsedOpenAIResponse {
        val json = JSONObject(raw.ifBlank { "{}" })
        val responseError = json.optJSONObject("error")
        if (responseError != null && responseError.optString("message").isNotBlank()) {
            error(responseError.optString("message"))
        }

        val textPieces = mutableListOf<String>()
        json.optString("output_text").takeIf { it.isNotBlank() }?.let(textPieces::add)
        val calls = mutableListOf<AgentToolCall>()
        val output = json.optJSONArray("output")
        if (output != null) {
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "message" -> {
                        val content = item.optJSONArray("content") ?: continue
                        for (j in 0 until content.length()) {
                            val part = content.optJSONObject(j) ?: continue
                            if (part.optString("type") == "output_text") {
                                part.optString("text").takeIf { it.isNotBlank() }?.let(textPieces::add)
                            }
                        }
                    }
                    "function_call" -> {
                        val callId = item.optString("call_id")
                        val name = item.optString("name")
                        if (callId.isNotBlank() && name.isNotBlank()) {
                            val arguments = runCatching {
                                JSONObject(item.optString("arguments", "{}"))
                            }.getOrDefault(JSONObject())
                            calls += AgentToolCall(callId, name, arguments)
                        }
                    }
                }
            }
        }

        return ParsedOpenAIResponse(
            id = json.optString("id").takeIf { it.isNotBlank() },
            text = textPieces.distinct().joinToString("\n").trim(),
            toolCalls = calls,
        )
    }

    fun errorMessage(httpCode: Int, raw: String): String {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val error = json?.optJSONObject("error")
        val message = error?.optString("message").orEmpty()
        val code = error?.optString("code").orEmpty()
        val type = error?.optString("type").orEmpty()
        val detail = message.ifBlank { "HTTP $httpCode" }

        return when {
            httpCode == 401 || code == "invalid_api_key" ->
                "OpenAI odrzucił klucz API (401). Zapisz prawidłowy klucz w Ustawienia > OpenAI."
            httpCode == 403 ->
                "Klucz dotarł do OpenAI, ale nie ma dostępu do tej operacji lub modelu (403): $detail"
            httpCode == 404 || code == "model_not_found" ->
                "Wybrany model OpenAI nie jest dostępny dla tego projektu: $detail"
            httpCode == 429 && (code == "insufficient_quota" || type == "insufficient_quota" || detail.contains("quota", true) || detail.contains("credit", true)) ->
                "Brak dostępnego limitu/środków OpenAI API (429): $detail"
            httpCode == 429 ->
                "Limit szybkości OpenAI API został chwilowo przekroczony (429). Spróbuj ponownie za moment."
            httpCode in 500..599 ->
                "OpenAI API chwilowo zwrócił błąd serwera ($httpCode): $detail"
            else -> "OpenAI API $httpCode: $detail"
        }
    }
}
