package pl.somaskan.questgpt

import android.content.Context
import android.content.Intent
import android.os.Build

object AgentServiceController {
    private const val PREFS = "questgpt_agent"
    private const val KEY_ENABLED = "service_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) start(context) else stop(context)
    }

    fun startIfEnabled(context: Context) {
        if (isEnabled(context)) start(context)
    }

    fun start(context: Context) {
        val intent = Intent(context, AgentForegroundService::class.java).setAction(AgentForegroundService.ACTION_START)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, AgentForegroundService::class.java))
    }
}
