package pl.somaskan.questgpt.live

import org.json.JSONArray
import org.json.JSONObject

data class LiveConfig(
    val title: String = "QuestGPT",
    val subtitle: String = "Asystent Meta Quest 3",
    val quickActions: List<String> = listOf("Co widzisz?", "Wyjaśnij", "Podsumuj", "Co dalej?"),
    val boardTemplates: List<String> = listOf("Rozmowa", "Vision", "Research", "Pliki projektu", "Zadania", "Pomysły"),
    val refreshSeconds: Long = 15,
    val updatedAt: String = "lokalna konfiguracja"
) {
    fun toJson(): String = JSONObject().apply {
        put("title", title)
        put("subtitle", subtitle)
        put("quickActions", JSONArray(quickActions))
        put("boardTemplates", JSONArray(boardTemplates))
        put("refreshSeconds", refreshSeconds)
        put("updatedAt", updatedAt)
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
                updatedAt = json.optString("updatedAt", defaults.updatedAt)
            )
        }

        private fun JSONArray.toStringList(): List<String> =
            List(length()) { index -> optString(index) }.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
