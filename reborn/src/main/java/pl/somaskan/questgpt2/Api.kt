package pl.somaskan.questgpt2

import android.content.Context
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Api {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(110, TimeUnit.SECONDS).build()
    fun ask(context: Context, prompt: String, images: List<String> = emptyList()) {
        if (Hub.state.busy) return
        val store = CredentialStore(context)
        val key = runCatching { store.readKey() }.getOrNull()
        if (key.isNullOrBlank()) { Hub.note("Najpierw zapisz klucz w Połączeniu."); return }
        if (Hub.state.voiceActive) {
            if (!Hub.state.voiceReady) { Hub.note("Poczekaj na gotowość rozmowy Live."); return }
            if (Hub.voiceService?.sendText(prompt, images) == true) Hub.message("user", prompt + if(images.isNotEmpty()) " [obraz: ${images.size}]" else "")
            return
        }
        val history = Hub.state.messages
        Hub.message("user", prompt + if(images.isNotEmpty()) " [obraz: ${images.size}]" else "")
        Hub.change { it.copy(busy = true, note = "") }
        val request = Request.Builder().url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $key")
            .post(Protocol.responseBody(store.textModel(), history, prompt, images).toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        Hub.textCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (Hub.textCall !== call) return
                Hub.change { it.copy(busy = false) }
                Hub.note(if (call.isCanceled()) "Wysyłanie anulowane." else "Brak połączenia: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (Hub.textCall !== call || call.isCanceled()) return
                    runCatching {
                        val raw = response.body?.string().orEmpty()
                        if (!response.isSuccessful) error(Protocol.error(response.code, raw))
                        val answer = Protocol.output(raw)
                        if (!call.isCanceled() && Hub.textCall === call) Hub.message("assistant", answer)
                    }.onFailure { Hub.note(it.message ?: "Błąd odpowiedzi") }
                    Hub.change { it.copy(busy = false) }
                }
            }
        })
    }
    fun test(context: Context) {
        val store = CredentialStore(context)
        val key = runCatching { store.readKey() }.getOrNull()
        if (key.isNullOrBlank()) { Hub.note("Najpierw zapisz klucz API."); return }
        Hub.change { it.copy(apiTest = "Sprawdzam odpowiedź API…") }
        val body = Protocol.responseBody(store.textModel(), emptyList(), "Odpowiedz jednym słowem: działa", emptyList())
        client.newCall(Request.Builder().url("https://api.openai.com/v1/responses").header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { Hub.change { it.copy(apiTest = "Test nieudany: brak połączenia.") } }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val result = runCatching {
                        val raw = response.body?.string().orEmpty()
                        if (!response.isSuccessful) error(Protocol.error(response.code, raw))
                        "API odpowiedziało: ${Protocol.output(raw).take(100)}"
                    }.getOrElse { "Test nieudany: ${Protocol.safe(it.message.orEmpty())}" }
                    Hub.change { it.copy(apiTest = result) }
                }
            }
        })
    }
}
