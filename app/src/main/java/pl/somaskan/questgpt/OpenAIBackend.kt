package pl.somaskan.questgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import pl.somaskan.questgpt.adb.AdbVisionCapture

class OpenAIBackend(private val http: OkHttpClient = OkHttpClient()) {
    data class Reply(val text: String, val responseId: String?)

    suspend fun respond(
        baseUrl: String,
        text: String,
        imageDataUrl: String?,
        previousResponseId: String?
    ): Reply = withContext(Dispatchers.IO) {
        val backend = QuestEndpoints.resolveBackend(baseUrl)

        // If Wireless ADB is already connected, every normal Chat turn gets a fresh
        // screenshot of the current Quest display. Explicit images still take priority.
        val adbView = if (imageDataUrl == null) {
            AdbVisionCapture.captureDataUrlIfAvailable(autoConnectIfNeeded = false)
        } else null
        val effectiveImage = imageDataUrl ?: adbView
        val effectiveText = if (adbView != null) {
            "$text\n\nDołączony obraz jest aktualnym widokiem ekranu użytkownika na Meta Quest 3, pobranym przez ADB. Uwzględnij go przy odpowiedzi."
        } else text

        val body = JSONObject().apply {
            put("text", effectiveText)
            if (effectiveImage != null) put("imageDataUrl", effectiveImage)
            if (previousResponseId != null) put("previousResponseId", previousResponseId)
        }
        val request = Request.Builder()
            .url(backend + "/api/respond")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).optString("error") }.getOrNull().orEmpty()
                error(if (message.isNotBlank()) message else "OpenAI backend ${response.code}: $raw")
            }
            val json = JSONObject(raw)
            Reply(
                json.optString("text", ""),
                json.optString("responseId").takeIf { it.isNotBlank() }
            )
        }
    }
}
