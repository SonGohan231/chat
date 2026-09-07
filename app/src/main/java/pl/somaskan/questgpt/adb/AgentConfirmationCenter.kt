package pl.somaskan.questgpt.adb

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import pl.somaskan.questgpt.AgentConfirmationActivity
import pl.somaskan.questgpt.QuestApp
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

    suspend fun request(title: String, details: String): Boolean {
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

        val opened = runCatching {
            QuestApp.appContext.startActivity(
                Intent(QuestApp.appContext, AgentConfirmationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }.isSuccess
        if (!opened) {
            deny()
            QuestAgentRuntime.lastError = "Nie udało się otworzyć panelu potwierdzenia."
            return false
        }

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
