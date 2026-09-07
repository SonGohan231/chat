package pl.somaskan.questgpt.adb

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class PendingAgentConfirmation(
    val id: String,
    val title: String,
    val details: String,
    val createdAt: Long = System.currentTimeMillis(),
)

object AgentConfirmationCenter {
    private val lock = Any()
    private var waiter: CompletableDeferred<Boolean>? = null

    var pending by mutableStateOf<PendingAgentConfirmation?>(null)
        private set
    var uiAvailable by mutableStateOf(false)
        private set

    fun setUiAvailable(available: Boolean) {
        uiAvailable = available
        if (!available) deny()
    }

    suspend fun request(title: String, details: String): Boolean {
        if (!uiAvailable) {
            QuestAgentRuntime.lastError = "Ta akcja wymaga potwierdzenia w otwartym panelu QuestGPT."
            return false
        }
        val deferred = CompletableDeferred<Boolean>()
        synchronized(lock) {
            if (waiter != null) {
                QuestAgentRuntime.lastError = "Inne potwierdzenie akcji jest już oczekujące."
                return false
            }
            waiter = deferred
            pending = PendingAgentConfirmation(UUID.randomUUID().toString(), title, details)
        }
        QuestAgentRuntime.confirmationStatus = "Oczekuje na potwierdzenie: $title"
        val approved = withTimeoutOrNull(30_000L) { deferred.await() } ?: false
        synchronized(lock) {
            if (waiter === deferred) {
                waiter = null
                pending = null
            }
        }
        QuestAgentRuntime.confirmationStatus = if (approved) "Akcja zatwierdzona" else "Akcja odrzucona lub wygasła"
        return approved
    }

    fun approve() = complete(true)
    fun deny() = complete(false)

    private fun complete(value: Boolean) {
        val current = synchronized(lock) {
            val local = waiter
            waiter = null
            pending = null
            local
        }
        current?.complete(value)
    }
}
