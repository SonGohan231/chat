package pl.somaskan.questgpt.adb

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.somaskan.questgpt.QuestApp

object AdbVisionMonitor {
    private val captureMutex = Mutex()
    @Volatile private var latestFrame: AdbVisionFrame? = null
    private var scope: CoroutineScope? = null
    private var monitorJob: Job? = null

    fun start() {
        if (monitorJob?.isActive == true) return
        val context = QuestApp.appContext
        val prefs = context.getSharedPreferences("questgpt_agent", Context.MODE_PRIVATE)
        QuestAgentRuntime.autoVisionEnabled = prefs.getBoolean("auto_vision", true)
        QuestAgentRuntime.agentControlEnabled = prefs.getBoolean("agent_control", true)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        monitorJob = newScope.launch {
            while (isActive) {
                if (!QuestAgentRuntime.autoVisionEnabled) {
                    QuestAgentRuntime.visionStatus = "ADB Vision: pauza"
                    delay(1_000L)
                    continue
                }
                val controller = WirelessAdbController(context)
                if (!controller.isConnected()) {
                    QuestAgentRuntime.visionStatus = "ADB Vision: ponowne łączenie ADB..."
                    val reconnected = runCatching { controller.autoConnect(timeoutMs = 3_000L) }.getOrDefault(false)
                    if (!reconnected) {
                        QuestAgentRuntime.visionStatus = "ADB Vision: ADB rozłączone • ponowię automatycznie"
                        delay(5_000L)
                        continue
                    }
                    QuestAgentRuntime.visionStatus = "ADB Vision: ADB połączone ponownie"
                }
                val previousHash = latestFrame?.hash
                val frame = captureNow()
                val changed = frame != null && frame.hash != previousHash
                delay(if (changed) 500L else 2_000L)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        scope?.cancel()
        scope = null
    }

    fun setAutoVisionEnabled(enabled: Boolean) {
        QuestAgentRuntime.autoVisionEnabled = enabled
        QuestApp.appContext.getSharedPreferences("questgpt_agent", Context.MODE_PRIVATE)
            .edit().putBoolean("auto_vision", enabled).apply()
        if (enabled) start()
    }

    fun setAgentControlEnabled(enabled: Boolean) {
        QuestAgentRuntime.agentControlEnabled = enabled
        QuestApp.appContext.getSharedPreferences("questgpt_agent", Context.MODE_PRIVATE)
            .edit().putBoolean("agent_control", enabled).apply()
    }

    fun latest(): AdbVisionFrame? = latestFrame

    suspend fun latestOrCapture(maxAgeMs: Long = 1_200L): AdbVisionFrame? {
        val now = System.currentTimeMillis()
        val current = latestFrame
        val screenFrame = if (current != null && now - current.capturedAt <= maxAgeMs) current else captureNow()
        return screenFrame?.let { VisionFrameEnricher.withWorldIfEnabled(it) }
    }

    suspend fun captureNow(): AdbVisionFrame? = captureMutex.withLock {
        val controller = WirelessAdbController(QuestApp.appContext)
        if (!controller.isConnected()) {
            QuestAgentRuntime.visionStatus = "ADB Vision: ADB rozłączone"
            QuestAgentRuntime.lastError = "Połącz ADB Wireless, aby GPT widział ekran."
            return@withLock null
        }
        runCatching { AdbVisionCapture.captureFrame(autoConnectIfNeeded = false) }
            .onSuccess { frame ->
                val previous = latestFrame
                latestFrame = frame
                QuestAgentRuntime.lastFrameAt = frame.capturedAt
                if (previous == null || previous.hash != frame.hash) {
                    QuestAgentRuntime.lastChangedAt = frame.capturedAt
                }
                QuestAgentRuntime.lastPreviewDataUrl = frame.dataUrl
                QuestAgentRuntime.lastError = null
                QuestAgentRuntime.visionStatus = if (frame.likelyBlank) {
                    "ADB Vision: obraz prawdopodobnie pusty/chroniony"
                } else {
                    "ADB Vision: GPT widzi ekran"
                }
            }
            .onFailure { error ->
                QuestAgentRuntime.visionStatus = "ADB Vision: błąd"
                QuestAgentRuntime.lastError = error.message ?: "Nie udało się pobrać widoku przez ADB."
            }
            .getOrNull()
    }
}
