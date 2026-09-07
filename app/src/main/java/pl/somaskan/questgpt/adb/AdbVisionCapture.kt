package pl.somaskan.questgpt.adb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.somaskan.questgpt.QuestApp
import java.io.ByteArrayOutputStream

object AdbVisionCapture {
    suspend fun captureDataUrlIfAvailable(autoConnectIfNeeded: Boolean = true): String? =
        runCatching { captureDataUrl(autoConnectIfNeeded) }.getOrNull()

    suspend fun captureDataUrl(autoConnectIfNeeded: Boolean = true): String = withContext(Dispatchers.Default) {
        val png = WirelessAdbController(QuestApp.appContext)
            .captureScreenshotPng(autoConnectIfNeeded)
        val source = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: error("Nie udało się zdekodować screenshotu ADB.")

        val maxEdge = 1600
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1))
        val targetWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val outputBitmap = if (targetWidth != source.width || targetHeight != source.height) {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true).also { source.recycle() }
        } else source

        try {
            ByteArrayOutputStream().use { out ->
                check(outputBitmap.compress(Bitmap.CompressFormat.JPEG, 84, out)) {
                    "Nie udało się skompresować obrazu z Questa."
                }
                "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            outputBitmap.recycle()
        }
    }
}
