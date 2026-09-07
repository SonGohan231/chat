package pl.somaskan.questgpt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import pl.somaskan.questgpt.VoiceAgentController
import pl.somaskan.questgpt.VoiceAgentRuntime

@Composable
fun VoiceScreen(state: String, openAIConfigured: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val backgroundActive = VoiceAgentRuntime.desiredRunning
    val effectiveState = if (backgroundActive) VoiceAgentRuntime.state else state

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("GPT Live", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (!openAIConfigured) "Wymaga konfiguracji OpenAI" else effectiveState,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (!openAIConfigured || VoiceAgentRuntime.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (openAIConfigured)
                        "Mikrofon łączy się bezpośrednio z OpenAI Realtime. Po schowaniu panelu rozmowę utrzymuje usługa QuestGPT."
                    else
                        "Najpierw przejdź do Ustawienia > OpenAI i zapisz własny klucz API.",
                    style = MaterialTheme.typography.bodyLarge
                )
                if (VoiceAgentRuntime.reconnectAttempt > 0 && backgroundActive) {
                    Text("Próba ponownego połączenia: ${VoiceAgentRuntime.reconnectAttempt}", style = MaterialTheme.typography.bodyMedium)
                }
                VoiceAgentRuntime.lastError?.takeIf { backgroundActive || !openAIConfigured }?.let {
                    Text("Błąd: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (backgroundActive && (VoiceAgentRuntime.lastUserTranscript.isNotBlank() || VoiceAgentRuntime.assistantTranscript.isNotBlank())) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ostatnia rozmowa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    VoiceAgentRuntime.lastUserTranscript.takeIf { it.isNotBlank() }?.let { Text("Ty: $it") }
                    VoiceAgentRuntime.assistantTranscript.takeIf { it.isNotBlank() }?.let { Text("GPT: $it") }
                }
            }
        }

        AdbVisionStatusCard(compact = false)
        Button(
            onClick = { if (backgroundActive) VoiceAgentController.stop(context) else onToggle() },
            enabled = openAIConfigured || backgroundActive,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(
                if (backgroundActive || effectiveState.startsWith("Połączono") || effectiveState.startsWith("Ponowne łączenie") || effectiveState.startsWith("Łączenie"))
                    "Zatrzymaj rozmowę"
                else "Uruchom GPT Live"
            )
        }
        Spacer(Modifier.height(18.dp))
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
    openAIConfigured: Boolean,
    textModel: String,
    realtimeModel: String,
    onSaveOpenAI: (String, String, String) -> Unit,
    onClearOpenAI: () -> Unit,
    micGranted: Boolean,
    notificationsGranted: Boolean,
    installGranted: Boolean,
    onMicPermission: () -> Unit,
    onNotificationPermission: () -> Unit,
    onInstallPermission: () -> Unit,
) {
    var section by remember { mutableStateOf(0) }
    var apiKey by remember { mutableStateOf("") }
    var textModelDraft by remember(textModel) { mutableStateOf(textModel) }
    var realtimeModelDraft by remember(realtimeModel) { mutableStateOf(realtimeModel) }

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
                label = { Text("ADB + Agent") }
            )
        }

        if (section == 1) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AdbVisionStatusCard(compact = false)
                WirelessAdbScreen(embedded = true)
                DiagnosticsCard()
                Spacer(Modifier.height(18.dp))
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("OpenAI — połączenie bezpośrednie", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            SuggestionChip(
                                onClick = {},
                                label = { Text(if (openAIConfigured) "Gotowe" else "Brak klucza") },
                            )
                        }
                        Text(
                            "QuestGPT nie używa AppDeploy ani zewnętrznego backendu. W prywatnej instalacji APK Twój klucz API jest szyfrowany kluczem Android Keystore i używany bezpośrednio do api.openai.com.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(if (openAIConfigured) "Nowy klucz API (zostaw puste, aby nie zmieniać)" else "Klucz OpenAI API") },
                            placeholder = { Text("sk-…") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = textModelDraft,
                            onValueChange = { textModelDraft = it },
                            label = { Text("Model tekstowy") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = realtimeModelDraft,
                            onValueChange = { realtimeModelDraft = it },
                            label = { Text("Model głosowy Realtime") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    onSaveOpenAI(apiKey.trim(), textModelDraft.trim(), realtimeModelDraft.trim())
                                    apiKey = ""
                                },
                                enabled = (openAIConfigured || apiKey.isNotBlank()) && textModelDraft.isNotBlank() && realtimeModelDraft.isNotBlank(),
                                modifier = Modifier.weight(1f),
                            ) { Text("Zapisz") }
                            if (openAIConfigured) {
                                OutlinedButton(onClick = { onClearOpenAI(); apiKey = "" }, modifier = Modifier.weight(1f)) {
                                    Text("Usuń klucz")
                                }
                            }
                        }
                        Text(
                            "Klucz nie jest częścią APK ani repozytorium. Jest jednak obecny na urządzeniu, dlatego ten tryb jest przeznaczony do prywatnego sideloadu, nie publicznej dystrybucji. Subskrypcja ChatGPT i rozliczenia OpenAI API są oddzielne.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                PermissionRow("Mikrofon", micGranted, onMicPermission)
                PermissionRow("Powiadomienia", notificationsGranted, onNotificationPermission)
                PermissionRow("Instalowanie aktualizacji APK", installGranted, onInstallPermission)
                DiagnosticsCard()

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Text(
                        "ADB Agent łączy obraz ekranu, drzewo UIAutomator i ograniczone sterowanie. Diagnostyka wyżej pokazuje oddzielnie, czy działa OpenAI, mikrofon, ADB shell, screenshot i UIAutomator.",
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
