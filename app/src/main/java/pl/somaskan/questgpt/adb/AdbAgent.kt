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
        check(AgentAccessPolicy.canExecute(call.name)) {
            "Narzędzie ${call.name} wymaga poziomu ${AgentAccessPolicy.minimumLevel(call.name).label}."
        }
        val controller = WirelessAdbController(QuestApp.appContext)
        check(controller.isConnected()) { "ADB rozłączone podczas wykonywania akcji." }

        if (AgentAccessPolicy.requiresConfirmation(call.name)) {
            val approved = AgentConfirmationCenter.request(
                title = confirmationTitle(call),
                details = confirmationDetails(call),
            )
            check(approved) { "Użytkownik nie zatwierdził akcji ${call.name}." }
        }

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
                "open_url" -> controller.openUrl(call.arguments.getString("url"))
                "launch_settings" -> controller.launchSettings(call.arguments.getString("section"))
                "media_control" -> controller.mediaControl(call.arguments.getString("action"))
                "volume_adjust" -> controller.adjustVolume(
                    call.arguments.getString("direction"),
                    call.arguments.optInt("steps", 1),
                )
                "set_brightness" -> controller.setBrightness(call.arguments.getInt("percent"))
                "toggle_wifi" -> controller.toggleWifi(call.arguments.getBoolean("enabled"))
                "toggle_bluetooth" -> controller.toggleBluetooth(call.arguments.getBoolean("enabled"))
                "install_apk" -> controller.installApk(call.arguments.getString("path"))
                "uninstall_app" -> controller.uninstallPackage(call.arguments.getString("package_name"))
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

    fun realtimeTools(): JSONArray {
        if (!QuestAgentRuntime.agentControlEnabled) return JSONArray()
        val tools = JSONArray()
        fun add(name: String, value: JSONObject) {
            if (AgentAccessPolicy.canExecute(name)) tools.put(value)
        }

        add("tap", tool(
            name = "tap",
            description = "Dotknij elementu interfejsu na ekranie Questa. Używaj współrzędnych z obrazu i bounds z drzewa UI.",
            properties = JSONObject().put("x", integerProperty("Współrzędna X")).put("y", integerProperty("Współrzędna Y")),
            required = listOf("x", "y"),
        ))
        add("swipe", tool(
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
        add("type_text", tool(
            name = "type_text",
            description = "Wpisz tekst do aktualnie aktywnego pola tekstowego.",
            properties = JSONObject().put("text", stringProperty("Tekst do wpisania")),
            required = listOf("text"),
        ))
        add("press_key", tool(
            name = "press_key",
            description = "Naciśnij bezpieczny klawisz systemowy lub nawigacyjny.",
            properties = JSONObject().put("key", enumProperty(listOf("BACK", "HOME", "ENTER", "TAB", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT"))),
            required = listOf("key"),
        ))
        add("open_app", tool(
            name = "open_app",
            description = "Uruchom zainstalowaną aplikację, jeśli znasz jej package name.",
            properties = JSONObject().put("package_name", stringProperty("Android package name, np. com.oculus.browser")),
            required = listOf("package_name"),
        ))
        add("open_url", tool(
            name = "open_url",
            description = "Otwórz bezpieczny adres http/https w domyślnej przeglądarce Questa.",
            properties = JSONObject().put("url", stringProperty("Pełny adres http lub https")),
            required = listOf("url"),
        ))
        add("launch_settings", tool(
            name = "launch_settings",
            description = "Otwórz konkretną sekcję ustawień systemowych bez zmieniania jej wartości.",
            properties = JSONObject().put("section", enumProperty(listOf("WIFI", "BLUETOOTH", "DISPLAY", "SOUND", "APPS", "DEVELOPER", "ACCESSIBILITY", "NETWORK"))),
            required = listOf("section"),
        ))
        add("media_control", tool(
            name = "media_control",
            description = "Steruj aktualnie odtwarzanymi multimediami.",
            properties = JSONObject().put("action", enumProperty(listOf("PLAY_PAUSE", "NEXT", "PREVIOUS", "STOP"))),
            required = listOf("action"),
        ))
        add("volume_adjust", tool(
            name = "volume_adjust",
            description = "Zmień głośność systemową o kilka kroków albo przełącz wyciszenie.",
            properties = JSONObject().put("direction", enumProperty(listOf("UP", "DOWN", "MUTE"))).put("steps", integerProperty("1-10 kroków")),
            required = listOf("direction"),
        ))
        add("set_brightness", tool(
            name = "set_brightness",
            description = "Ustaw przybliżoną jasność wyświetlacza Questa w procentach.",
            properties = JSONObject().put("percent", integerProperty("5-100")),
            required = listOf("percent"),
        ))
        add("toggle_wifi", tool(
            name = "toggle_wifi",
            description = "Włącz lub wyłącz Wi-Fi. Ta akcja zawsze wymaga potwierdzenia użytkownika w panelu QuestGPT.",
            properties = JSONObject().put("enabled", booleanProperty("true = włącz, false = wyłącz")),
            required = listOf("enabled"),
        ))
        add("toggle_bluetooth", tool(
            name = "toggle_bluetooth",
            description = "Włącz lub wyłącz Bluetooth. Ta akcja zawsze wymaga potwierdzenia użytkownika w panelu QuestGPT.",
            properties = JSONObject().put("enabled", booleanProperty("true = włącz, false = wyłącz")),
            required = listOf("enabled"),
        ))
        add("install_apk", tool(
            name = "install_apk",
            description = "Zainstaluj APK znajdujące się wyłącznie w folderze Download. Zawsze wymaga potwierdzenia użytkownika.",
            properties = JSONObject().put("path", stringProperty("Ścieżka /sdcard/Download/nazwa.apk")),
            required = listOf("path"),
        ))
        add("uninstall_app", tool(
            name = "uninstall_app",
            description = "Usuń aplikację użytkownika po package name. Zawsze wymaga potwierdzenia użytkownika.",
            properties = JSONObject().put("package_name", stringProperty("Android package name")),
            required = listOf("package_name"),
        ))
        add("wait", tool(
            name = "wait",
            description = "Poczekaj krótko aż interfejs się przeładuje.",
            properties = JSONObject().put("milliseconds", integerProperty("100-5000 ms")),
            required = emptyList(),
        ))
        add("refresh_view", tool(
            name = "refresh_view",
            description = "Natychmiast pobierz nową klatkę bez wykonywania akcji.",
            properties = JSONObject(),
            required = emptyList(),
        ))
        return tools
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

    private fun tool(name: String, description: String, properties: JSONObject, required: List<String>): JSONObject =
        JSONObject()
            .put("type", "function")
            .put("name", name)
            .put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false))

    private fun integerProperty(description: String) = JSONObject().put("type", "integer").put("description", description)
    private fun stringProperty(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun booleanProperty(description: String) = JSONObject().put("type", "boolean").put("description", description)
    private fun enumProperty(values: List<String>) = JSONObject().put("type", "string").put("enum", JSONArray(values))

    private fun confirmationTitle(call: AgentToolCall): String = when (call.name) {
        "toggle_wifi" -> "Zmiana Wi-Fi"
        "toggle_bluetooth" -> "Zmiana Bluetooth"
        "install_apk" -> "Instalacja APK"
        "uninstall_app" -> "Usunięcie aplikacji"
        else -> "Wrażliwa akcja systemowa"
    }

    private fun confirmationDetails(call: AgentToolCall): String = when (call.name) {
        "toggle_wifi" -> "GPT chce ${if (call.arguments.optBoolean("enabled")) "włączyć" else "wyłączyć"} Wi-Fi."
        "toggle_bluetooth" -> "GPT chce ${if (call.arguments.optBoolean("enabled")) "włączyć" else "wyłączyć"} Bluetooth."
        "install_apk" -> "GPT chce zainstalować: ${call.arguments.optString("path").take(300)}"
        "uninstall_app" -> "GPT chce usunąć aplikację: ${call.arguments.optString("package_name").take(180)}"
        else -> describe(call)
    }

    private fun describe(call: AgentToolCall): String = when (call.name) {
        "tap" -> "tap ${call.arguments.optInt("x")},${call.arguments.optInt("y")}"
        "swipe" -> "swipe"
        "type_text" -> "wpisuję tekst"
        "press_key" -> "klawisz ${call.arguments.optString("key")}"
        "open_app" -> "otwieram ${call.arguments.optString("package_name")}"
        "open_url" -> "otwieram URL"
        "launch_settings" -> "ustawienia ${call.arguments.optString("section")}"
        "media_control" -> "multimedia ${call.arguments.optString("action")}"
        "volume_adjust" -> "głośność ${call.arguments.optString("direction")}"
        "set_brightness" -> "jasność ${call.arguments.optInt("percent")}%"
        "toggle_wifi" -> "Wi-Fi ${call.arguments.optBoolean("enabled")}"
        "toggle_bluetooth" -> "Bluetooth ${call.arguments.optBoolean("enabled")}"
        "install_apk" -> "instaluję APK"
        "uninstall_app" -> "usuwam ${call.arguments.optString("package_name")}"
        "wait" -> "czekam"
        "refresh_view" -> "odświeżam widok"
        else -> call.name
    }
}
