package pl.somaskan.questgpt.adb

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object QuestAgentRuntime {
    var autoVisionEnabled by mutableStateOf(true)
    var agentControlEnabled by mutableStateOf(true)
    var serviceRunning by mutableStateOf(false)
    var permissionLevel by mutableStateOf(AgentPermissionLevel.INTERACT)
    var visionStatus by mutableStateOf("ADB Vision: oczekiwanie")
    var lastFrameAt by mutableStateOf(0L)
    var lastChangedAt by mutableStateOf(0L)
    var lastPreviewDataUrl by mutableStateOf<String?>(null)
    var currentActivity by mutableStateOf("")
    var lastAction by mutableStateOf("Brak akcji GPT")
    var lastError by mutableStateOf<String?>(null)
    var confirmationStatus by mutableStateOf("Brak oczekującego potwierdzenia")
    var actionsThisTurn by mutableStateOf(0)

    fun resetTurn() {
        actionsThisTurn = 0
        lastAction = "Analizuję aktualny widok"
        lastError = null
    }
}
