package pl.somaskan.questgpt

import android.content.Context

object WorldVisionManager {
    private const val PREFS = "questgpt_world_vision"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        WorldVisionRuntime.enabled = enabled
        WorldVisionRuntime.status = if (enabled) "World Vision: gotowe do przechwytywania" else "World Vision: wyłączone"
        if (!enabled) {
            WorldVisionRuntime.lastPreviewDataUrl = null
            WorldVisionRuntime.lastError = null
        }
    }

    fun refreshRuntime(context: Context) {
        WorldVisionRuntime.enabled = isEnabled(context)
        if (!WorldVisionRuntime.enabled) WorldVisionRuntime.status = "World Vision: wyłączone"
    }

    suspend fun captureDataUrlIfEnabled(context: Context): String? {
        if (!isEnabled(context)) return null
        return runCatching { captureNow(context) }.getOrNull()
    }

    suspend fun captureNow(context: Context): String {
        val camera = PassthroughCameraCapture(context.applicationContext)
        check(camera.hasPermission()) { "Brak uprawnienia CAMERA/HEADSET_CAMERA." }
        WorldVisionRuntime.status = "World Vision: przechwytywanie…"
        return runCatching { camera.captureJpegDataUrl() }
            .onSuccess { dataUrl ->
                WorldVisionRuntime.lastPreviewDataUrl = dataUrl
                WorldVisionRuntime.lastFrameAt = System.currentTimeMillis()
                WorldVisionRuntime.lastError = null
                WorldVisionRuntime.status = "World Vision: obraz fizycznego świata gotowy"
            }
            .onFailure {
                WorldVisionRuntime.lastError = it.message
                WorldVisionRuntime.status = "World Vision: błąd"
            }
            .getOrThrow()
    }
}
