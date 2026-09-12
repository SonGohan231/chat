package pl.somaskan.questgpt2

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

class CameraService : Service() {
    private val thread = HandlerThread("Quest RGB camera")
    private lateinit var worker: Handler
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var ending = false
    private var starting = false
    private var lastFrameAt = 0L
    private var startedAt = 0L
    private var sequence = 0L
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stopCamera("Kamera zatrzymana po uśpieniu gogli.") }
    }
    override fun onCreate() {
        super.onCreate(); thread.start(); worker = Handler(thread.looper); Hub.cameraService = this
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "STOP") { stopCamera(); return START_NOT_STICKY }
        if (starting || ending) return START_NOT_STICKY
        starting = true
        runCatching {
            check(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { "Zezwól na kamerę w ustawieniach aplikacji." }
            startForeground(203, Notifications.build(this, "QuestGPT · kamera otoczenia", "W Live zdjęcia otoczenia trafiają do OpenAI. Stop wyłącza kamerę."), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            Hub.screenService?.stopCapture()
            Hub.voiceService?.clearScreenContext("Zmieniono źródło na kamerę otoczenia. Czekaj na świeże zdjęcie.")
            Hub.change { it.copy(cameraActive = true, cameraFrame = null, visionSource = VisionSource.CAMERA,
                cameraStatus = "Otwieram kamerę otoczenia…", note = "", sentFrames = 0, lastSentAt = 0) }
            startedAt = SystemClock.elapsedRealtime()
            worker.post { runCatching { open() }.onFailure { fail(it.message.orEmpty()) } }
            worker.postDelayed(watchdog, 2500)
        }.onFailure { fail(it.message.orEmpty()) }
        return START_NOT_STICKY
    }
    @SuppressLint("MissingPermission")
    private fun open() {
        if (ending) return
        val manager = getSystemService(CameraManager::class.java)
        val descriptors = manager.cameraIdList.map { id ->
            val c = manager.getCameraCharacteristics(id)
            fun tag(name: String): Int? = runCatching { c.get(CameraCharacteristics.Key(name, Int::class.javaObjectType)) }.getOrNull()
            CameraDescriptor(id, tag("com.meta.extra_metadata.camera_source"), tag("com.meta.extra_metadata.position"),
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK)
        }
        val selected = CameraSelection.choose(descriptors, QuestRuntime.isQuest(this))
            ?: error("Brak kamery RGB Questa. Włącz passthrough, zaktualizuj Horizon OS i sprawdź zgodę na kamerę.")
        val c = manager.getCameraCharacteristics(selected.id)
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val size = sizes.filter { it.width <= 1280 && it.height <= 1280 }.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height } ?: error("Kamera nie udostępnia formatu YUV.")
        check(size.width * size.height <= 4096 * 2160) { "Nieobsługiwany rozmiar klatki." }
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        reader = r
        r.setOnImageAvailableListener({ source ->
            if (!ending) runCatching { source.acquireLatestImage()?.use { receive(it) } }.onFailure { fail("Odczyt obrazu: ${it.javaClass.simpleName}") }
        }, worker)
        manager.openCamera(selected.id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (ending) { device.close(); return }
                camera = device
                runCatching {
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (ending) { s.close(); return }
                            session = s
                            runCatching {
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }.build()
                                s.setRepeatingRequest(request, null, worker)
                            }.onFailure { fail("Nie można uruchomić strumienia kamery.") }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); fail("Konfiguracja kamery nie powiodła się.") }
                    }, worker)
                }.onFailure { fail("Kamera została przejęta przez inną aplikację.") }
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); fail("Kamera odłączona lub przejęta przez system.") }
            override fun onError(device: CameraDevice, error: Int) { device.close(); fail("Kamera jest niedostępna (kod $error). Zakończ użycie kamery w innych aplikacjach.") }
        }, worker)
    }
    private fun receive(image: Image) {
        val now = SystemClock.elapsedRealtime()
        if (ending || now - lastFrameAt < 750) return
        val crop = image.cropRect
        val left = (crop.left + 1) / 2 * 2
        val top = (crop.top + 1) / 2 * 2
        val width = (crop.right - left) / 2 * 2
        val height = (crop.bottom - top) / 2 * 2
        val nv21 = CameraFrames.nv21(width, height, left, top, image.planes.map { CameraFrames.Plane(it.buffer, it.rowStride, it.pixelStride) })
        val blank = CameraFrames.blank(nv21, width, height)
        val bytes = ByteArrayOutputStream().use { out ->
            check(YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 76, out))
            out.toByteArray()
        }
        if (ending) return
        lastFrameAt = now
        val frame = Frame("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), now, ++sequence, blank, VisionSource.CAMERA)
        Hub.change { if (ending) it else it.copy(cameraFrame = frame,
            cameraStatus = if (blank) "Obraz kamery jest ciemny lub pusty — nie wysyłam go." else "Kamera otoczenia · ${width}×${height} · klatka $sequence") }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (ending) return
            val now = SystemClock.elapsedRealtime()
            if (now - maxOf(startedAt, lastFrameAt) > 12000) { fail("Brak obrazu. Włącz passthrough i sprawdź zgodę na kamerę."); return }
            if (lastFrameAt > 0 && now - lastFrameAt > 4000) {
                Hub.change { it.copy(cameraFrame = null, cameraStatus = "Kamera nie dostarcza świeżych klatek.") }
                Hub.voiceService?.clearScreenContext("Kamera nie dostarcza obrazu. Nie masz aktualnego widoku otoczenia.")
            }
            worker.postDelayed(this, 2500)
        }
    }
    private fun fail(reason: String) = stopCamera("${Protocol.safe(reason)} Włącz kamerę ponownie, gdy będzie dostępna.")
    @Synchronized fun stopCamera(reason: String = "Kamera wyłączona") {
        if (ending) return
        ending = true
        Hub.change { it.copy(cameraActive = false, cameraFrame = null, cameraStatus = reason) }
        if (Hub.state.visionSource == VisionSource.CAMERA) Hub.voiceService?.clearScreenContext("Kamera została wyłączona. Nie masz aktualnego widoku otoczenia.")
        worker.removeCallbacks(watchdog)
        worker.post {
            runCatching { reader?.setOnImageAvailableListener(null, null) }
            runCatching { session?.stopRepeating() }; runCatching { session?.close() }; session = null
            runCatching { camera?.close() }; camera = null
            runCatching { reader?.close() }; reader = null
            Hub.main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
    override fun onDestroy() {
        stopCamera()
        if (Hub.cameraService === this) Hub.cameraService = null
        runCatching { unregisterReceiver(screenOff) }
        thread.quitSafely(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
