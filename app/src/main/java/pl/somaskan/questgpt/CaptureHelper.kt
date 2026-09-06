package pl.somaskan.questgpt

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object CaptureHelper {
    suspend fun capturePng(context: Context, resultCode: Int, data: Intent): ByteArray = withContext(Dispatchers.Default) {
        if (resultCode != Activity.RESULT_OK) error("Zgoda na screenshot została anulowana")
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, data)
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1024)
        val height = metrics.heightPixels.coerceAtLeast(1024)
        val density = metrics.densityDpi
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = projection.createVirtualDisplay(
            "QuestGPTCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )

        try {
            val bitmap = suspendCancellableCoroutine<Bitmap> { cont ->
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * width
                        val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                        padded.copyPixelsFromBuffer(buffer)
                        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                        padded.recycle()
                        if (cont.isActive) cont.resume(cropped)
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWithException(t)
                    } finally {
                        image.close()
                        reader.setOnImageAvailableListener(null, null)
                    }
                }, null)
                cont.invokeOnCancellation { reader.setOnImageAvailableListener(null, null) }
            }
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                bitmap.recycle()
                out.toByteArray()
            }
        } finally {
            virtualDisplay.release()
            reader.close()
            projection.stop()
        }
    }
}
