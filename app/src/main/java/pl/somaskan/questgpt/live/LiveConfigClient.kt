package pl.somaskan.questgpt.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class LiveConfigClient {
    private val http = OkHttpClient()
    private val url = "https://raw.githubusercontent.com/SonGohan231/chat/main/config/live.json"

    suspend fun fetch(): LiveConfig = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "QuestGPT")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Live Config HTTP ${response.code}")
            LiveConfig.fromJson(response.body?.string().orEmpty())
        }
    }
}
