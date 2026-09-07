package pl.somaskan.questgpt

import android.content.Context
import android.content.Intent
import android.os.Build

object VoiceAgentController {
    fun start(context: Context, backendUrl: String, initialPrompt: String? = null) {
        val intent = Intent(context, VoiceAgentService::class.java)
            .setAction(VoiceAgentService.ACTION_START)
            .putExtra(VoiceAgentService.EXTRA_BACKEND_URL, backendUrl)
        if (!initialPrompt.isNullOrBlank()) intent.putExtra(VoiceAgentService.EXTRA_INITIAL_PROMPT, initialPrompt)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, VoiceAgentService::class.java).setAction(VoiceAgentService.ACTION_STOP)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }
}
