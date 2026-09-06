package pl.somaskan.questgpt.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

class UpdateClient(private val context: Context) {
    private val client = OkHttpClient()
    private val latestReleaseApi = "https://api.github.com/repos/SonGohan231/chat/releases/latest"

    data class ReleaseInfo(val build: Int, val name: String, val apkUrl: String)

    fun currentBuild(): Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    suspend fun findUpdate(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(latestReleaseApi)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "QuestGPT")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body?.string().orEmpty())
            val tag = json.optString("tag_name")
            val build = tag.substringAfterLast("-").toIntOrNull() ?: return@withContext null
            if (build <= currentBuild()) return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    return@withContext ReleaseInfo(build, name, asset.getString("browser_download_url"))
                }
            }
            null
        }
    }

    suspend fun download(info: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val out = File(dir, "QuestGPT-${info.build}.apk")
        val request = Request.Builder().url(info.apkUrl).header("User-Agent", "QuestGPT").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Pobieranie aktualizacji: HTTP ${response.code}" }
            response.body?.byteStream()?.use { input -> out.outputStream().use { input.copyTo(it) } }
                ?: error("Pusta odpowiedź aktualizacji")
        }
        out
    }
}
