package pl.somaskan.questgpt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun VoiceScreen(state: String, onToggle: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Rozmowa głosowa", style = MaterialTheme.typography.headlineSmall)
        Text(state)
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.startsWith("Połączono")) "Zatrzymaj rozmowę" else "Uruchom mikrofon i GPT Live")
        }
        Text("Realtime działa niezależnie od czatu tekstowego. Mikrofon jest uruchamiany tylko po Twojej zgodzie.")
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
    onOpenFolder: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Pliki", style = MaterialTheme.typography.headlineSmall)
        Text("Plik: $fileLabel")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenFile, modifier = Modifier.weight(1f)) { Text("Otwórz") }
            Button(onClick = onCreateFile, modifier = Modifier.weight(1f)) { Text("Nowy") }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Zapisz") }
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("Edytor tekstu") },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        OutlinedButton(onClick = onOpenFolder, modifier = Modifier.fillMaxWidth()) { Text("Folder roboczy: $folderLabel") }
        OutlinedTextField(
            value = downloadUrl,
            onValueChange = onDownloadUrlChange,
            label = { Text("URL pliku do pobrania") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onDownload, enabled = downloadUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Pobierz URL do pliku") }
        Text("Dostęp do plików i folderów jest trwały tylko dla lokalizacji, które wskażesz w systemowym oknie Androida/Horizon OS.")
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Aktualizacje", style = MaterialTheme.typography.headlineSmall)
        Text(status)
        Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Sprawdź teraz") }
        if (!canInstall) OutlinedButton(onClick = onAllowInstalls, modifier = Modifier.fillMaxWidth()) { Text("Zezwól QuestGPT instalować aktualizacje") }
        Button(onClick = onInstall, enabled = canInstall && status.contains("Pobrano"), modifier = Modifier.fillMaxWidth()) { Text("Zainstaluj pobraną wersję") }
        Text("Nowy APK jest budowany i publikowany po każdej udanej zmianie na gałęzi main. Aplikacja sprawdza wersję przy starcie i może ją automatycznie pobrać. Samo zastąpienie APK wymaga systemowego potwierdzenia instalatora.")
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ustawienia i uprawnienia", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(backendUrl, onBackendUrl, label = { Text("Backend URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        PermissionRow("Mikrofon", micGranted, onMicPermission)
        PermissionRow("Powiadomienia", notificationsGranted, onNotificationPermission)
        PermissionRow("Instalowanie aktualizacji APK", installGranted, onInstallPermission)
        Text("Zdjęcia, dokumenty i foldery są przyznawane przez systemowy picker, a screenshot przez MediaProjection. Dzięki temu aplikacja ma realny zapis/odczyt/tworzenie w wybranych lokalizacjach bez obchodzenia zabezpieczeń Horizon OS.")
    }
}

@Composable
private fun PermissionRow(name: String, granted: Boolean, onRequest: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$name: ${if (granted) "OK" else "brak"}")
            if (!granted) TextButton(onClick = onRequest) { Text("Włącz") }
        }
    }
}
