package pl.somaskan.questgpt.live

data class LiveConfig(
    val title: String = "QuestGPT",
    val subtitle: String = "Asystent Meta Quest 3",
    val quickActions: List<String> = listOf("Co widzisz?", "Wyjaśnij", "Podsumuj", "Co dalej?"),
    val boardTemplates: List<String> = listOf("Rozmowa", "Research", "Pliki projektu", "Zadania", "Pomysły"),
    val refreshSeconds: Long = 15,
    val updatedAt: String = "lokalna konfiguracja"
)
