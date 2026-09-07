package pl.somaskan.questgpt.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Composable
fun VisionScreen(
    imageDataUrl: String?,
    busy: Boolean,
    quickActions: List<String>,
    onPickImage: () -> Unit,
    onScreenshot: () -> Unit,
    onAnalyze: (String) -> Unit,
    onOpenSketch: () -> Unit,
) {
    val bitmap = remember(imageDataUrl) { imageDataUrl?.let(::decodeDataUrlBitmap) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Widzę", style = MaterialTheme.typography.headlineSmall)
        Text("Zdjęcie lub screenshot możesz od razu przeanalizować albo otworzyć w Szkicu i zaznaczyć interesujący fragment.")

        ElevatedCard(Modifier.fillMaxWidth().weight(1f)) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Ostatni obraz",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                )
            } else {
                Box(Modifier.fillMaxSize().padding(20.dp)) {
                    Text("Brak obrazu. Wybierz zdjęcie albo wykonaj screenshot widoku Questa.")
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickImage, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Zdjęcie") }
            Button(onClick = onScreenshot, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Screenshot") }
            OutlinedButton(onClick = onOpenSketch, enabled = imageDataUrl != null && !busy, modifier = Modifier.weight(1f)) { Text("Zaznacz") }
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            quickActions.forEach { action ->
                AssistChip(
                    onClick = { if (imageDataUrl != null) onAnalyze(action) },
                    enabled = imageDataUrl != null && !busy,
                    label = { Text(action) }
                )
            }
        }
    }
}

@Composable
fun SketchScreen(
    backgroundDataUrl: String?,
    busy: Boolean,
    onPickBackground: () -> Unit,
    onScreenshotBackground: () -> Unit,
    onSendAnnotated: (List<List<Offset>>, IntSize) -> Unit,
) {
    val background = remember(backgroundDataUrl) { backgroundDataUrl?.let(::decodeDataUrlBitmap) }
    val strokes = remember(backgroundDataUrl) { mutableStateListOf<List<Offset>>() }
    var activeStroke by remember(backgroundDataUrl) { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Szkic i zaznaczenia", style = MaterialTheme.typography.headlineSmall)
        Text("Rysuj kontrolerem lub dłonią po obrazie. Zaznaczenia zostaną scalone z obrazem i wysłane do analizy AI.")

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (background != null) {
                Image(
                    bitmap = background.asImageBitmap(),
                    contentDescription = "Tło szkicu",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(backgroundDataUrl) {
                        detectDragGestures(
                            onDragStart = { point -> activeStroke = listOf(point) },
                            onDrag = { change, _ -> activeStroke = activeStroke + change.position },
                            onDragEnd = {
                                if (activeStroke.isNotEmpty()) strokes += activeStroke
                                activeStroke = emptyList()
                            },
                            onDragCancel = { activeStroke = emptyList() }
                        )
                    }
            ) {
                val all = if (activeStroke.isEmpty()) strokes else strokes + listOf(activeStroke)
                all.forEach { stroke ->
                    for (index in 1 until stroke.size) {
                        drawLine(
                            color = Color(0xFFFF3B30),
                            start = stroke[index - 1],
                            end = stroke[index],
                            strokeWidth = 7f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickBackground, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Zdjęcie") }
            OutlinedButton(onClick = onScreenshotBackground, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Screenshot") }
            OutlinedButton(
                onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) },
                enabled = strokes.isNotEmpty() && !busy,
                modifier = Modifier.weight(1f)
            ) { Text("Cofnij") }
            OutlinedButton(onClick = { strokes.clear(); activeStroke = emptyList() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Wyczyść") }
        }
        Button(
            onClick = { onSendAnnotated(strokes.toList(), canvasSize) },
            enabled = !busy && canvasSize.width > 0 && canvasSize.height > 0 && (backgroundDataUrl != null || strokes.isNotEmpty()),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Wyślij zaznaczenie do GPT") }
    }
}

private fun decodeDataUrlBitmap(dataUrl: String): Bitmap? = runCatching {
    val payload = dataUrl.substringAfter(',', dataUrl)
    val bytes = Base64.decode(payload, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()
