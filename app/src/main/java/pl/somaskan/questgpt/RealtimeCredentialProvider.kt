package pl.somaskan.questgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RealtimeCredential(
    val token: String,
    val model: String,
    val transport: String,
)

object RealtimeCredentialProvider {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(baseUrl: String, mode: String): RealtimeCredential {
        val backend = QuestEndpoints.resolveBackend(baseUrl)
        val safeMode = if (mode == "text") "text" else "voice"
        val raw = runCatching { fetchRaw(backend, safeMode) }.getOrNull()
        if (raw != null) return raw

        val bridged = AppDeployWebBridge.fetchRealtimeCredential(backend, safeMode)
        val token = bridged.optString("value")
        check(token.isNotBlank()) { "Most AppDeploy nie zwrócił krótkotrwałego tokenu Realtime." }
        return RealtimeCredential(
            token = token,
            model = bridged.optString("model", "gpt-realtime"),
            transport = "AppDeploy Web bridge",
        )
    }

    private suspend fun fetchRaw(backend: String, mode: String): RealtimeCredential = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$backend/api/realtime-token?mode=$mode&t=${System.currentTimeMillis()}")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty().trim()
            check(response.isSuccessful) { "HTTP ${response.code}" }
            check(raw.startsWith("{") && !raw.contains("data-appdeploy-overlay", ignoreCase = true)) {
                "Publiczny adres zwrócił frontend zamiast JSON."
            }
            val json = JSONObject(raw)
            val token = json.optString("value")
            check(token.isNotBlank()) { "Brak tokenu Realtime w odpowiedzi." }
            RealtimeCredential(token, json.optString("model", "gpt-realtime"), "native HTTPS")
        }
    }
}
