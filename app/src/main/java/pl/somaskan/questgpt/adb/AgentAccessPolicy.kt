package pl.somaskan.questgpt.adb

import android.content.Context
import pl.somaskan.questgpt.QuestApp

enum class AgentPermissionLevel(val key: String, val label: String) {
    OBSERVE("observe", "Tylko patrz"),
    INTERACT("interact", "Interakcje"),
    SYSTEM("system", "System"),
}

object AgentAccessPolicy {
    private const val PREFS = "questgpt_agent"
    private const val KEY_LEVEL = "permission_level"

    fun current(): AgentPermissionLevel {
        val value = QuestApp.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LEVEL, AgentPermissionLevel.INTERACT.key)
        return AgentPermissionLevel.entries.firstOrNull { it.key == value } ?: AgentPermissionLevel.INTERACT
    }

    fun set(level: AgentPermissionLevel) {
        QuestApp.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LEVEL, level.key).apply()
        QuestAgentRuntime.permissionLevel = level
    }

    fun refreshRuntime() {
        QuestAgentRuntime.permissionLevel = current()
    }

    fun canExecute(tool: String): Boolean {
        val level = current()
        return when (tool) {
            "wait", "refresh_view" -> true
            "tap", "swipe", "type_text", "press_key", "open_app", "open_url", "launch_settings", "media_control" ->
                level == AgentPermissionLevel.INTERACT || level == AgentPermissionLevel.SYSTEM
            "volume_adjust", "set_brightness", "toggle_wifi", "toggle_bluetooth", "install_apk", "uninstall_app" ->
                level == AgentPermissionLevel.SYSTEM
            else -> false
        }
    }

    fun requiresConfirmation(tool: String): Boolean = when (tool) {
        "toggle_wifi", "toggle_bluetooth", "install_apk", "uninstall_app" -> true
        else -> false
    }

    fun minimumLevel(tool: String): AgentPermissionLevel = when (tool) {
        "wait", "refresh_view" -> AgentPermissionLevel.OBSERVE
        "tap", "swipe", "type_text", "press_key", "open_app", "open_url", "launch_settings", "media_control" -> AgentPermissionLevel.INTERACT
        else -> AgentPermissionLevel.SYSTEM
    }
}
