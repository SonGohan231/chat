package pl.somaskan.questgptfree

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Captures exactly one user-approved local image, then releases projection. No networking. */
class SnapshotService : Service() {
    private val thread=HandlerThread("Free local snapshot")
    private lateinit var worker: Handler
    private var projection: MediaProjection?=null
    private var display: VirtualDisplay?=null
    private var reader: ImageReader?=null
    private val ending=AtomicBoolean(false)
    private var captureAt=0L
    private var latest: Bitmap?=null
    private var decodedAt=0L
    private val callback=object: MediaProjection.Callback() {
        override fun onStop() { finishCapture("Przechwytywanie zakończone przez system.") }
    }
    override fun onCreate() { super.onCreate(); thread.start(); worker=Handler(thread.looper) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent == null || intent.action == "STOP") { finishCapture("Zrzut anulowany."); return START_NOT_STICKY }
        if(projection != null || ending.get()) return START_NOT_STICKY
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("snapshot","Lokalny zrzut ekranu",NotificationManager.IMPORTANCE_LOW))
            val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop=PendingIntent.getService(this,1,Intent(this,SnapshotService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
            val notification=Notification.Builder(this,"snapshot").setSmallIcon(R.drawable.ic_free).setContentTitle("QuestGPT Free · zrzut za 5 sekund")
                .setContentText("Obraz zostanie zapisany lokalnie. Nic nie jest wysyłane do AI.")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Anuluj",stop).build()).build()
            startForeground(301,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            @Suppress("DEPRECATION")
            val consent=intent.getParcelableExtra<Intent>("consent") ?: error("Brak zgody")
            require(intent.getIntExtra("code",0)==Activity.RESULT_OK)
            val p=getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,consent)
            projection=p
            p.registerCallback(callback,worker)
            val w=intent.getIntExtra("width",1280).coerceIn(320,1600)
            val h=intent.getIntExtra("height",720).coerceIn(240,1600)
            captureAt=SystemClock.elapsedRealtime()+5000
            val r=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3)
            reader=r
            r.setOnImageAvailableListener({ source ->
                runCatching {
                    source.acquireLatestImage()?.use { image ->
                        val now=SystemClock.elapsedRealtime()
                        if(ending.get() || now-decodedAt<200) return@use
                        val plane=image.planes.first()
                        val padded=Bitmap.createBitmap(plane.rowStride/plane.pixelStride,image.height,Bitmap.Config.ARGB_8888)
                        var cropped: Bitmap?=null
                        try {
                            val bytes=ByteBuffer.allocate(padded.byteCount)
                            bytes.put(plane.buffer); bytes.rewind(); padded.copyPixelsFromBuffer(bytes)
                            val rect=image.cropRect
                            val b=Bitmap.createBitmap(padded,rect.left,rect.top,rect.width(),rect.height())
                            cropped=b
                            latest?.recycle()
                            latest=b.copy(Bitmap.Config.ARGB_8888,false)
                            decodedAt=now
                            if(now>=captureAt) saveLatest()
                        } finally { if(cropped !== padded) cropped?.recycle(); padded.recycle() }
                    }
                }.onFailure { finishCapture("Nie udało się zapisać zrzutu. Spróbuj ponownie lub użyj zrzutu systemowego Questa.") }
            },worker)
            display=p.createVirtualDisplay("QuestGPT Free local screenshot",w,h,resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,r.surface,null,worker)
            SnapshotStore.status(this,"Zrzut za 5 sekund. Wróć do gry przyciskiem Meta. Przechwytywanie zakończy się samo.")
            // Static screens may not produce another frame at the deadline. Keep the
            // most recent actual frame and save it even when the display is unchanged.
            worker.postDelayed({saveLatest()},maxOf(0L,captureAt-SystemClock.elapsedRealtime()))
            worker.postDelayed({ finishCapture("System nie dostarczył obrazu. Użyj zrzutu systemowego Questa.") },20000)
        } catch(e: Exception) { finishCapture("Nie można uruchomić zrzutu. Użyj zrzutu systemowego Questa i dołącz go w ChatGPT.") }
        return START_NOT_STICKY
    }
    private fun saveLatest() {
        if(ending.get()) return
        val b=latest ?: return
        try {
            var brightest=0
            for(y in 1..8) for(x in 1..12) {
                val c=b.getPixel(x*(b.width-1)/13,y*(b.height-1)/9)
                brightest=maxOf(brightest,(c shr 16) and 255,(c shr 8) and 255,c and 255)
            }
            if(brightest<9) {finishCapture("Pusty lub chroniony obraz. Użyj systemowego zrzutu Questa.");return}
            SnapshotStore.save(this,b)
            finishCapture()
        } catch(e: Exception) {finishCapture("Nie udało się zapisać zrzutu. Spróbuj ponownie.")}
    }
    private fun finishCapture(message: String?=null) {
        if(!ending.compareAndSet(false,true)) return
        message?.let { SnapshotStore.status(this,it) }
        worker.post {
            latest?.recycle();latest=null
            reader?.setOnImageAvailableListener(null,null)
            runCatching { display?.release() }; display=null
            runCatching { reader?.close() }; reader=null
            runCatching { projection?.unregisterCallback(callback); projection?.stop() }; projection=null
            Handler(mainLooper).post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
    override fun onDestroy() { finishCapture(); thread.quitSafely(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder?=null
}
