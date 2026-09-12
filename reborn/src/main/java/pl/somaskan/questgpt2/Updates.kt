package pl.somaskan.questgpt2

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Updates {
    private var checked=false
    fun check() {
        if(checked)return
        checked=true
        val client=OkHttpClient.Builder().callTimeout(10,TimeUnit.SECONDS).build()
        val url="https://raw.githubusercontent.com/SonGohan231/chat/rebuild/questgpt2-20260911/config/questgpt2-release.json"
        client.newCall(Request.Builder().url(url).build()).enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { }
            override fun onResponse(call:Call,response:Response) { response.use {
                if(!response.isSuccessful)return
                runCatching {
                    val code=JSONObject(response.body?.string().orEmpty()).optInt("versionCode",0)
                    if(code>BuildConfig.VERSION_CODE && Hub.state.note.isBlank()) Hub.note("Dostępna nowa wersja. Otwórz ⋯ → Pobierz aktualizację.")
                }
            } }
        })
    }
}
