package pl.somaskan.questgpt.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import pl.somaskan.questgpt.data.Board
import pl.somaskan.questgpt.live.LiveUiConfig
import pl.somaskan.questgpt.live.LiveUiRuntime
import pl.somaskan.questgpt.navigation.AppScreen
import kotlin.math.roundToInt

@Composable
fun MultiBoardShell(current: AppScreen, onScreen: (AppScreen) -> Unit, body: @Composable () -> Unit) {
    val runtimeConfig = LiveUiRuntime.config
    val ui = runtimeConfig.ui
    val workspace = rememberWorkspaceState()
    val baseDensity = LocalDensity.current
    val effectiveFontScale = (baseDensity.fontScale * ui.fontScale * workspace.textScale).coerceIn(0.72f, 1.65f)

    CompositionLocalProvider(
        LocalDensity provides Density(baseDensity.density, effectiveFontScale)
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ui.backgroundDim))
        ) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

            if (ui.floatingPanels && workspace.floating) {
                FloatingWorkspace(
                    current = current,
                    onScreen = onScreen,
                    body = body,
                    ui = ui,
                    workspace = workspace,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    maxWidthDp = maxWidth,
                    maxHeightDp = maxHeight,
                )
            } else {
                DockedWorkspace(
                    current = current,
                    onScreen = onScreen,
                    body = body,
                    ui = ui,
                    workspace = workspace,
                )
            }

            if (workspace.settingsOpen) {
                WorkspaceSettingsPanel(
                    workspace = workspace,
                    ui = ui,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (workspace.settingsX * widthPx).roundToInt(),
                                (workspace.settingsY * heightPx).roundToInt()
                            )
                        }
                        .align(Alignment.TopStart)
                )
            }
        }
    }
}

@Composable
private fun FloatingWorkspace(
    current: AppScreen,
    onScreen: (AppScreen) -> Unit,
    body: @Composable () -> Unit,
    ui: LiveUiConfig,
    workspace: WorkspaceState,
    widthPx: Float,
    heightPx: Float,
    maxWidthDp: androidx.compose.ui.unit.Dp,
    maxHeightDp: androidx.compose.ui.unit.Dp,
) {
    val shape = RoundedCornerShape(ui.panelCornerDp.dp)
    val panelColor = MaterialTheme.colorScheme.surface.copy(
        alpha = (ui.panelOpacity * workspace.opacityScale).coerceIn(0.62f, 1f)
    )
    val spacing = ui.panelSpacingDp.dp

    if (workspace.showNavigation) {
        ElevatedCard(
            shape = shape,
            colors = CardDefaults.elevatedCardColors(containerColor = panelColor),
            modifier = Modifier
                .offset {
                    IntOffset(
                        (workspace.navX * widthPx).roundToInt(),
                        (workspace.navY * heightPx).roundToInt()
                    )
                }
                .width((ui.navWidthDp * workspace.navScale).coerceIn(88f, 210f).dp)
                .heightIn(max = maxHeightDp * 0.92f)
        ) {
            PanelDragHeader(
                title = "MENU",
                locked = workspace.locked,
                onSettings = { workspace.settingsOpen = true },
                onDrag = { dx, dy ->
                    if (!workspace.locked) {
                        workspace.navX = clampPanelPosition(workspace.navX + dx / widthPx, 0.08f)
                        workspace.navY = clampPanelPosition(workspace.navY + dy / heightPx, 0.08f)
                    }
                },
                onDragEnd = workspace::save
            )
            HorizontalDivider()
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                AppScreen.entries.forEach { screen ->
                    NavigationRailItem(
                        selected = current == screen,
                        onClick = { onScreen(screen) },
                        icon = {
                            Text(
                                screen.short,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        label = if (ui.navigationLabels) {
                            { Text(screen.title, maxLines = 1) }
                        } else null,
                        alwaysShowLabel = ui.navigationLabels
                    )
                }
            }
        }
    }

    ElevatedCard(
        shape = shape,
        colors = CardDefaults.elevatedCardColors(containerColor = panelColor),
        modifier = Modifier
            .offset {
                IntOffset(
                    (workspace.contentX * widthPx).roundToInt(),
                    (workspace.contentY * heightPx).roundToInt()
                )
            }
            .width(maxWidthDp * workspace.contentWidth)
            .height(maxHeightDp * workspace.contentHeight)
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (ui.showPanelHeader) {
                    PanelDragHeader(
                        title = current.title.uppercase(),
                        locked = workspace.locked,
                        onSettings = { workspace.settingsOpen = true },
                        onDrag = { dx, dy ->
                            if (!workspace.locked) {
                                workspace.contentX = clampPanelPosition(
                                    workspace.contentX + dx / widthPx,
                                    0.08f
                                )
                                workspace.contentY = clampPanelPosition(
                                    workspace.contentY + dy / heightPx,
                                    0.08f
                                )
                            }
                        },
                        onDragEnd = workspace::save
                    )
                    HorizontalDivider()
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(ui.contentPaddingDp.dp)
                ) {
                    body()
                }
            }

            if (!workspace.locked) {
                ResizeHandle(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    onResize = { dx, dy ->
                        workspace.contentWidth =
                            (workspace.contentWidth + dx / widthPx).coerceIn(0.48f, 0.98f)
                        workspace.contentHeight =
                            (workspace.contentHeight + dy / heightPx).coerceIn(0.48f, 0.98f)
                    },
                    onResizeEnd = workspace::save
                )
            }
        }
    }

    if (!workspace.showNavigation) {
        FilledTonalButton(
            onClick = { workspace.settingsOpen = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.padding(spacing)
        ) {
            Text("Układ")
        }
    }
}

