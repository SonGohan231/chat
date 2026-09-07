package pl.somaskan.questgpt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.somaskan.questgpt.AgentServiceController
import pl.somaskan.questgpt.adb.AgentAccessPolicy
import pl.somaskan.questgpt.adb.AgentConfirmationCenter
import pl.somaskan.questgpt.adb.AgentPermissionLevel
import pl.somaskan.questgpt.adb.QuestAgentRuntime

@Composable
fun AgentControlSettings() {
    val context = LocalContext.current
    var serviceEnabled by remember { mutableStateOf(AgentServiceController.isEnabled(context)) }
    val level = QuestAgentRuntime.permissionLevel

    LaunchedEffect(Unit) { AgentAccessPolicy.refreshRuntime() }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Agent Service", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (QuestAgentRuntime.serviceRunning) "Aktywny w tle" else "Usługa nie działa",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = serviceEnabled,
                    onCheckedChange = {
                        serviceEnabled = it
                        AgentServiceController.setEnabled(context, it)
                    },
                )
            }
            Text(
                "Gdy jest włączony, QuestGPT utrzymuje ADB Vision jako foreground service także wtedy, gdy panel nie jest na pierwszym planie. Po restarcie Questa usługa uruchamia się ponownie, jeśli pozostaje włączona.",
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()
            Text("Poziom uprawnień GPT", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AgentPermissionLevel.entries.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = level == item,
                        onClick = { AgentAccessPolicy.set(item) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = AgentPermissionLevel.entries.size),
                        label = { Text(item.label) },
                    )
                }
            }
            Text(
                when (level) {
                    AgentPermissionLevel.OBSERVE -> "Tylko patrz: GPT widzi ekran i UI hierarchy, ale nie może klikać ani zmieniać systemu."
                    AgentPermissionLevel.INTERACT -> "Interakcje: GPT może klikać, przewijać, pisać, otwierać aplikacje/URL, ustawienia i sterować multimediami."
                    AgentPermissionLevel.SYSTEM -> "System: dodatkowo głośność, jasność, Wi-Fi, Bluetooth oraz instalacja/usuwanie APK. Operacje wrażliwe wymagają osobnego potwierdzenia."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Potwierdzenia: ${QuestAgentRuntime.confirmationStatus}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun AgentConfirmationDialog() {
    val pending = AgentConfirmationCenter.pending ?: return
    AlertDialog(
        onDismissRequest = { AgentConfirmationCenter.deny() },
        title = { Text(pending.title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pending.details)
                Text(
                    "Ta operacja została zatrzymana przed wykonaniem. Zostanie wykonana tylko po Twoim zatwierdzeniu.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = { AgentConfirmationCenter.approve() }) { Text("Zezwól") }
        },
        dismissButton = {
            OutlinedButton(onClick = { AgentConfirmationCenter.deny() }) { Text("Odrzuć") }
        },
    )
}
