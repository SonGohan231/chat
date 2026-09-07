package pl.somaskan.questgpt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.somaskan.questgpt.live.LiveConfig

@Composable
fun LiveEditScreen(
    config: LiveConfig,
    status: String,
    localOverride: Boolean,
    onApplyLocal: (LiveConfig) -> Unit,
    onUseRemote: () -> Unit,
    onRefresh: () -> Unit,
) {
    var title by remember(config) { mutableStateOf(config.title) }
    var subtitle by remember(config) { mutableStateOf(config.subtitle) }
    var actions by remember(config) { mutableStateOf(config.quickActions.joinToString("\n")) }
    var templates by remember(config) { mutableStateOf(config.boardTemplates.joinToString("\n")) }
    var refresh by remember(config) { mutableStateOf(config.refreshSeconds.toString()) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Live Edit", style = MaterialTheme.typography.headlineSmall)
                Text(if (localOverride) "Tryb: lokalna edycja APK" else "Tryb: synchronizacja z tego chatu / GitHub")
            }
            AssistChip(onClick = {}, label = { Text(status) })
        }

        OutlinedTextField(title, { title = it }, label = { Text("Tytuł") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(subtitle, { subtitle = it }, label = { Text("Podtytuł") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = actions,
                onValueChange = { actions = it },
                label = { Text("Szybkie akcje — jedna na linię") },
                modifier = Modifier.weight(1f),
                minLines = 4
            )
            OutlinedTextField(
                value = templates,
                onValueChange = { templates = it },
                label = { Text("Szablony plansz — jeden na linię") },
                modifier = Modifier.weight(1f),
                minLines = 4
            )
        }
        OutlinedTextField(
            value = refresh,
            onValueChange = { refresh = it.filter(Char::isDigit) },
            label = { Text("Odświeżanie zdalne (sekundy)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onApplyLocal(
                        LiveConfig(
                            title = title.trim().ifEmpty { "QuestGPT" },
                            subtitle = subtitle.trim(),
                            quickActions = actions.lines().map(String::trim).filter(String::isNotEmpty),
                            boardTemplates = templates.lines().map(String::trim).filter(String::isNotEmpty),
                            refreshSeconds = refresh.toLongOrNull()?.coerceIn(5, 3600) ?: 15,
                            updatedAt = "lokalna edycja w APK"
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text("Zastosuj lokalnie") }
            OutlinedButton(onClick = onRefresh, enabled = !localOverride, modifier = Modifier.weight(1f)) { Text("Pobierz z chatu") }
            if (localOverride) {
                OutlinedButton(onClick = onUseRemote, modifier = Modifier.weight(1f)) { Text("Wróć do synchronizacji") }
            }
        }
        Text("Zmiany w config/live.json na gałęzi main pojawiają się bez przebudowy APK. Edycja lokalna działa natychmiast na tym Queście i może być później zastąpiona synchronizacją z repozytorium.")
    }
}
