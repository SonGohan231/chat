package pl.somaskan.questgpt.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.somaskan.questgpt.adb.AdbVisionMonitor
import pl.somaskan.questgpt.adb.QuestAgentRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AdbVisionStatusCard(compact: Boolean = false) {
    val scope = rememberCoroutineScope()
    var showPreview by remember { mutableStateOf(false) }
    val previewData = QuestAgentRuntime.lastPreviewDataUrl
    val previewBitmap = remember(previewData, showPreview) {
        if (!showPreview || previewData.isNullOrBlank()) null
        else runCatching {
            val bytes = Base64.decode(previewData.substringAfter(','), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val frameTime = if (QuestAgentRuntime.lastFrameAt > 0L) timeFormat.format(Date(QuestAgentRuntime.lastFrameAt)) else "—"

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!compact) {
            StartupConversationSettingsCard()
            AgentControlSettings()
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(if (compact) 12.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("GPT Vision + Agent", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(QuestAgentRuntime.visionStatus, style = MaterialTheme.typography.bodyMedium)
                        Text("Ostatnia klatka: $frameTime", style = MaterialTheme.typography.bodySmall)
                    }
                    if (QuestAgentRuntime.actionsThisTurn > 0) {
                        AssistChip(onClick = {}, label = { Text("Akcje: ${QuestAgentRuntime.actionsThisTurn}") })
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Switch(
                            checked = QuestAgentRuntime.autoVisionEnabled,
                            onCheckedChange = { AdbVisionMonitor.setAutoVisionEnabled(it) }
                        )
                        Column {
                            Text("Auto Vision", fontWeight = FontWeight.SemiBold)
                            if (!compact) Text("0,5–2 FPS, lokalnie; klatka trafia do GPT tylko gdy jest potrzebna", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Switch(
                            checked = QuestAgentRuntime.agentControlEnabled,
                            onCheckedChange = { AdbVisionMonitor.setAgentControlEnabled(it) }
                        )
                        Column {
                            Text("Sterowanie GPT", fontWeight = FontWeight.SemiBold)
                            if (!compact) Text("Zakres działania dodatkowo ogranicza wybrany poziom uprawnień", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                QuestAgentRuntime.currentActivity.takeIf { it.isNotBlank() }?.let {
                    Text("Aktywne okno: ${it.take(180)}", style = MaterialTheme.typography.bodySmall)
                }
                Text(QuestAgentRuntime.lastAction, style = MaterialTheme.typography.bodyMedium)
                QuestAgentRuntime.lastError?.let {
                    Text("Błąd: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { scope.launch { AdbVisionMonitor.captureNow() } },
                        modifier = Modifier.weight(1f)
                    ) { Text("Odśwież widok") }
                    OutlinedButton(
                        onClick = { showPreview = !showPreview },
                        enabled = previewData != null,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (showPreview) "Ukryj podgląd" else "Pokaż co widzi GPT") }
                }

                if (showPreview && previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap,
                        contentDescription = "Ostatnia klatka ADB widziana przez GPT",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = if (compact) 180.dp else 300.dp)
                    )
                }
            }
        }

        WorldVisionStatusCard(compact = compact)
    }
}