@Composable
private fun DockedWorkspace(
    current: AppScreen,
    onScreen: (AppScreen) -> Unit,
    body: @Composable () -> Unit,
    ui: LiveUiConfig,
    workspace: WorkspaceState,
) {
    val shape = RoundedCornerShape(ui.panelCornerDp.dp)
    val panelColor = MaterialTheme.colorScheme.surface.copy(
        alpha = (ui.panelOpacity * workspace.opacityScale).coerceIn(0.62f, 1f)
    )

    Row(
        Modifier
            .fillMaxSize()
            .padding(ui.panelSpacingDp.dp),
        horizontalArrangement = Arrangement.spacedBy(ui.panelSpacingDp.dp)
    ) {
        if (workspace.showNavigation) {
            ElevatedCard(
                shape = shape,
                colors = CardDefaults.elevatedCardColors(containerColor = panelColor),
                modifier = Modifier
                    .width((ui.navWidthDp * workspace.navScale).coerceIn(88f, 210f).dp)
                    .fillMaxHeight()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("MENU", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { workspace.settingsOpen = true }) { Text("Układ") }
                }
                HorizontalDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AppScreen.entries.forEach { screen ->
                        NavigationRailItem(
                            selected = current == screen,
                            onClick = { onScreen(screen) },
                            icon = { Text(screen.short, fontWeight = FontWeight.Bold) },
                            label = if (ui.navigationLabels) {
                                { Text(screen.title, maxLines = 1) }
                            } else null,
                            alwaysShowLabel = ui.navigationLabels
                        )
                    }
                }
            }
        }

        ElevatedCard(
            shape = shape,
            colors = CardDefaults.elevatedCardColors(containerColor = panelColor),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(current.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { workspace.settingsOpen = true }) { Text("Układ") }
            }
            HorizontalDivider()
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(ui.contentPaddingDp.dp)
            ) {
                body()
            }
        }
    }
}

@Composable
private fun PanelDragHeader(
    title: String,
    locked: Boolean,
    onSettings: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .pointerInput(locked) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd
                ) { _, amount ->
                    onDrag(amount.x, amount.y)
                }
            }
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("⋮⋮", style = MaterialTheme.typography.titleMedium)
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        if (locked) {
            AssistChip(onClick = {}, label = { Text("Zablok.") })
        }
        TextButton(onClick = onSettings) { Text("Układ") }
    }
}

