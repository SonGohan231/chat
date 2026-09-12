package pl.somaskan.questgpt2

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.SystemClock

object QuestRuntime {
    fun isQuest(context: Context): Boolean =
        context.packageManager.hasSystemFeature("android.hardware.vr.headtracking") ||
            Build.MANUFACTURER.contains("oculus", true) || Build.MODEL.contains("Quest", true)

    fun openEnvironment(activity: Activity) {
        val mr = isQuest(activity) && Build.SUPPORTED_ABIS.contains("arm64-v8a")
        runCatching { activity.startActivity(Intent().setClassName(activity,
            if (mr) "pl.somaskan.questgpt2.ImmersiveActivity" else "pl.somaskan.questgpt2.EnvironmentActivity")) }
            .onFailure { Hub.note("Nie można uruchomić MR. Zaktualizuj Horizon OS; dostępny jest podgląd kamery w panelu.") }
    }
}

class EnvironmentController(private val activity: Activity, private val explainCamera: ((() -> Unit) -> Unit)? = null) {
    companion object {
        const val CAMERA_EXPLANATION = "Kamera RGB gogli pokaże fizyczne otoczenie. W Live do OpenAI trafi zdjęcie co około 2 sekundy oraz przy pytaniu. Bez Live obraz zostaje lokalnie do chwili wysłania pytania. Obrazy zużywają środki API.\n\nPrzycisk Kamera wyłącza udostępnianie. Stop wszystko kończy kamerę, mikrofon i ekran. Passthrough dla Ciebie może nadal pozostać widoczny."
    }
    private var pendingCamera = false
    private var pendingVoice = false
    fun camera() {
        if (Hub.state.cameraActive) { Hub.cameraService?.stopCamera(); return }
        val prefs = activity.getSharedPreferences("questgpt2_ui", 0)
        if (!prefs.getBoolean("camera_explained", false)) {
            val accept: () -> Unit = { prefs.edit().putBoolean("camera_explained", true).apply(); cameraPermission() }
            if (explainCamera != null) explainCamera.invoke(accept)
            else AlertDialog.Builder(activity).setTitle("Pokaż otoczenie asystentowi")
                .setMessage(CAMERA_EXPLANATION)
                .setPositiveButton("Włącz kamerę") { _, _ -> accept() }
                .setNegativeButton("Anuluj", null).show()
        } else cameraPermission()
    }
    private fun cameraPermission() {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCamera = true; activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), 502); return
        }
        runCatching { activity.startForegroundService(Intent(activity, CameraService::class.java).setAction("START")) }
            .onFailure { Hub.note("Włącz kamerę z widocznego panelu Questa. ${Protocol.safe(it.message.orEmpty())}") }
    }
    fun voice() {
        if (Hub.state.voiceActive) { Hub.voiceService?.stopVoice(); return }
        if (Hub.state.busy) { Hub.note("Poczekaj na odpowiedź tekstową."); return }
        if (!CredentialStore(activity).hasKey()) { Hub.note("Najpierw otwórz Czat → ⋯ → Połączenie i zapisz klucz API."); return }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoice = true; activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 503); return
        }
        runCatching { activity.startForegroundService(Intent(activity, VoiceService::class.java).setAction("START").putExtra("greeting", false)) }
            .onFailure { Hub.note("Uruchom Live z widocznego panelu gogli.") }
    }
    fun permissionResult(code: Int, results: IntArray): Boolean {
        if (code == 502 && pendingCamera) {
            pendingCamera = false
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) cameraPermission()
            else Hub.note("Nie przyznano dostępu do kamery. Możesz ponowić zgodę w ustawieniach aplikacji.")
            return true
        }
        if (code == 503 && pendingVoice) {
            pendingVoice = false
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) voice() else Hub.note("Nie przyznano dostępu do mikrofonu.")
            return true
        }
        return false
    }
    fun ask(prompt: String = "Co widzisz przede mną?") {
        val frame = Hub.state.activeFrame(SystemClock.elapsedRealtime())
        if (frame == null) { Hub.note("Brak świeżego obrazu. Włącz kamerę i poczekaj na podgląd."); return }
        Api.ask(activity, Protocol.frameCaption(frame) + "\n" + prompt, listOf(frame.dataUrl))
    }
}
