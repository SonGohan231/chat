package pl.somaskan.questgpt2

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

class AudioEngine(private val context: Context, private val onPcm: (ByteArray) -> Unit, private val onError: (String) -> Unit) {
    private val workers = Executors.newFixedThreadPool(2)
    private val queue = ArrayBlockingQueue<Pair<Int, ByteArray>>(160)
    private val generation = AtomicInteger(0)
    @Volatile private var running = false
    @Volatile var muted = false
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private val manager = context.getSystemService(AudioManager::class.java)
    private var oldMode = AudioManager.MODE_NORMAL
    private var focus: AudioFocusRequest? = null
    private var itemId: String? = null
    private var itemStartHead = 0L
    private var written = 0L
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (configs.any { it.clientAudioSessionId == record?.audioSessionId && it.isClientSilenced })
                Hub.note("System wyciszył mikrofon QuestGPT. Wyłącz mikrofon/czat głosowy w drugiej aplikacji i wróć do Live.")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener { value ->
                if (value == AudioManager.AUDIOFOCUS_LOSS) onError("Inna aplikacja przejęła dźwięk. Uruchom Live ponownie.")
            }.build()
        focus = request
        if(manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) error("System nie udostępnił dźwięku. Wróć do panelu i spróbuj ponownie.")
        oldMode = manager.mode
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        val min = AudioRecord.getMinBufferSize(24000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "Mikrofon nie obsługuje 24 kHz." }
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 24000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 9600))
        record = r
        check(r.state == AudioRecord.STATE_INITIALIZED) { "Nie można otworzyć mikrofonu." }
        if(AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
        if(NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
        val size = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder().setSampleRate(24000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(size * 2, 9600)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        check(t.state == AudioTrack.STATE_INITIALIZED) { "Nie można otworzyć głośnika." }
        manager.registerAudioRecordingCallback(recordingCallback, Hub.main)
        t.play(); r.startRecording(); running = true
        workers.execute {
            try {
                val samples = ShortArray(480)
                var meterAt = 0L
                while(running) {
                    val n = r.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if(n < 0) { if(running) onError("Mikrofon został zatrzymany przez system ($n)."); break }
                    if(n == 0) continue
                    val bytes = ByteArray(n * 2)
                    var energy = 0.0
                    for(i in 0 until n) {
                        val sample = if(muted) 0 else samples[i].toInt()
                        bytes[i*2] = sample.toByte(); bytes[i*2+1] = (sample shr 8).toByte()
                        energy += sample.toDouble() * sample
                    }
                    if(!muted && running) onPcm(bytes)
                    val now = SystemClock.elapsedRealtime()
                    if(now - meterAt > 180) {
                        meterAt = now
                        val level = (sqrt(energy / n) / 100).toInt().coerceIn(0, 100)
                        Hub.change { it.copy(micLevel = level) }
                    }
                }
            } catch(e: Exception) { if(running) onError("Mikrofon: ${e.javaClass.simpleName}") }
        }
        workers.execute {
            try {
                while(running) {
                    val chunk = queue.poll(150, TimeUnit.MILLISECONDS) ?: continue
                    var offset = 0
                    while(running && chunk.first == generation.get() && offset < chunk.second.size) {
                        val n = t.write(chunk.second, offset, chunk.second.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                        if(n < 0) { onError("Błąd odtwarzania audio ($n)."); break }
                        if(n == 0) Thread.sleep(5) else { offset += n; synchronized(this) { written += n/2 } }
                    }
                }
            } catch(e: Exception) { if(running) onError("Głośnik: ${e.javaClass.simpleName}") }
        }
    }
    @Synchronized fun enqueue(id: String, bytes: ByteArray) {
        if(!running) return
        if(itemId != id) { itemId = id; itemStartHead = track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0L; written = 0L }
        if(!queue.offer(generation.get() to bytes)) onError("Dźwięk napływa zbyt szybko. Sesja została zatrzymana; uruchom Live ponownie.")
    }
    @Synchronized fun interrupt(): Pair<String, Long>? {
        val head = track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0L
        val ms = (head - itemStartHead).coerceIn(0L, written) * 1000L / 24000L
        val result = itemId?.let { it to ms }
        generation.incrementAndGet(); queue.clear(); itemId = null; written = 0L
        runCatching { track?.pause(); track?.flush(); if(running) track?.play() }
        return result
    }
    fun stop() {
        running = false
        generation.incrementAndGet(); queue.clear()
        runCatching { record?.stop() }
        workers.shutdownNow()
        runCatching { manager.unregisterAudioRecordingCallback(recordingCallback) }
        runCatching { echo?.release(); noise?.release() }
        runCatching { record?.release() }; record = null
        runCatching { track?.pause(); track?.flush(); track?.release() }; track = null
        focus?.let { manager.abandonAudioFocusRequest(it) }
        if(manager.mode == AudioManager.MODE_IN_COMMUNICATION) manager.mode = oldMode
    }
}
