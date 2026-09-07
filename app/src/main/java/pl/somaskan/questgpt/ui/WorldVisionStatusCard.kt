package pl.somaskan.questgpt.ui

import android.Manifest
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.somaskan.questgpt.PassthroughCameraCapture
import pl.somaskan.questgpt.WorldVisionManager
import pl.somaskan.questgpt.WorldVisionRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WorldVisionStatusCard(compact: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val camera = remember { PassthroughCameraCapture(context.applicationContext) }
    var permissionGranted by remember { mutableStateOf(camera.hasPermission()) }
    var showPreview by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionGranted = camera.hasPermission()
        if (permissionGranted) WorldVisionManager.setEnabled(context, true)
        else {
            WorldVisionRuntime.lastError = "Nie przyznano CAMERA i HEADSET_CAMERA."
            WorldVisionRuntime.status = "World Vision: brak uprawnień"
        }
    }

    val requestPermission = {
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, PassthroughCameraCapture.HEADSET_CAMERA_PERMISSION))
    }

    LaunchedEffect(Unit) {
        WorldVisionManager.refreshRuntime(context)
        permissionGranted = camera.hasPermission()
    }

    val preview = WorldVisionRuntime.lastPreviewDataUrl
    val previewBitmap = remember(preview, showPreview) {
        if (!showPreview || preview.isNullOrBlank()) null else runCatching {
            val bytes = Base64.decode(preview.substringAfter(','), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    val format = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val captured = if (WorldVisionRuntime.lastFrameAt > 0L) format.format(Date(WorldVisionRuntime.lastFrameAt)) else "—"

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (compact) 12.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("World Vision • kamery passthrough", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Quest 3/3S może przekazać GPT obraz fizycznego świata z przednich kamer RGB. Obraz jest pobierany tylko, gdy World Vision jest włączone i aplikacja ma zgodę na kamerę.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(WorldVisionRuntime.status, style = MaterialTheme.typography.bodyMedium)
            Text("Ostatnia klatka świata: $captured", style = MaterialTheme.typography.bodySmall)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(
                    checked = WorldVisionRuntime.enabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !permissionGranted) requestPermission()
                        else WorldVisionManager.setEnabled(context, enabled)
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text("Dołączaj świat do GPT", fontWeight = FontWeight.SemiBold)
                    if (!compact) Text("Przy każdej obserwacji agenta GPT dostaje ekran Questa oraz świeżą klatkę fizycznego świata.", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (!permissionGranted) {
                FilledTonalButton(onClick = requestPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("Zezwól na kamerę passthrough")
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                runCatching { WorldVisionManager.captureNow(context) }
                                    .onSuccess { showPreview = true }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Zrób klatkę świata") }
                    OutlinedButton(
                        onClick = { showPreview = !showPreview },
                        enabled = preview != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (showPreview) "Ukryj" else "Pokaż") }
                }
            }

            WorldVisionRuntime.lastError?.let {
                Text("Błąd: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (showPreview && previewBitmap != null) {
                Image(
                    bitmap = previewBitmap,
                    contentDescription = "Obraz z kamery passthrough widziany przez GPT",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = if (compact) 180.dp else 300.dp),
                )
            }
        }
    }
}
