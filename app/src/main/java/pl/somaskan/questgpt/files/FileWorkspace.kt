package pl.somaskan.questgpt.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class FileWorkspace(private val context: Context) {
    private val client = OkHttpClient()

    fun persistUri(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
            ?: error("Nie można zapisać pliku")
    }

    suspend fun downloadUrl(url: String, target: Uri) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "QuestGPT").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Pobieranie: HTTP ${response.code}" }
            val output = context.contentResolver.openOutputStream(target) ?: error("Nie można utworzyć pliku")
            output.use { out -> response.body?.byteStream()?.use { it.copyTo(out) } ?: error("Pusty plik") }
        }
    }

    fun renameDocument(uri: Uri, newName: String): Uri? = runCatching {
        DocumentsContract.renameDocument(context.contentResolver, uri, newName)
    }.getOrNull()

    fun deleteDocument(uri: Uri): Boolean = runCatching {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    }.getOrDefault(false)
}
