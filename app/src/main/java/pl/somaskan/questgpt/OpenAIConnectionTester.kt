package pl.somaskan.questgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class OpenAITestResult(val ok: Boolean, val detail: String)

class OpenAIConnectionTester(
    private val context: android.content.Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
) {
    private val store = OpenAICredentialStore(context.applicationContext)

    suspend fun testModel(model: String): OpenAITestResult = withContext(Dispatchers.IO) {
        val key = runCatching { store.readKey() }.getOrNull()
            ?: return@withContext OpenAITestResult(false, "Brak zapisanego klucza OpenAI API.")
        val safeModel = model.trim()
        if (!safeModel.matches(Regex("[A-Za-z0-9._:-]{2,100}"))) {
            return@withContext OpenAITestResult(false, "Nieprawidłowa nazwa modelu: $safeModel")
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/models/$safeModel")
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .get()
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    OpenAITestResult(true, "Połączenie z OpenAI działa • model $safeModel jest dostępny")
                } else {
                    OpenAITestResult(false, OpenAIProtocol.errorMessage(response.code, raw))
                }
            }
        }.getOrElse { error ->
            OpenAITestResult(false, "Nie udało się połączyć z api.openai.com: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}
