package pl.somaskan.questgpt.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class LiveConfigClient {
    private val http = OkHttpClient()
    private val url = "https://raw.githubusercontent.com/SonGohan231/chat/main/config/live.json"

    suspend fun fetch(): LiveConfig = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Live Config HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            LiveConfig(
                title = json.optString("title", "QuestGPT"),
                subtitle = json.optString("subtitle", "Asystent Meta Quest 3"),
                quickActions = json.optJSONArray("quickActions")?.let { array ->
                    List(array.length()) { index -> array.optString(index) }.filter { it.isNotBlank() }
                }.orEmpty().ifEmpty { LiveConfig().quickActions },
                boardTemplates = json.optJSONArray("boardTemplates")?.let { array ->
                    List(array.length()) { index -> array.optString(index) }.filter { it.isNotBlank() }
                }.orEmpty().ifEmpty { LiveConfig().boardTemplates },
                refreshSeconds = json.optLong("refreshSeconds", 15).coerceIn(5, 3600),
                updatedAt = json.optString("updatedAt", "zdalna konfiguracja")
            )
        }
    }
}
