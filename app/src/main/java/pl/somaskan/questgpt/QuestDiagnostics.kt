package pl.somaskan.questgpt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import pl.somaskan.questgpt.adb.AdbVisionMonitor
import pl.somaskan.questgpt.adb.WirelessAdbController
import java.util.concurrent.TimeUnit

data class DiagnosticItem(val name: String, val ok: Boolean, val detail: String)
data class DiagnosticReport(val items: List<DiagnosticItem>, val ranAt: Long = System.currentTimeMillis())

object QuestDiagnostics {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun run(context: Context, backendUrl: String): DiagnosticReport = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val items = mutableListOf<DiagnosticItem>()

        val backend = QuestEndpoints.resolveBackend(backendUrl)
        val backendResult = runCatching {
            http.newCall(Request.Builder().url("$backend/api/health").get().build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                response.body?.string().orEmpty().take(240)
            }
        }
        items += DiagnosticItem(
            "Backend OpenAI",
            backendResult.isSuccess,
            backendResult.getOrElse { it.message ?: "brak połączenia" }.ifBlank { "OK" },
        )

        val realtimeToken = runCatching {
            http.newCall(Request.Builder().url("$backend/api/realtime-token?mode=text").get().build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                check(response.isSuccessful) { "HTTP ${response.code}: ${raw.take(180)}" }
                val json = JSONObject(raw)
                check(json.optString("value").isNotBlank()) { "brak client secret" }
                "GET działa • model=${json.optString("model", "gpt-realtime")} • tryb=${json.optString("modality", "text")}"
            }
        }
        items += DiagnosticItem(
            "Native Realtime token",
            realtimeToken.isSuccess,
            realtimeToken.getOrElse { it.message ?: "nie udało się pobrać krótkotrwałego tokenu" },
        )

        val mic = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        items += DiagnosticItem("Mikrofon", mic, if (mic) "Uprawnienie przyznane" else "Brak uprawnienia RECORD_AUDIO")

        val notifications = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        items += DiagnosticItem("Powiadomienia", notifications, if (notifications) "Gotowe" else "Brak uprawnienia do powiadomień")

        val installs = app.packageManager.canRequestPackageInstalls()
        items += DiagnosticItem("Aktualizacje APK", installs, if (installs) "Instalowanie APK dozwolone" else "Brak zgody na instalację z tego źródła")

        val passthrough = PassthroughCameraCapture(app)
        val cameraPermission = passthrough.hasPermission()
        val cameraSupported = runCatching { passthrough.isSupported() }.getOrDefault(false)
        val worldEnabled = WorldVisionManager.isEnabled(app)
        items += DiagnosticItem(
            "World Vision / passthrough camera",
            cameraSupported && cameraPermission,
            when {
                !cameraSupported -> "Nie znaleziono kamery passthrough przez Camera2. Wymagany Quest 3/3S i odpowiednio nowy Horizon OS."
                !cameraPermission -> "Kamera wykryta, ale brakuje CAMERA + HEADSET_CAMERA."
                worldEnabled -> "Kamera gotowa; World Vision włączone."
                else -> "Kamera gotowa; World Vision jest wyłączone przez użytkownika."
            },
        )
        if (cameraSupported && cameraPermission && worldEnabled) {
            val worldTest = runCatching { WorldVisionManager.captureNow(app) }
            items += DiagnosticItem(
                "Klatka fizycznego świata",
                worldTest.isSuccess,
                worldTest.fold({ "Przechwycono obraz JPEG (${it.length / 1024} KB data URL)" }, { it.message ?: "błąd kamery" }),
            )
        }

        items += DiagnosticItem(
            "Agent Service",
            AgentServiceController.isEnabled(app),
            if (AgentServiceController.isEnabled(app)) "Włączony; runtime=${pl.somaskan.questgpt.adb.QuestAgentRuntime.serviceRunning}" else "Wyłączony",
        )
        items += DiagnosticItem(
            "Voice Agent Service",
            VoiceAgentRuntime.running || !VoiceAgentRuntime.desiredRunning,
            when {
                VoiceAgentRuntime.running -> "Połączony i działa w tle"
                VoiceAgentRuntime.desiredRunning -> "Ma działać, ale nie jest połączony: ${VoiceAgentRuntime.state}"
                else -> "Wyłączony przez użytkownika"
            },
        )

        val adb = WirelessAdbController(app)
        val connected = if (adb.isConnected()) true else runCatching { adb.autoConnect(3_500L) }.getOrDefault(false)
        items += DiagnosticItem("Wireless ADB", connected, if (connected) "Połączone" else "Nie udało się połączyć z zapisanym ADB")

        if (connected) {
            val shell = runCatching { adb.runCommand("echo QUESTGPT_ADB_OK") }
            items += DiagnosticItem("ADB shell", shell.getOrNull()?.contains("QUESTGPT_ADB_OK") == true, shell.getOrElse { it.message ?: "błąd shell" }.take(240))

            val screenshot = runCatching { adb.captureScreenshotPng(autoConnectIfNeeded = false) }
            items += DiagnosticItem(
                "ADB screenshot",
                screenshot.isSuccess,
                screenshot.fold({ "PNG ${it.size / 1024} KB" }, { it.message ?: "błąd screencap" }),
            )

            val hierarchy = runCatching { adb.uiHierarchyXml() }
            val hierarchyText = hierarchy.getOrDefault("")
            items += DiagnosticItem(
                "UIAutomator",
                hierarchy.isSuccess && hierarchyText.contains("<hierarchy"),
                if (hierarchyText.contains("<hierarchy")) "Drzewo UI: ${hierarchyText.length} znaków" else hierarchy.exceptionOrNull()?.message ?: "Brak drzewa UI",
            )
        }

        DiagnosticReport(items)
    }

    suspend fun repair(context: Context, backendUrl: String): DiagnosticReport = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        AgentServiceController.startIfEnabled(app)
        AdbVisionMonitor.start()
        runCatching { WirelessAdbController(app).autoConnect(6_000L) }
        if (VoiceAgentRuntime.desiredRunning && ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runCatching { VoiceAgentController.start(app, backendUrl) }
        }
        run(app, backendUrl)
    }
}
