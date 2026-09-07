package pl.somaskan.questgpt.adb

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import pl.somaskan.questgpt.QuestApp

data class AdbObservation(
    val imageDataUrl: String?,
    val capturedAt: Long,
    val currentActivity: String,
    val displaySize: String,
    val uiSummary: String,
    val likelyBlank: Boolean,
)

data class AgentToolCall(
    val callId: String,
    val name: String,
    val arguments: JSONObject,
)

object AdbAgent {
    suspend fun observe(): AdbObservation {
        val controller = WirelessAdbController(QuestApp.appContext)
        check(controller.isConnected()) { "ADB nie jest połączone." }
        val frame = AdbVisionMonitor.latestOrCapture(maxAgeMs = 900L)
        val activity = controller.currentActivity().take(1_000)
        val display = controller.displaySize().take(300)
        val xml = controller.uiHierarchyXml()
        val ui = summarizeUi(xml)
        QuestAgentRuntime.currentActivity = activity
        return AdbObservation(
            imageDataUrl = frame?.dataUrl,
            capturedAt = frame?.capturedAt ?: System.currentTimeMillis(),
            currentActivity = activity,
            displaySize = display,
            uiSummary = ui,
            likelyBlank = frame?.likelyBlank ?: false,
        )
    }

    suspend fun execute(call: AgentToolCall): String {
        check(QuestAgentRuntime.agentControlEnabled) { "Sterowanie GPT jest wyłączone przez użytkownika." }
        val controller = WirelessAdbController(QuestApp.appContext)
        check(controller.isConnected()) { "ADB rozłączone podczas wykonywania akcji." }
        QuestAgentRuntime.actionsThisTurn += 1
        QuestAgentRuntime.lastAction = "GPT: ${describe(call)}"
        QuestAgentRuntime.lastError = null

        return runCatching {
            when (call.name) {
                "tap" -> controller.tap(call.arguments.getInt("x"), call.arguments.getInt("y"))
                "swipe" -> controller.swipe(
                    call.arguments.getInt("x1"),
                    call.arguments.getInt("y1"),
                    call.arguments.getInt("x2"),
                    call.arguments.getInt("y2"),
                    call.arguments.optInt("duration_ms", 350),
                )
                "type_text" -> controller.inputText(call.arguments.getString("text"))
                "press_key" -> controller.pressKey(call.arguments.getString("key"))
                "open_app" -> controller.openPackage(call.arguments.getString("package_name"))
                "wait" -> {
                    val ms = call.arguments.optInt("milliseconds", 800).coerceIn(100, 5_000)
                    delay(ms.toLong())
                    "Odczekano ${ms}ms"
                }
                "refresh_view" -> {
                    val frame = AdbVisionMonitor.captureNow()
                    if (frame == null) "Nie udało się odświeżyć widoku" else "Widok odświeżony ${frame.width}x${frame.height}"
                }
                else -> error("Niedozwolone narzędzie ADB: ${call.name}")
            }
        }.onFailure {
            QuestAgentRuntime.lastError = it.message
            QuestAgentRuntime.lastAction = "Błąd akcji GPT: ${call.name}"
        }.getOrThrow()
    }

