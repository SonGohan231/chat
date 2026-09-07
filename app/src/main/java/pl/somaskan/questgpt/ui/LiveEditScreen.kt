package pl.somaskan.questgpt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.somaskan.questgpt.live.LiveConfig

@Composable
fun LiveEditScreen(config: LiveConfig, status: String, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Live Edit", style = MaterialTheme.typography.headlineMedium)
        Text("Treści tego ekranu i szybkie akcje mogą być zmieniane z repozytorium bez przebudowy APK. Aplikacja odświeża config automatycznie.")
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(config.title, style = MaterialTheme.typography.titleLarge)
                Text(config.subtitle)
                Text("Status: $status")
                Text("Ostatnia zmiana: ${config.updatedAt}")
                Text("Odświeżanie: co ${config.refreshSeconds} s")
            }
        }
        Text("Szybkie akcje", style = MaterialTheme.typography.titleMedium)
        config.quickActions.forEach { action -> AssistChip(onClick = {}, label = { Text(action) }) }
        Button(onClick = onRefresh) { Text("Odśwież teraz") }
    }
}
