package pl.somaskan.questgpt

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream

class PassthroughCameraCapture(private val context: Context) {
    companion object {
        const val HEADSET_CAMERA_PERMISSION = "horizonos.permission.HEADSET_CAMERA"
        private const val CAMERA_SOURCE_PASSTHROUGH = 0
        private const val CAMERA_POSITION_LEFT = 0
        private val KEY_SOURCE = CameraCharacteristics.Key<Int>("com.meta.extra_metadata.camera_source", Int::class.java)
        private val KEY_POSITION = CameraCharacteristics.Key<Int>("com.meta.extra_metadata.position", Int::class.java)
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, HEADSET_CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun isSupported(): Boolean = runCatching { findPassthroughCamera() != null }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    suspend fun captureJpegDataUrl(): String = withContext(Dispatchers.IO) {
        check(hasPermission()) { "Brak uprawnienia do kamery passthrough." }
        val target = findPassthroughCamera() ?: error("Nie znaleziono kamery passthrough. Wymagany Quest 3/3S z Horizon OS obsługującym Camera2 Passthrough.")
        val manager = context.getSystemService(CameraManager::class.java)
        val characteristics = manager.getCameraCharacteristics(target.first)
        val size = chooseSize(characteristics)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        val thread = HandlerThread("QuestGPT-WorldVision").apply { start() }
        val handler = Handler(thread.looper)
        val imageDeferred = CompletableDeferred<ByteArray>()
        val cameraDeferred = CompletableDeferred<CameraDevice>()
        val sessionDeferred = CompletableDeferred<CameraCaptureSession>()

        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!imageDeferred.isCompleted) imageDeferred.complete(yuv420ToJpeg(image, 84))
            } catch (t: Throwable) {
                if (!imageDeferred.isCompleted) imageDeferred.completeExceptionally(t)
            } finally {
                image.close()
            }
        }, handler)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            manager.openCamera(target.first, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { if (!cameraDeferred.isCompleted) cameraDeferred.complete(camera) }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (!cameraDeferred.isCompleted) cameraDeferred.completeExceptionally(IllegalStateException("Kamera passthrough została rozłączona."))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (!cameraDeferred.isCompleted) cameraDeferred.completeExceptionally(IllegalStateException("Błąd kamery passthrough: $error"))
                }
            }, handler)
            device = withTimeout(5_000L) { cameraDeferred.await() }
            device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    if (!sessionDeferred.isCompleted) sessionDeferred.complete(captureSession)
                }
                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                    if (!sessionDeferred.isCompleted) sessionDeferred.completeExceptionally(IllegalStateException("Nie udało się skonfigurować sesji passthrough."))
                }
            }, handler)
            session = withTimeout(5_000L) { sessionDeferred.await() }
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build()
            session.setRepeatingRequest(request, null, handler)
            val jpeg = withTimeout(5_000L) { imageDeferred.await() }
            "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        } finally {
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader.close() }
            thread.quitSafely()
        }
    }

    private fun findPassthroughCamera(): Pair<String, Int?>? {
        val manager = context.getSystemService(CameraManager::class.java)
        var fallback: Pair<String, Int?>? = null
        manager.cameraIdList.forEach { id ->
            val c = manager.getCameraCharacteristics(id)
            val source = runCatching { c.get(KEY_SOURCE) }.getOrNull()
            val position = runCatching { c.get(KEY_POSITION) }.getOrNull()
            if (source == CAMERA_SOURCE_PASSTHROUGH) {
                val candidate = id to position
                if (position == CAMERA_POSITION_LEFT) return candidate
                if (fallback == null) fallback = candidate
            }
        }
        return fallback
    }

    private fun chooseSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1280, 720)
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1280, 720)
        return sizes
            .filter { it.width <= 1600 && it.height <= 1200 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { kotlin.math.abs((it.width * it.height) - (1280 * 720)) }
            ?: sizes.first()
    }

    private fun yuv420ToJpeg(image: Image, quality: Int): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        copyPlane(image.planes[0], width, height, nv21, 0, 1)
        val u = image.planes[1]
        val v = image.planes[2]
        var out = width * height
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vIndex = row * v.rowStride + col * v.pixelStride
                val uIndex = row * u.rowStride + col * u.pixelStride
                nv21[out++] = v.buffer.get(vIndex)
                nv21[out++] = u.buffer.get(uIndex)
            }
        }
        return ByteArrayOutputStream().use { stream ->
            val ok = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), quality.coerceIn(60, 95), stream)
            check(ok) { "Nie udało się zakodować obrazu passthrough." }
            stream.toByteArray()
        }
    }

    private fun copyPlane(plane: Image.Plane, width: Int, height: Int, out: ByteArray, offset: Int, outPixelStride: Int) {
        var dst = offset
        for (row in 0 until height) {
            for (col in 0 until width) {
                val src = row * plane.rowStride + col * plane.pixelStride
                out[dst] = plane.buffer.get(src)
                dst += outPixelStride
            }
        }
    }
}
