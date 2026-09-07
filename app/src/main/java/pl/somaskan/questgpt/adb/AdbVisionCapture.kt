package pl.somaskan.questgpt.adb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.somaskan.questgpt.QuestApp
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

data class AdbVisionFrame(
    val dataUrl: String,
    val capturedAt: Long,
    val hash: String,
    val width: Int,
    val height: Int,
    val likelyBlank: Boolean,
)

object AdbVisionCapture {
    suspend fun captureDataUrlIfAvailable(autoConnectIfNeeded: Boolean = true): String? =
        runCatching { captureFrame(autoConnectIfNeeded).dataUrl }.getOrNull()

    suspend fun captureDataUrl(autoConnectIfNeeded: Boolean = true): String =
        captureFrame(autoConnectIfNeeded).dataUrl

    suspend fun captureFrame(autoConnectIfNeeded: Boolean = true): AdbVisionFrame = withContext(Dispatchers.Default) {
        val png = WirelessAdbController(QuestApp.appContext)
            .captureScreenshotPng(autoConnectIfNeeded)
        val source = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: error("Nie udało się zdekodować screenshotu ADB.")

        val likelyBlank = isLikelyBlank(source)
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
                val jpeg = out.toByteArray()
                val digest = MessageDigest.getInstance("SHA-256").digest(jpeg)
                    .take(10).joinToString("") { "%02x".format(it) }
                AdbVisionFrame(
                    dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP),
                    capturedAt = System.currentTimeMillis(),
                    hash = digest,
                    width = outputBitmap.width,
                    height = outputBitmap.height,
                    likelyBlank = likelyBlank,
                )
            }
        } finally {
            outputBitmap.recycle()
        }
    }

    private fun isLikelyBlank(bitmap: Bitmap): Boolean {
        var count = 0
        var sum = 0.0
        var sumSq = 0.0
        val samples = 8
        for (iy in 0 until samples) {
            val y = ((iy + 0.5f) * bitmap.height / samples).toInt().coerceIn(0, bitmap.height - 1)
            for (ix in 0 until samples) {
                val x = ((ix + 0.5f) * bitmap.width / samples).toInt().coerceIn(0, bitmap.width - 1)
                val pixel = bitmap.getPixel(x, y)
                val luminance = 0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)
                sum += luminance
                sumSq += luminance * luminance
                count++
            }
        }
        val mean = sum / count.coerceAtLeast(1)
        val variance = (sumSq / count.coerceAtLeast(1)) - mean * mean
        return mean < 12.0 && variance < 30.0
    }
}
