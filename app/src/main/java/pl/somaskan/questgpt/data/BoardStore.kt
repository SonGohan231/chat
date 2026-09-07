package pl.somaskan.questgpt.data

import android.content.Context
import pl.somaskan.questgpt.ChatLine
import org.json.JSONArray
import org.json.JSONObject

class BoardStore(context: Context) {
    private val prefs = context.getSharedPreferences("questgpt_boards", Context.MODE_PRIVATE)

    data class Session(val messages: List<ChatLine>, val previousResponseId: String?)

    fun loadBoards(defaultNames: List<String>): List<Board> {
        val raw = prefs.getString("boards", null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(raw)
                return List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    Board(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        description = item.optString("description")
                    )
                }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
            }
        }
        val names = defaultNames.ifEmpty { listOf("Rozmowa", "Research", "Pliki projektu", "Zadania", "Pomysły") }
        val boards = names.distinct().mapIndexed { index, name ->
            Board("board-$index", name, defaultDescription(name))
        }
        saveBoards(boards)
        return boards
    }

    fun ensureTemplates(current: List<Board>, templateNames: List<String>): List<Board> {
        val result = current.toMutableList()
        templateNames.distinct().filter { template -> result.none { it.name.equals(template, true) } }.forEach { name ->
            result += Board("board-${System.currentTimeMillis()}-${result.size}", name, defaultDescription(name))
        }
        if (result != current) saveBoards(result)
        return result
    }

    fun saveBoards(boards: List<Board>) {
        val array = JSONArray()
        boards.forEach { board ->
            array.put(JSONObject().apply {
                put("id", board.id)
                put("name", board.name)
                put("description", board.description)
            })
        }
        prefs.edit().putString("boards", array.toString()).apply()
    }

    fun selectedBoardId(): String? = prefs.getString("selected_board", null)

    fun saveSelectedBoard(id: String) {
        prefs.edit().putString("selected_board", id).apply()
    }

    fun loadSession(boardId: String): Session {
        val raw = prefs.getString("session_$boardId", null) ?: return Session(emptyList(), null)
        return runCatching {
            val json = JSONObject(raw)
            val messagesJson = json.optJSONArray("messages") ?: JSONArray()
            val messages = List(messagesJson.length()) { index ->
                val item = messagesJson.getJSONObject(index)
                ChatLine(item.optString("role"), item.optString("text"))
            }.filter { it.text.isNotBlank() }
            Session(messages, json.optString("previousResponseId").takeIf { it.isNotBlank() })
        }.getOrDefault(Session(emptyList(), null))
    }

    fun saveSession(boardId: String, messages: List<ChatLine>, previousResponseId: String?) {
        val array = JSONArray()
        messages.takeLast(150).forEach { line ->
            array.put(JSONObject().apply {
                put("role", line.role)
                put("text", line.text)
            })
        }
        val json = JSONObject().apply {
            put("messages", array)
            if (!previousResponseId.isNullOrBlank()) put("previousResponseId", previousResponseId)
        }
        prefs.edit().putString("session_$boardId", json.toString()).apply()
    }

    fun deleteSession(boardId: String) {
        prefs.edit().remove("session_$boardId").apply()
    }

    private fun defaultDescription(name: String): String = when (name.lowercase()) {
        "rozmowa" -> "Bieżąca rozmowa i kontekst"
        "vision" -> "Obrazy, screenshoty i interpretacja widoku"
        "research" -> "Analizy, źródła i linki"
        "pliki projektu" -> "Dokumenty i materiały"
        "zadania" -> "Rzeczy do wykonania"
        "pomysły" -> "Koncepcje i rozwój"
        else -> "Niezależna przestrzeń robocza"
    }
}
