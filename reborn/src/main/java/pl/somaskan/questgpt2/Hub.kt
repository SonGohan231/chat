package pl.somaskan.questgpt2

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

class QuestApplication : Application() {
    override fun onCreate() { super.onCreate(); Hub.init(this) }
}

data class State(
    val messages: List<Message> = emptyList(), val busy: Boolean = false,
    val voice: String = "Głos wyłączony", val voiceActive: Boolean = false,
    val voiceReady: Boolean = false, val muted: Boolean = false, val micLevel: Int = 0,
    val capture: String = "Ekran nieudostępniany", val sharing: Boolean = false,
    val frame: Frame? = null, val sentFrames: Int = 0, val lastSentAt: Long = 0,
    val note: String = "", val apiTest: String = "Połączenie niesprawdzone"
)

object Hub {
    private lateinit var context: Context
    val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    @Volatile var state = State(); private set
    @Volatile var voiceService: VoiceService? = null
    @Volatile var screenService: ScreenService? = null
    @Volatile var textCall: okhttp3.Call? = null
    fun init(context: Context) {
        this.context = context.applicationContext
        val history = runCatching {
            val array = JSONArray(context.getSharedPreferences("history", 0).getString("messages", "[]"))
            (0 until array.length()).map { i -> array.getJSONObject(i).let { Message(it.getString("id"), it.getString("role"), it.getString("text")) } }
        }.getOrDefault(emptyList())
        state = state.copy(messages = history)
    }
    @Synchronized fun change(block: (State) -> State) { state = block(state); main.post { observers.forEach { it() } } }
    fun observe(callback: () -> Unit) { observers.add(callback); callback() }
    fun unobserve(callback: () -> Unit) { observers.remove(callback) }
    fun note(text: String) = change { it.copy(note = Protocol.safe(text)) }
    fun message(role: String, text: String, id: String = UUID.randomUUID().toString(), complete: Boolean = true): String {
        change { old -> old.copy(messages = (old.messages.filterNot { it.id == id } + Message(id, role, text, complete)).takeLast(100)) }
        if (complete) save()
        return id
    }
    fun delta(id: String, delta: String) {
        change { old ->
            val existing = old.messages.firstOrNull { it.id == id }
            val msg = Message(id, "assistant", (existing?.text.orEmpty() + delta).take(40000), false)
            old.copy(messages = (old.messages.filterNot { it.id == id } + msg).takeLast(100))
        }
    }
    fun complete(id: String) { change { s -> s.copy(messages = s.messages.map { if (it.id == id) it.copy(complete = true) else it }) }; save() }
    fun clear() { textCall?.cancel(); voiceService?.stopVoice(); change { it.copy(messages = emptyList(), note = "Nowa rozmowa", busy = false) }; save() }
    @Synchronized private fun save() {
        val array = JSONArray()
        state.messages.filter { it.complete }.forEach { array.put(JSONObject().put("id", it.id).put("role", it.role).put("text", it.text)) }
        context.getSharedPreferences("history", 0).edit().putString("messages", array.toString()).apply()
    }
}
