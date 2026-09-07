package pl.somaskan.questgpt.adb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.somaskan.questgpt.QuestApp
import pl.somaskan.questgpt.WorldVisionManager
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

object VisionFrameEnricher {
    suspend fun withWorldIfEnabled(screen: AdbVisionFrame): AdbVisionFrame = withContext(Dispatchers.Default) {
        val worldDataUrl = WorldVisionManager.captureDataUrlIfEnabled(QuestApp.appContext) ?: return@withContext screen
        val screenBitmap = decode(screen.dataUrl) ?: return@withContext screen
        val worldBitmap = decode(worldDataUrl) ?: run { screenBitmap.recycle(); return@withContext screen }
        try {
            val panelHeight = 56
            val targetHeight = maxOf(screenBitmap.height, worldBitmap.height).coerceAtMost(1200)
            val leftWidth = (screenBitmap.width * (targetHeight.toFloat() / screenBitmap.height)).toInt().coerceAtLeast(1)
            val rightWidth = (worldBitmap.width * (targetHeight.toFloat() / worldBitmap.height)).toInt().coerceAtLeast(1)
            val totalWidth = (leftWidth + rightWidth).coerceAtMost(2400)
            val scale = minOf(1f, totalWidth.toFloat() / (leftWidth + rightWidth).coerceAtLeast(1))
            val lw = (leftWidth * scale).toInt().coerceAtLeast(1)
            val rw = (rightWidth * scale).toInt().coerceAtLeast(1)
            val h = (targetHeight * scale).toInt().coerceAtLeast(1)
            val output = Bitmap.createBitmap(lw + rw, h + panelHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(screenBitmap, null, Rect(0, panelHeight, lw, panelHeight + h), Paint(Paint.ANTI_ALIAS_FLAG))
            canvas.drawBitmap(worldBitmap, null, Rect(lw, panelHeight, lw + rw, panelHeight + h), Paint(Paint.ANTI_ALIAS_FLAG))
            val header = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 30f
            }
            canvas.drawText("QUEST UI / EKRAN", 18f, 38f, header)
            canvas.drawText("WORLD / PASSTHROUGH", lw + 18f, 38f, header)
            return@withContext try {
                ByteArrayOutputStream().use { out ->
                    check(output.compress(Bitmap.CompressFormat.JPEG, 82, out))
                    val jpeg = out.toByteArray()
                    val hash = MessageDigest.getInstance("SHA-256").digest(jpeg)
                        .take(10).joinToString("") { "%02x".format(it) }
                    AdbVisionFrame(
                        dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP),
                        capturedAt = System.currentTimeMillis(),
                        hash = hash,
                        width = output.width,
                        height = output.height,
                        likelyBlank = screen.likelyBlank,
                    )
                }
            } finally {
                output.recycle()
            }
        } finally {
            screenBitmap.recycle()
            worldBitmap.recycle()
        }
    }

    private fun decode(dataUrl: String): Bitmap? = runCatching {
        val bytes = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
