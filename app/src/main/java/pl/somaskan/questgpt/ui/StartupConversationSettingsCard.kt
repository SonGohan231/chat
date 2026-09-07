package pl.somaskan.questgpt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.somaskan.questgpt.StartupConversationGuide

@Composable
fun StartupConversationSettingsCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(StartupConversationGuide.isEnabled(context)) }
    var fullGuidePending by remember { mutableStateOf(StartupConversationGuide.needsFullGuide(context)) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Rozmowa po uruchomieniu", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "QuestGPT sam sprawdza połączenia i zaczyna rozmowę. Jeśli mikrofon jest gotowy, GPT odzywa się pierwszy przez Realtime.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        StartupConversationGuide.setEnabled(context, it)
                    },
                )
            }

            Text(
                if (fullGuidePending) {
                    "Przy następnym uruchomieniu zostanie pokazany pełny przewodnik funkcji i podłączeń."
                } else {
                    "Pełny przewodnik został już pokazany. Kolejne uruchomienia zaczynają się krótkim raportem gotowości."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedButton(
                onClick = {
                    StartupConversationGuide.resetFullGuide(context)
                    fullGuidePending = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Powtórz pełny przewodnik przy następnym uruchomieniu")
            }
        }
    }
}
