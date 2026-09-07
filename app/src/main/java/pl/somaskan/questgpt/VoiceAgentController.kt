package pl.somaskan.questgpt

import android.content.Context
import android.content.Intent
import android.os.Build

object VoiceAgentController {
    private const val PREFS = "questgpt_voice"
    private const val KEY_DESIRED = "desired_running"

    fun start(context: Context, backendUrl: String, initialPrompt: String? = null) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DESIRED, true).apply()
        VoiceAgentRuntime.desiredRunning = true
        val intent = Intent(app, VoiceAgentService::class.java)
            .setAction(VoiceAgentService.ACTION_START)
            .putExtra(VoiceAgentService.EXTRA_BACKEND_URL, backendUrl)
        if (!initialPrompt.isNullOrBlank()) intent.putExtra(VoiceAgentService.EXTRA_INITIAL_PROMPT, initialPrompt)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DESIRED, false).apply()
        VoiceAgentRuntime.desiredRunning = false
        VoiceAgentRuntime.running = false
        VoiceAgentRuntime.state = "Głos wyłączony"
        VoiceAgentRuntime.resetConversationDraft()
        app.stopService(Intent(app, VoiceAgentService::class.java))
    }
}