    fun realtimeTools(): JSONArray = JSONArray().apply {
        put(tool(
            name = "tap",
            description = "Dotknij elementu interfejsu na ekranie Questa. Używaj współrzędnych z obrazu i bounds z drzewa UI.",
            properties = JSONObject()
                .put("x", integerProperty("Współrzędna X"))
                .put("y", integerProperty("Współrzędna Y")),
            required = listOf("x", "y"),
        ))
        put(tool(
            name = "swipe",
            description = "Wykonaj gest przesunięcia na ekranie Questa.",
            properties = JSONObject()
                .put("x1", integerProperty("Początkowe X"))
                .put("y1", integerProperty("Początkowe Y"))
                .put("x2", integerProperty("Końcowe X"))
                .put("y2", integerProperty("Końcowe Y"))
                .put("duration_ms", integerProperty("Czas gestu w ms, zwykle 200-800")),
            required = listOf("x1", "y1", "x2", "y2"),
        ))
        put(tool(
            name = "type_text",
            description = "Wpisz tekst do aktualnie aktywnego pola tekstowego.",
            properties = JSONObject().put("text", stringProperty("Tekst do wpisania")),
            required = listOf("text"),
        ))
        put(tool(
            name = "press_key",
            description = "Naciśnij bezpieczny klawisz systemowy lub nawigacyjny.",
            properties = JSONObject().put(
                "key",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("BACK", "HOME", "ENTER", "TAB", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT")))
            ),
            required = listOf("key"),
        ))
        put(tool(
            name = "open_app",
            description = "Uruchom zainstalowaną aplikację, jeśli znasz jej package name.",
            properties = JSONObject().put("package_name", stringProperty("Android package name, np. com.oculus.browser")),
            required = listOf("package_name"),
        ))
        put(tool(
            name = "wait",
            description = "Poczekaj krótko aż interfejs się przeładuje.",
            properties = JSONObject().put("milliseconds", integerProperty("100-5000 ms")),
            required = emptyList(),
        ))
        put(tool(
            name = "refresh_view",
            description = "Poproś QuestGPT o natychmiastowe pobranie nowej klatki bez wykonywania akcji.",
            properties = JSONObject(),
            required = emptyList(),
        ))
    }

    private fun summarizeUi(xml: String, maxNodes: Int = 140): String {
        if (xml.isBlank() || !xml.contains("<node")) return "Brak dostępnego drzewa UI (aplikacja może nie udostępniać Accessibility/UIAutomator)."
        val nodeRegex = Regex("<node\\s+([^>]+?)/?>")
        val attrRegex = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
        val lines = ArrayList<String>()
        for (match in nodeRegex.findAll(xml)) {
            val attrs = attrRegex.findAll(match.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            val text = attrs["text"].orEmpty()
            val desc = attrs["content-desc"].orEmpty()
            val resource = attrs["resource-id"].orEmpty()
            val clickable = attrs["clickable"] == "true"
            val scrollable = attrs["scrollable"] == "true"
            val focusable = attrs["focusable"] == "true"
            if (text.isBlank() && desc.isBlank() && resource.isBlank() && !clickable && !scrollable && !focusable) continue
            val line = buildString {
                append("#${lines.size + 1} ")
                if (text.isNotBlank()) append("text=\"").append(text.take(100)).append("\" ")
                if (desc.isNotBlank()) append("desc=\"").append(desc.take(100)).append("\" ")
                if (resource.isNotBlank()) append("id=").append(resource.takeLast(100)).append(' ')
                attrs["class"]?.takeIf { it.isNotBlank() }?.let { append("class=").append(it.substringAfterLast('.')).append(' ') }
                attrs["bounds"]?.let { append("bounds=").append(it).append(' ') }
                if (clickable) append("clickable ")
                if (scrollable) append("scrollable ")
                if (focusable) append("focusable ")
                if (attrs["checked"] == "true") append("checked ")
                if (attrs["selected"] == "true") append("selected ")
            }.trim()
            lines += line
            if (lines.size >= maxNodes) break
        }
        return if (lines.isEmpty()) "Drzewo UI nie zawiera opisanych/interaktywnych elementów." else lines.joinToString("\n")
    }

    private fun tool(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ): JSONObject = JSONObject()
        .put("type", "function")
        .put("name", name)
        .put("description", description)
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(required))
                .put("additionalProperties", false)
        )

    private fun integerProperty(description: String) = JSONObject().put("type", "integer").put("description", description)
    private fun stringProperty(description: String) = JSONObject().put("type", "string").put("description", description)

    private fun describe(call: AgentToolCall): String = when (call.name) {
        "tap" -> "tap ${call.arguments.optInt("x")},${call.arguments.optInt("y")}"
        "swipe" -> "swipe"
        "type_text" -> "wpisuję tekst"
        "press_key" -> "klawisz ${call.arguments.optString("key")}"
        "open_app" -> "otwieram ${call.arguments.optString("package_name")}"
        "wait" -> "czekam"
        "refresh_view" -> "odświeżam widok"
        else -> call.name
    }
}
