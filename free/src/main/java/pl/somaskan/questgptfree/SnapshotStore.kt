package pl.somaskan.questgptfree

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SnapshotStore {
    private const val PREFS = "local_snapshot"
    fun status(context: Context) = context.getSharedPreferences(PREFS,0).getString("status", "Zrzut zapiszesz lokalnie. Dołączysz go samodzielnie do rozmowy.").orEmpty()
    fun status(context: Context, value: String) { context.getSharedPreferences(PREFS,0).edit().putString("status",value).apply() }
    fun latest(context: Context): Uri? = context.getSharedPreferences(PREFS,0).getString("uri",null)?.let(Uri::parse)
    fun name(context: Context) = context.getSharedPreferences(PREFS,0).getString("name","").orEmpty()
    fun save(context: Context, bitmap: Bitmap): Uri {
        val filename = "QuestGPT-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.ROOT).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,filename)
            put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES + "/QuestGPT Free")
            put(MediaStore.Images.Media.IS_PENDING,1)
        }
        val resolver=context.contentResolver
        val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("Nie można utworzyć pliku")
        try {
            resolver.openOutputStream(uri)?.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)) } ?: error("Nie można zapisać obrazu")
            check(resolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null) == 1)
            context.getSharedPreferences(PREFS,0).edit().putString("uri",uri.toString()).putString("name",filename)
                .putString("status","Zrzut zapisany: $filename\nZdjęcia → QuestGPT Free. W ChatGPT wybierz + i dołącz obraz.").apply()
            return uri
        } catch(e: Exception) { resolver.delete(uri,null,null); throw e }
    }
}
