package pl.somaskan.questgpt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun VoiceScreen(state: String, onToggle: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Rozmowa głosowa", style = MaterialTheme.typography.headlineSmall)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state, style = MaterialTheme.typography.titleMedium)
                Text("Dwukierunkowe audio Realtime: mówisz naturalnie, a GPT odpowiada głosem. Możesz przerwać odpowiedź, zaczynając mówić.")
            }
        }
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.startsWith("Połączono")) "Zatrzymaj rozmowę" else "Uruchom mikrofon i GPT Live")
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Pliki", style = MaterialTheme.typography.headlineSmall)
                Text("Plik: $fileLabel")
            }
            Text("Folder: $folderLabel", style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(rename, { rename = it }, label = { Text("Nowa nazwa") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { if (rename.isNotBlank()) onRename(rename.trim()) }) { Text("Zmień nazwę") }
            OutlinedButton(onClick = onDelete) { Text("Usuń") }
        }
        OutlinedButton(onClick = onOpenFolder, modifier = Modifier.fillMaxWidth()) { Text("Wybierz folder roboczy") }
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
        Text("QuestGPT używa Storage Access Framework: odczyt, tworzenie, edycja, zmiana nazwy, usuwanie i pobieranie działają w lokalizacjach wskazanych przez użytkownika.")
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Aktualizacje", style = MaterialTheme.typography.headlineSmall)
        ElevatedCard(Modifier.fillMaxWidth()) { Text(status, Modifier.padding(16.dp)) }
        Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Sprawdź teraz") }
        if (!canInstall) OutlinedButton(onClick = onAllowInstalls, modifier = Modifier.fillMaxWidth()) { Text("Zezwól QuestGPT instalować APK") }
        Button(onClick = onInstall, enabled = canInstall && status.contains("Pobrano"), modifier = Modifier.fillMaxWidth()) { Text("Zainstaluj pobraną wersję") }
        Text("Zmiany kodu na main tworzą nowy APK. Horizon OS nadal pokazuje systemowe potwierdzenie instalacji — zwykła aplikacja nie może legalnie ominąć tego kroku.")
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ustawienia i uprawnienia", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(backendUrl, onBackendUrl, label = { Text("Backend HTTPS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        PermissionRow("Mikrofon", micGranted, onMicPermission)
        PermissionRow("Powiadomienia", notificationsGranted, onNotificationPermission)
        PermissionRow("Instalowanie aktualizacji APK", installGranted, onInstallPermission)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Text(
                "Zdjęcia i dokumenty są udostępniane przez systemowy picker, screenshot przez MediaProjection, a pliki przez Storage Access Framework. To maksymalny normalny zakres uprawnień bez roota i obchodzenia zabezpieczeń Horizon OS.",
                Modifier.padding(14.dp)
            )
        }
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
