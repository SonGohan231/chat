package pl.somaskan.questgpt.navigation

enum class AppScreen(val title: String, val short: String) {
    CHAT("Chat", "C"),
    VOICE("Głos", "G"),
    VISION("Widzę", "V"),
    SKETCH("Szkic", "S"),
    FILES("Pliki", "P"),
    BOARDS("Plansze", "B"),
    ADB("ADB Wi‑Fi", "D"),
    LIVE("Live Edit", "L"),
    UPDATES("Aktualizacje", "A"),
    SETTINGS("Ustawienia", "U")
}
