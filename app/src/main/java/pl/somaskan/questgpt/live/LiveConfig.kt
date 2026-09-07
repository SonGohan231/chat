package pl.somaskan.questgpt.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class LiveUiConfig(
    val floatingPanels: Boolean = true,
    val panelOpacity: Float = 0.96f,
    val panelCornerDp: Float = 24f,
    val navWidthDp: Float = 126f,
    val contentPaddingDp: Float = 14f,
    val panelSpacingDp: Float = 10f,
    val fontScale: Float = 1f,
    val backgroundDim: Float = 0.12f,
    val navigationLabels: Boolean = true,
    val showPanelHeader: Boolean = true,
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("floatingPanels", floatingPanels)
        put("panelOpacity", panelOpacity)
        put("panelCornerDp", panelCornerDp)
        put("navWidthDp", navWidthDp)
        put("contentPaddingDp", contentPaddingDp)
        put("panelSpacingDp", panelSpacingDp)
        put("fontScale", fontScale)
        put("backgroundDim", backgroundDim)
        put("navigationLabels", navigationLabels)
        put("showPanelHeader", showPanelHeader)
    }

    companion object {
        fun fromJson(json: JSONObject?): LiveUiConfig {
            val defaults = LiveUiConfig()
            if (json == null) return defaults
            return LiveUiConfig(
                floatingPanels = json.optBoolean("floatingPanels", defaults.floatingPanels),
                panelOpacity = json.optDouble("panelOpacity", defaults.panelOpacity.toDouble()).toFloat().coerceIn(0.62f, 1f),
                panelCornerDp = json.optDouble("panelCornerDp", defaults.panelCornerDp.toDouble()).toFloat().coerceIn(0f, 48f),
                navWidthDp = json.optDouble("navWidthDp", defaults.navWidthDp.toDouble()).toFloat().coerceIn(88f, 210f),
                contentPaddingDp = json.optDouble("contentPaddingDp", defaults.contentPaddingDp.toDouble()).toFloat().coerceIn(4f, 32f),
                panelSpacingDp = json.optDouble("panelSpacingDp", defaults.panelSpacingDp.toDouble()).toFloat().coerceIn(2f, 24f),
                fontScale = json.optDouble("fontScale", defaults.fontScale.toDouble()).toFloat().coerceIn(0.82f, 1.38f),
                backgroundDim = json.optDouble("backgroundDim", defaults.backgroundDim.toDouble()).toFloat().coerceIn(0f, 0.5f),
                navigationLabels = json.optBoolean("navigationLabels", defaults.navigationLabels),
                showPanelHeader = json.optBoolean("showPanelHeader", defaults.showPanelHeader),
            )
        }
    }
}

data class LiveConfig(
    val title: String = "QuestGPT",
    val subtitle: String = "Asystent Meta Quest 3",
    val quickActions: List<String> = listOf("Co widzisz?", "Wyjaśnij", "Podsumuj", "Co dalej?"),
    val boardTemplates: List<String> = listOf("Rozmowa", "Vision", "Research", "Pliki projektu", "Zadania", "Pomysły"),
    val refreshSeconds: Long = 15,
    val updatedAt: String = "lokalna konfiguracja",
    val ui: LiveUiConfig = LiveUiConfig(),
) {
    fun toJson(): String = JSONObject().apply {
        put("title", title)
        put("subtitle", subtitle)
        put("quickActions", JSONArray(quickActions))
        put("boardTemplates", JSONArray(boardTemplates))
        put("refreshSeconds", refreshSeconds)
        put("updatedAt", updatedAt)
        put("ui", ui.toJsonObject())
    }.toString()

    companion object {
        fun fromJson(raw: String): LiveConfig {
            val json = JSONObject(raw)
            val defaults = LiveConfig()
            return LiveConfig(
                title = json.optString("title", defaults.title),
                subtitle = json.optString("subtitle", defaults.subtitle),
                quickActions = json.optJSONArray("quickActions")?.toStringList().orEmpty().ifEmpty { defaults.quickActions },
                boardTemplates = json.optJSONArray("boardTemplates")?.toStringList().orEmpty().ifEmpty { defaults.boardTemplates },
                refreshSeconds = json.optLong("refreshSeconds", defaults.refreshSeconds).coerceIn(5, 3600),
                updatedAt = json.optString("updatedAt", defaults.updatedAt),
                ui = LiveUiConfig.fromJson(json.optJSONObject("ui")),
            ).also(LiveUiRuntime::publish)
        }

        private fun JSONArray.toStringList(): List<String> =
            List(length()) { index -> optString(index) }.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

object LiveUiRuntime {
    var config by mutableStateOf(LiveConfig())
        private set

    fun publish(config: LiveConfig) {
        this.config = config
    }
}
