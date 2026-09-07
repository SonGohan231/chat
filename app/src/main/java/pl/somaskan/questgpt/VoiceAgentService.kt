package pl.somaskan.questgpt

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

class VoiceAgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: RealtimeVoiceClient? = null
    private var retryJob: Job? = null
    private var pendingInitialPrompt: String? = null
    private var desiredRunning = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setDesired(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        pendingInitialPrompt = intent?.getStringExtra(EXTRA_INITIAL_PROMPT)?.takeIf { it.isNotBlank() }
        desiredRunning = intent?.action == ACTION_START || prefs.getBoolean(KEY_DESIRED, false)
        if (!desiredRunning) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!OpenAICredentialStore(this).hasKey()) {
            VoiceAgentRuntime.desiredRunning = false
            VoiceAgentRuntime.running = false
            VoiceAgentRuntime.state = "Brak klucza OpenAI API"
            VoiceAgentRuntime.lastError = "Wejdź w Ustawienia > OpenAI i zapisz klucz API."
            setDesired(false)
            stopSelf()
            return START_NOT_STICKY
        }

        setDesired(true)
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Łączenie bezpośrednio z OpenAI…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        connectNow()
        return START_STICKY
    }

    private fun connectNow() {
        if (!desiredRunning) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failPermanently("Brak uprawnienia do mikrofonu", "Zezwól QuestGPT na mikrofon w ustawieniach Questa.")
            return
        }
        if (!OpenAICredentialStore(this).hasKey()) {
            failPermanently("Brak klucza OpenAI API", "Wejdź w Ustawienia > OpenAI i zapisz klucz API.")
            return
        }

        retryJob?.cancel()
        client?.stop()
        val initialPrompt = pendingInitialPrompt
        pendingInitialPrompt = null
        VoiceAgentRuntime.assistantTranscript = ""
        val newClient = RealtimeVoiceClient(
            applicationContext,
            onAssistantDelta = { delta ->
                VoiceAgentRuntime.assistantTranscript += delta
                broadcast(TYPE_ASSISTANT_DELTA, delta)
            },
            onUserTranscript = { text ->
                if (text.isNotBlank()) {
                    VoiceAgentRuntime.lastUserTranscript = text
                    VoiceAgentRuntime.assistantTranscript = ""
                    broadcast(TYPE_USER_TRANSCRIPT, text)
                }
            },
            onState = { state ->
                VoiceAgentRuntime.state = state
                VoiceAgentRuntime.running = state.startsWith("Połączono")
                if (VoiceAgentRuntime.running) {
                    VoiceAgentRuntime.reconnectAttempt = 0
                    VoiceAgentRuntime.lastError = null
                }
                updateNotification(state)
                broadcast(TYPE_STATE, state)
            },
            onError = { error ->
                VoiceAgentRuntime.running = false
                VoiceAgentRuntime.lastError = error
                updateNotification("Błąd: ${error.take(90)}")
                broadcast(TYPE_ERROR, error)
                if (isPermanentError(error)) {
                    setDesired(false)
                    stopSelf()
                } else {
                    scheduleReconnect()
                }
            },
            allowBackgroundHandoff = false,
        )
        client = newClient
        VoiceAgentRuntime.state = "Łączenie bezpośrednio z OpenAI…"
        updateNotification(VoiceAgentRuntime.state)
        newClient.start(initialPrompt)
    }

    private fun failPermanently(state: String, error: String) {
        VoiceAgentRuntime.running = false
        VoiceAgentRuntime.state = state
        VoiceAgentRuntime.lastError = error
        broadcast(TYPE_ERROR, error)
        setDesired(false)
        stopSelf()
    }

    private fun isPermanentError(error: String): Boolean =
        error.contains("Brak klucza", ignoreCase = true) ||
            error.contains("401", ignoreCase = true) ||
            error.contains("invalid", ignoreCase = true) && error.contains("key", ignoreCase = true) ||
            error.contains("uprawnienia do mikrofonu", ignoreCase = true)

    private fun scheduleReconnect() {
        if (!desiredRunning || retryJob?.isActive == true) return
        val attempt = (VoiceAgentRuntime.reconnectAttempt + 1).coerceAtMost(8)
        VoiceAgentRuntime.reconnectAttempt = attempt
        val delayMs = min(30_000L, 1_500L * (1L shl (attempt - 1).coerceAtMost(4)))
        VoiceAgentRuntime.state = "Ponowne łączenie za ${delayMs / 1000}s…"
        updateNotification(VoiceAgentRuntime.state)
        retryJob = scope.launch {
            delay(delayMs)
            if (desiredRunning) connectNow()
        }
    }

    private fun setDesired(enabled: Boolean) {
        desiredRunning = enabled
        VoiceAgentRuntime.desiredRunning = enabled
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DESIRED, enabled).apply()
        if (!enabled) VoiceAgentRuntime.resetConversationDraft()
    }

    override fun onDestroy() {
        desiredRunning = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DESIRED, false)
        retryJob?.cancel()
        client?.stop()
        client = null
        VoiceAgentRuntime.running = false
        if (!desiredRunning) {
            VoiceAgentRuntime.desiredRunning = false
            if (VoiceAgentRuntime.lastError == null) VoiceAgentRuntime.state = "Głos wyłączony"
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "QuestGPT Voice", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Utrzymuje rozmowę głosową QuestGPT także po schowaniu panelu."
            }
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("QuestGPT Live")
        .setContentText(text.take(120))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                31,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .addAction(
            android.R.drawable.ic_media_pause,
            "Zatrzymaj",
            PendingIntent.getService(
                this,
                32,
                Intent(this, VoiceAgentService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    private fun broadcast(type: String, value: String) {
        sendBroadcast(
            Intent(ACTION_EVENT)
                .setPackage(packageName)
                .putExtra(EXTRA_EVENT_TYPE, type)
                .putExtra(EXTRA_EVENT_VALUE, value)
        )
    }

    companion object {
        const val ACTION_START = "pl.somaskan.questgpt.VOICE_START"
        const val ACTION_STOP = "pl.somaskan.questgpt.VOICE_STOP"
        const val ACTION_EVENT = "pl.somaskan.questgpt.VOICE_EVENT"
        const val EXTRA_INITIAL_PROMPT = "initial_prompt"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_EVENT_VALUE = "event_value"
        const val TYPE_ASSISTANT_DELTA = "assistant_delta"
        const val TYPE_USER_TRANSCRIPT = "user_transcript"
        const val TYPE_STATE = "state"
        const val TYPE_ERROR = "error"
        private const val PREFS = "questgpt_voice"
        private const val KEY_DESIRED = "desired_running"
        private const val CHANNEL_ID = "questgpt_voice"
        private const val NOTIFICATION_ID = 2402
    }
}
