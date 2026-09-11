package pl.somaskan.questgpt2

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayOutputStream

class ScreenService : Service() {
    private val thread = HandlerThread("Screen capture")
    private lateinit var worker: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var ending = false
    private var lastFrameAt = 0L
    private var startedAt = 0L
    private var sequence = 0L
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { stopCapture("Udostępnianie zakończone przez system. Włącz je ponownie, aby uzyskać nową zgodę.") }
    }
    override fun onCreate() {
        super.onCreate()
        thread.start(); worker = Handler(thread.looper)
        Hub.screenService = this
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent == null || intent.action == "STOP") { stopCapture(); return START_NOT_STICKY }
        if(projection != null) return START_NOT_STICKY
        try {
            startForeground(201, Notifications.build(this, "QuestGPT · udostępnianie ekranu", "Obraz może być wysyłany do OpenAI podczas Live."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            @Suppress("DEPRECATION")
            val consent = intent.getParcelableExtra<Intent>("consent") ?: error("Brak zgody na ekran")
            require(intent.getIntExtra("code", 0) == Activity.RESULT_OK)
            // Create the projection only AFTER foreground promotion, use consent exactly once.
            val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, consent)
            projection = p
            p.registerCallback(callback, worker)
            val w = intent.getIntExtra("width", 1280).coerceIn(320, 1600)
            val h = intent.getIntExtra("height", 720).coerceIn(240, 1600)
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            reader = r
            r.setOnImageAvailableListener({ source ->
                if (!ending) runCatching { source.acquireLatestImage()?.use { image -> receive(image) } }
                    .onFailure { stopCapture("Błąd przechwytywania: ${it.javaClass.simpleName}. Uruchom udostępnianie ponownie.") }
            }, worker)
            display = p.createVirtualDisplay("QuestGPT user-approved screen share", w, h, resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, worker)
            startedAt = SystemClock.elapsedRealtime()
            Hub.change { it.copy(sharing = true, capture = "Czekam na pierwszą klatkę…", frame = null, sentFrames = 0, lastSentAt = 0) }
            worker.postDelayed(watchdog, 3000)
        } catch(e: Exception) { stopCapture("Nie udało się udostępnić ekranu: ${Protocol.safe(e.message.orEmpty())}") }
        return START_NOT_STICKY
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if(ending) return
            val now = SystemClock.elapsedRealtime()
            if (lastFrameAt == 0L && now - startedAt > 12000L) {
                stopCapture("System nie dostarczył obrazu. Zakończ inne przesyłanie/nagrywanie i spróbuj ponownie.")
                return
            }
            if(lastFrameAt > 0L && now - lastFrameAt > 5000L) Hub.change { it.copy(capture = "Brak świeżych klatek — obraz nie jest wysyłany.", frame = null) }
            worker.postDelayed(this, 3000)
        }
    }
    private fun receive(image: Image) {
        val now = SystemClock.elapsedRealtime()
        if(ending || now - lastFrameAt < 750L) return
        val plane = image.planes.first()
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        var cropped: Bitmap? = null
        try {
            padded.copyPixelsFromBuffer(plane.buffer)
            val rect = image.cropRect
            val b = Bitmap.createBitmap(padded, rect.left, rect.top, rect.width(), rect.height())
            cropped = b
            var brightest = 0
            for(y in 1..8) for(x in 1..12) {
                val c = b.getPixel(x * (b.width - 1) / 13, y * (b.height - 1) / 9)
                brightest = maxOf(brightest, (c shr 16) and 255, (c shr 8) and 255, c and 255)
            }
            val blank = brightest < 9
            val bytes = ByteArrayOutputStream().use { out -> b.compress(Bitmap.CompressFormat.JPEG, 77, out); out.toByteArray() }
            if (ending) return
            lastFrameAt = now
            val frame = Frame("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), now, ++sequence, blank)
            Hub.change { if(ending || !it.sharing) it else it.copy(frame = frame, capture = if(blank) "Ciemna/pusta klatka — możliwa blokada aplikacji." else "Ekran udostępniany · klatka ${sequence}") }
        } finally { if(cropped !== padded) cropped?.recycle(); padded.recycle() }
    }
    @Synchronized fun stopCapture(reason: String = "Ekran nieudostępniany") {
        if(ending) return
        ending = true
        Hub.change { it.copy(sharing = false, frame = null, capture = reason) }
        Hub.voiceService?.clearScreenContext()
        worker.post {
            runCatching { reader?.setOnImageAvailableListener(null, null) }
            runCatching { display?.release() }; display = null
            runCatching { reader?.close() }; reader = null
            runCatching { projection?.unregisterCallback(callback); projection?.stop() }; projection = null
            Hub.main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
    override fun onDestroy() {
        if(!ending) stopCapture()
        if(Hub.screenService === this) Hub.screenService = null
        thread.quitSafely()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
