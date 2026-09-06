package pl.somaskan.questgpt.data

data class Board(
    val id: String,
    val name: String,
    val description: String = "",
    val notes: MutableList<String> = mutableListOf(),
)
