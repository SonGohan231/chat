package pl.somaskan.questgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class OpenAIBackend(private val http: OkHttpClient = OkHttpClient()) {
    data class Reply(val text: String, val responseId: String?)

    suspend fun respond(
        baseUrl: String,
        text: String,
        imageDataUrl: String?,
        previousResponseId: String?
    ): Reply = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("text", text)
            if (imageDataUrl != null) put("imageDataUrl", imageDataUrl)
            if (previousResponseId != null) put("previousResponseId", previousResponseId)
        }
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/respond")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Backend ${response.code}: $raw")
            val json = JSONObject(raw)
            Reply(json.optString("text", ""), json.optString("responseId").takeIf { it.isNotBlank() })
        }
    }
}
