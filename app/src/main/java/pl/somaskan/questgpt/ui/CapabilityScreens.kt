package pl.somaskan.questgpt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.somaskan.questgpt.QuestEndpoints

@Composable
fun VoiceScreen(state: String, onToggle: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Rozmowa głosowa", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Dwukierunkowe audio OpenAI Realtime. QuestGPT pobiera krótki token z bezpiecznego backendu i łączy się bezpośrednio z OpenAI.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
            Text(if (state.startsWith("Połączono")) "Zatrzymaj rozmowę" else "Uruchom GPT Live")
        }
    }
}

@Composable
fun FilesScreen(
    fileLabel: String,
    text: String,
    onTextChange: (String) -> Unit,
    folderLabel: String,
    downloadUrl: String,
    onDownloadUrlChange: (String) -> Unit,
    onOpenFile: () -> Unit,
    onCreateFile: () -> Unit,
    onSave: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onOpenFolder: () -> Unit,
    onDownload: () -> Unit,
) {
    var rename by remember(fileLabel) { mutableStateOf(fileLabel.substringAfterLast(':').ifBlank { "dokument.txt" }) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Pliki", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Plik: $fileLabel", style = MaterialTheme.typography.bodyLarge)
            }
            Text("Folder: $folderLabel", style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onOpenFile, modifier = Modifier.weight(1f).heightIn(min = 50.dp)) { Text("Otwórz") }
            Button(onClick = onCreateFile, modifier = Modifier.weight(1f).heightIn(min = 50.dp)) { Text("Nowy") }
            Button(onClick = onSave, modifier = Modifier.weight(1f).heightIn(min = 50.dp)) { Text("Zapisz") }
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("Edytor tekstu") },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(rename, { rename = it }, label = { Text("Nowa nazwa") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { if (rename.isNotBlank()) onRename(rename.trim()) }) { Text("Zmień nazwę") }
            OutlinedButton(onClick = onDelete) { Text("Usuń") }
        }
        OutlinedButton(onClick = onOpenFolder, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Wybierz folder roboczy") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = downloadUrl,
                onValueChange = onDownloadUrlChange,
                label = { Text("URL pliku do pobrania") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onDownload, enabled = downloadUrl.isNotBlank()) { Text("Pobierz") }
        }
    }
}

@Composable
fun UpdatesScreen(
    status: String,
    canInstall: Boolean,
    onAllowInstalls: () -> Unit,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Aktualizacje", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        ElevatedCard(Modifier.fillMaxWidth()) { Text(status, Modifier.padding(18.dp), style = MaterialTheme.typography.bodyLarge) }
        Button(onClick = onCheck, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Sprawdź teraz") }
        if (!canInstall) OutlinedButton(onClick = onAllowInstalls, modifier = Modifier.fillMaxWidth()) { Text("Zezwól QuestGPT instalować APK") }
        Button(onClick = onInstall, enabled = canInstall && status.contains("Pobrano"), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Zainstaluj pobraną wersję") }
    }
}

@Composable
fun SettingsScreen(
    backendUrl: String,
    onBackendUrl: (String) -> Unit,
    micGranted: Boolean,
    notificationsGranted: Boolean,
    installGranted: Boolean,
    onMicPermission: () -> Unit,
    onNotificationPermission: () -> Unit,
    onInstallPermission: () -> Unit,
) {
    var section by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ustawienia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = section == 0,
                onClick = { section = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                label = { Text("OpenAI i uprawnienia") }
            )
            SegmentedButton(
                selected = section == 1,
                onClick = { section = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                label = { Text("ADB Wireless") }
            )
        }

        if (section == 1) {
            WirelessAdbScreen()
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("OpenAI API", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "QuestGPT korzysta z bezpiecznego publicznego backendu HTTPS. Główny klucz OpenAI nie jest zapisany w APK.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "Aktywny backend: ${QuestEndpoints.resolveBackend(backendUrl)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (QuestEndpoints.resolveBackend(backendUrl) != QuestEndpoints.PUBLIC_BACKEND) {
                            FilledTonalButton(onClick = { onBackendUrl(QuestEndpoints.PUBLIC_BACKEND) }) {
                                Text("Przywróć bezpieczne połączenie QuestGPT")
                            }
                        }
                        Text(
                            "Uwaga: subskrypcja ChatGPT i OpenAI API są oddzielnymi usługami. Logowanie do ChatGPT nie przekazuje aplikacji klucza API.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                PermissionRow("Mikrofon", micGranted, onMicPermission)
                PermissionRow("Powiadomienia", notificationsGranted, onNotificationPermission)
                PermissionRow("Instalowanie aktualizacji APK", installGranted, onInstallPermission)

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Zdjęcia i dokumenty są udostępniane przez systemowy picker, screenshot przez MediaProjection, a pliki przez Storage Access Framework. ADB Wireless jest dostępne w drugiej zakładce.",
                        Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun PermissionRow(name: String, granted: Boolean, onRequest: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("$name: ${if (granted) "OK" else "brak"}", style = MaterialTheme.typography.bodyLarge)
            if (!granted) TextButton(onClick = onRequest) { Text("Włącz") }
        }
    }
}
