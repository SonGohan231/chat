package pl.somaskan.questgpt.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.somaskan.questgpt.adb.AdbVisionMonitor
import pl.somaskan.questgpt.adb.WirelessAdbController

@Composable
fun WirelessAdbScreen(embedded: Boolean = false) {
    val context = LocalContext.current
    val controller = remember { WirelessAdbController(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val rootScroll = rememberScrollState()

    var host by remember { mutableStateOf(controller.savedHost()) }
    var pairingPort by remember { mutableStateOf(controller.savedPairingPort()) }
    var pairingCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf(controller.savedConnectPort()) }
    var command by remember { mutableStateOf("getprop ro.product.model") }
    var output by remember { mutableStateOf("Tutaj pojawi się wynik polecenia ADB.") }
    var status by remember { mutableStateOf("Sprawdzanie ADB...") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val connected = withContext(Dispatchers.IO) { controller.isConnected() }
        status = if (connected) "Połączono z ADB" else "Niepołączony"
        if (connected) AdbVisionMonitor.captureNow()
    }

    val rootModifier = if (embedded) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.fillMaxSize().verticalScroll(rootScroll)
    }

    Column(rootModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("ADB Wireless", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Kanał obrazu, drzewa UI i bezpiecznego sterowania GPT — bez komputera i kabla.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            AssistChip(onClick = {}, label = { Text(if (busy) "Pracuję..." else status) })
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. Włącz Wireless Debugging", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "W ustawieniach programistycznych Questa włącz Debugowanie bezprzewodowe i wybierz parowanie kodem. " +
                        "Okna parowania nie zamykaj, dopóki QuestGPT nie potwierdzi sparowania."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.onFailure {
                                status = "Quest nie udostępnił bezpośredniego skrótu do Opcji programistycznych"
                            }
                        }
                    ) { Text("Otwórz opcje programistyczne") }

                    OutlinedButton(
                        onClick = {
                            if (busy) return@OutlinedButton
                            busy = true
                            status = "Szukam portu parowania..."
                            scope.launch {
                                runCatching { controller.discoverPairingEndpoint() }
                                    .onSuccess {
                                        host = it.host
                                        pairingPort = it.port.toString()
                                        status = "Wykryto port parowania ${it.port}"
                                    }
                                    .onFailure { status = it.message ?: "Nie znaleziono portu parowania" }
                                busy = false
                            }
                        },
                        enabled = !busy
                    ) { Text("Wykryj port parowania") }
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("2. Sparuj QuestGPT", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("IP Questa") },
                    supportingText = { Text("Zwykle lokalny adres Wi‑Fi, np. 192.168.x.x") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = pairingPort,
                        onValueChange = { pairingPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port parowania") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("Kod 6 cyfr") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Port parowania jest tymczasowy i nie jest tym samym portem, którego później używa zwykłe połączenie ADB.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        status = "Parowanie..."
                        scope.launch {
                            runCatching {
                                val port = pairingPort.toIntOrNull() ?: error("Podaj port parowania")
                                check(controller.pair(host, port, pairingCode)) { "Parowanie nie powiodło się" }
                                status = "Sparowano — szukam portu ADB..."
                                check(controller.autoConnect()) { "Sparowano, ale nie udało się automatycznie połączyć" }
                                AdbVisionMonitor.captureNow()
                                "Połączono z ADB • Vision aktywne"
                            }.onSuccess { status = it }
                                .onFailure { status = it.message ?: "Błąd parowania ADB" }
                            busy = false
                        }
                    },
                    enabled = !busy && host.isNotBlank() && pairingPort.isNotBlank() && pairingCode.length == 6,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sparuj i uruchom Vision") }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("3. Połączenie", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true
                            status = "Wykrywam ADB..."
                            scope.launch {
                                runCatching {
                                    val connected = controller.autoConnect()
                                    if (connected) AdbVisionMonitor.captureNow()
                                    connected
                                }.onSuccess { status = if (it) "Połączono • GPT Vision aktywne" else "Nie udało się połączyć" }
                                    .onFailure { status = it.message ?: "Błąd połączenia" }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Auto Connect") }

                    OutlinedButton(
                        onClick = {
                            if (busy) return@OutlinedButton
                            busy = true
                            scope.launch {
                                runCatching { controller.disconnect() }
                                    .onSuccess { status = "Rozłączono" }
                                    .onFailure { status = it.message ?: "Błąd rozłączania" }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Rozłącz") }
                }

                HorizontalDivider()
                Text("Ręczny fallback IP:port", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it.trim() },
                        label = { Text("Host") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = connectPort,
                        onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port ADB") },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true
                            status = "Łączenie ręczne..."
                            scope.launch {
                                runCatching {
                                    val port = connectPort.toIntOrNull() ?: error("Podaj port ADB")
                                    val connected = controller.directConnect(host, port)
                                    if (connected) AdbVisionMonitor.captureNow()
                                    connected
                                }.onSuccess { status = if (it) "Połączono • GPT Vision aktywne" else "Połączenie odrzucone" }
                                    .onFailure { status = it.message ?: "Błąd połączenia" }
                                busy = false
                            }
                        },
                        enabled = !busy && host.isNotBlank() && connectPort.isNotBlank()
                    ) { Text("Połącz") }
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ADB Shell — diagnostyka ręczna", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("GPT Agent nie dostaje dostępu do dowolnego shella. Korzysta wyłącznie z ograniczonych narzędzi UI.", style = MaterialTheme.typography.bodySmall)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "id",
                        "getprop ro.product.model",
                        "getprop ro.build.version.release",
                        "pm list packages -3",
                        "dumpsys window | head -n 40"
                    ).forEach { quick ->
                        AssistChip(onClick = { command = quick }, label = { Text(quick) })
                    }
                }

                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Polecenie shell") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        status = "Wykonuję polecenie..."
                        scope.launch {
                            runCatching { controller.runCommand(command) }
                                .onSuccess {
                                    output = it
                                    status = "ADB połączone • polecenie zakończone"
                                }
                                .onFailure {
                                    output = "Błąd: ${it.message}"
                                    status = it.message ?: "Błąd ADB Shell"
                                }
                            busy = false
                        }
                    },
                    enabled = !busy && command.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Uruchom przez ADB") }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp)
                ) {
                    SelectionContainer {
                        Text(text = output, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }

        Text(
            "Klucz parowania ADB jest przechowywany wyłącznie w prywatnych danych QuestGPT. Auto Vision przechwytuje obraz lokalnie adaptacyjnie (0,5–2 FPS); do OpenAI trafia klatka wtedy, gdy jest potrzebna do odpowiedzi lub aktywnej rozmowy Live.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 18.dp)
        )
    }
}