@Composable
private fun ResizeHandle(
    modifier: Modifier = Modifier,
    onResize: (Float, Float) -> Unit,
    onResizeEnd: () -> Unit,
) {
    Surface(
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 14.dp),
        modifier = modifier
            .size(42.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onResizeEnd,
                    onDragCancel = onResizeEnd
                ) { _, amount ->
                    onResize(amount.x, amount.y)
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("↘", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun WorkspaceSettingsPanel(
    workspace: WorkspaceState,
    ui: LiveUiConfig,
    widthPx: Float,
    heightPx: Float,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        shape = RoundedCornerShape(ui.panelCornerDp.dp),
        modifier = modifier
            .width(360.dp)
            .heightIn(max = 620.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = workspace::save,
                            onDragCancel = workspace::save
                        ) { _, amount ->
                            workspace.settingsX =
                                clampPanelPosition(workspace.settingsX + amount.x / widthPx, 0.12f)
                            workspace.settingsY =
                                clampPanelPosition(workspace.settingsY + amount.y / heightPx, 0.12f)
                        }
                    }
                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⋮⋮", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Dostosuj przestrzeń",
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { workspace.settingsOpen = false }) { Text("Zamknij") }
            }
            HorizontalDivider()

            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsSwitch(
                    title = "Pływające panele",
                    subtitle = "Menu i obszar roboczy są niezależnymi panelami.",
                    checked = workspace.floating,
                    onChecked = { workspace.floating = it; workspace.save() }
                )
                SettingsSwitch(
                    title = "Zablokuj układ",
                    subtitle = "Wyłącza przypadkowe przesuwanie i zmianę rozmiaru.",
                    checked = workspace.locked,
                    onChecked = { workspace.locked = it; workspace.save() }
                )
                SettingsSwitch(
                    title = "Pokaż menu",
                    subtitle = "Możesz ukryć nawigację i odzyskać miejsce na rozmowę.",
                    checked = workspace.showNavigation,
                    onChecked = { workspace.showNavigation = it; workspace.save() }
                )

                HorizontalDivider()
                SliderSetting(
                    label = "Szerokość panelu",
                    value = workspace.contentWidth,
                    range = 0.48f..0.98f,
                    valueLabel = "${(workspace.contentWidth * 100).roundToInt()}%",
                    onValueChange = { workspace.contentWidth = it },
                    onFinished = workspace::save
                )
                SliderSetting(
                    label = "Wysokość panelu",
                    value = workspace.contentHeight,
                    range = 0.48f..0.98f,
                    valueLabel = "${(workspace.contentHeight * 100).roundToInt()}%",
                    onValueChange = { workspace.contentHeight = it },
                    onFinished = workspace::save
                )
                SliderSetting(
                    label = "Skala menu",
                    value = workspace.navScale,
                    range = 0.76f..1.45f,
                    valueLabel = "${(workspace.navScale * 100).roundToInt()}%",
                    onValueChange = { workspace.navScale = it },
                    onFinished = workspace::save
                )
                SliderSetting(
                    label = "Skala tekstu",
                    value = workspace.textScale,
                    range = 0.82f..1.38f,
                    valueLabel = "${(workspace.textScale * 100).roundToInt()}%",
                    onValueChange = { workspace.textScale = it },
                    onFinished = workspace::save
                )
                SliderSetting(
                    label = "Przezroczystość paneli",
                    value = workspace.opacityScale,
                    range = 0.72f..1.04f,
                    valueLabel = "${(workspace.opacityScale * 100).roundToInt()}%",
                    onValueChange = { workspace.opacityScale = it },
                    onFinished = workspace::save
                )

                HorizontalDivider()
                Text(
                    "Live UI z GitHub: ${if (ui.floatingPanels) "floating" else "docked"} • " +
                        "róg ${ui.panelCornerDp.roundToInt()} dp • tekst ${(ui.fontScale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Pozycje i rozmiary zapisują się lokalnie na Queście. Parametry Live UI mogą zmieniać się zdalnie bez przebudowy APK.",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = workspace::reset,
                        modifier = Modifier.weight(1f)
                    ) { Text("Reset układu") }
                    Button(
                        onClick = { workspace.locked = true; workspace.save() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Zablokuj") }
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(valueLabel, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            onValueChangeFinished = onFinished
        )
    }
}

private fun clampPanelPosition(value: Float, visibleFraction: Float): Float =
    value.coerceIn(-0.86f, 1f - visibleFraction)

@Composable
private fun rememberWorkspaceState(): WorkspaceState {
    val context = LocalContext.current
    return remember(context) {
        WorkspaceState(
            context.getSharedPreferences("questgpt_workspace", Context.MODE_PRIVATE)
        )
    }
}

private class WorkspaceState(private val prefs: SharedPreferences) {
    var floating by mutableStateOf(prefs.getBoolean("floating", true))
    var locked by mutableStateOf(prefs.getBoolean("locked", false))
    var showNavigation by mutableStateOf(prefs.getBoolean("showNavigation", true))
    var settingsOpen by mutableStateOf(false)

    var navX by mutableStateOf(prefs.getFloat("navX", 0.012f))
    var navY by mutableStateOf(prefs.getFloat("navY", 0.035f))
    var contentX by mutableStateOf(prefs.getFloat("contentX", 0.145f))
    var contentY by mutableStateOf(prefs.getFloat("contentY", 0.035f))
    var settingsX by mutableStateOf(prefs.getFloat("settingsX", 0.58f))
    var settingsY by mutableStateOf(prefs.getFloat("settingsY", 0.08f))

    var contentWidth by mutableStateOf(prefs.getFloat("contentWidth", 0.84f))
    var contentHeight by mutableStateOf(prefs.getFloat("contentHeight", 0.92f))
    var navScale by mutableStateOf(prefs.getFloat("navScale", 1f))
    var textScale by mutableStateOf(prefs.getFloat("textScale", 1f))
    var opacityScale by mutableStateOf(prefs.getFloat("opacityScale", 1f))

    fun save() {
        prefs.edit()
            .putBoolean("floating", floating)
            .putBoolean("locked", locked)
            .putBoolean("showNavigation", showNavigation)
            .putFloat("navX", navX)
            .putFloat("navY", navY)
            .putFloat("contentX", contentX)
            .putFloat("contentY", contentY)
            .putFloat("settingsX", settingsX)
            .putFloat("settingsY", settingsY)
            .putFloat("contentWidth", contentWidth)
            .putFloat("contentHeight", contentHeight)
            .putFloat("navScale", navScale)
            .putFloat("textScale", textScale)
            .putFloat("opacityScale", opacityScale)
            .apply()
    }

    fun reset() {
        floating = true
        locked = false
        showNavigation = true
        navX = 0.012f
        navY = 0.035f
        contentX = 0.145f
        contentY = 0.035f
        settingsX = 0.58f
        settingsY = 0.08f
        contentWidth = 0.84f
        contentHeight = 0.92f
        navScale = 1f
        textScale = 1f
        opacityScale = 1f
        save()
    }
}

@Composable
fun BoardsScreen(
    boards: List<Board>,
    selectedBoardId: String,
    onSelect: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Plansze", style = MaterialTheme.typography.headlineSmall)
        Text("Każda plansza ma własną historię rozmowy i własny kontekst Responses API. Możesz dodawać dowolne kolejne przestrzenie.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nazwa nowej planszy") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                val clean = name.trim()
                if (clean.isNotEmpty()) {
                    onAdd(clean)
                    name = ""
                }
            }) { Text("Dodaj") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(boards, key = { it.id }) { board ->
                ElevatedCard(
                    onClick = { onSelect(board.id) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(board.name, style = MaterialTheme.typography.titleMedium)
                                if (board.id == selectedBoardId) AssistChip(onClick = {}, label = { Text("Aktywna") })
                            }
                            if (board.description.isNotBlank()) Text(board.description, style = MaterialTheme.typography.bodySmall)
                        }
                        if (boards.size > 1) {
                            TextButton(onClick = { onDelete(board.id) }) { Text("Usuń") }
                        }
                    }
                }
            }
        }
    }
}
