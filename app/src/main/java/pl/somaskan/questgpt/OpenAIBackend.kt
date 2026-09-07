package pl.somaskan.questgpt

import okhttp3.OkHttpClient

class OpenAIBackend(http: OkHttpClient = OkHttpClient()) {
    data class Reply(val text: String, val responseId: String?)

    private val realtime = RealtimeTextBackend(http)

    suspend fun respond(
        baseUrl: String,
        text: String,
        imageDataUrl: String?,
        previousResponseId: String?,
    ): Reply {
        // CloudFront in front of the legacy HTTPS backend rejects native POST requests.
        // Text, images and local Quest agent tool calls therefore use a direct OpenAI
        // Realtime WebSocket authenticated by a short-lived server-minted client secret.
        // The long-lived OpenAI API key never enters the APK.
        val answer = realtime.respond(
            baseUrl = baseUrl,
            text = text,
            imageDataUrl = imageDataUrl,
        )
        return Reply(answer, null)
    }
}
