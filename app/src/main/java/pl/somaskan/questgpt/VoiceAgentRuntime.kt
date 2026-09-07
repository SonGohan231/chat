package pl.somaskan.questgpt

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object VoiceAgentRuntime {
    var desiredRunning by mutableStateOf(false)
    var running by mutableStateOf(false)
    var state by mutableStateOf("Głos wyłączony")
    var lastError by mutableStateOf<String?>(null)
    var reconnectAttempt by mutableStateOf(0)
    var lastUserTranscript by mutableStateOf("")
    var assistantTranscript by mutableStateOf("")

    fun resetConversationDraft() {
        lastUserTranscript = ""
        assistantTranscript = ""
    }
}
