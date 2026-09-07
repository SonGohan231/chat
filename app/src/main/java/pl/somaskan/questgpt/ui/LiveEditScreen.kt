package pl.somaskan.questgpt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.somaskan.questgpt.live.LiveConfig
import pl.somaskan.questgpt.live.LiveUiConfig
import pl.somaskan.questgpt.live.LiveUiRuntime
import kotlin.math.roundToInt

@Composable
fun LiveEditScreen(
    config: LiveConfig,
    status: String,
    localOverride: Boolean,
    onApplyLocal: (LiveConfig) -> Unit,
    onUseRemote: () -> Unit,
    onRefresh: () -> Unit,
) {
    var title by remember(config) { mutableStateOf(config.title) }
    var subtitle by remember(config) { mutableStateOf(config.subtitle) }
    var actions by remember(config) { mutableStateOf(config.quickActions.joinToString("\n")) }
    var templates by remember(config) { mutableStateOf(config.boardTemplates.joinToString("\n")) }
    var refresh by remember(config) { mutableStateOf(config.refreshSeconds.toString()) }

    var floatingPanels by remember(config) { mutableStateOf(config.ui.floatingPanels) }
    var panelOpacity by remember(config) { mutableStateOf(config.ui.panelOpacity) }
    var panelCornerDp by remember(config) { mutableStateOf(config.ui.panelCornerDp) }
    var navWidthDp by remember(config) { mutableStateOf(config.ui.navWidthDp) }
    var contentPaddingDp by remember(config) { mutableStateOf(config.ui.contentPaddingDp) }
    var panelSpacingDp by remember(config) { mutableStateOf(config.ui.panelSpacingDp) }
    var fontScale by remember(config) { mutableStateOf(config.ui.fontScale) }
    var backgroundDim by remember(config) { mutableStateOf(config.ui.backgroundDim) }
    var navigationLabels by remember(config) { mutableStateOf(config.ui.navigationLabels) }
    var showPanelHeader by remember(config) { mutableStateOf(config.ui.showPanelHeader) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Live Edit", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (localOverride) "Tryb: lokalna edycja APK"
                    else "Tryb: synchronizacja z tego chatu / GitHub"
                )
            }
            AssistChip(onClick = {}, label = { Text(status) })
        }

        Text(
            "Zmiany w sekcji Live UI są stosowane od razu. Pozycje paneli zapisują się lokalnie; styl i domyślne parametry mogą być synchronizowane z GitHub bez przebudowy APK.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            title,
            { title = it },
            label = { Text("Tytuł") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            subtitle,
            { subtitle = it },
            label = { Text("Podtytuł") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = actions,
                onValueChange = { actions = it },
                label = { Text("Szybkie akcje — jedna na linię") },
                modifier = Modifier.weight(1f),
                minLines = 4
            )
            OutlinedTextField(
                value = templates,
                onValueChange = { templates = it },
                label = { Text("Szablony plansz — jeden na linię") },
                modifier = Modifier.weight(1f),
                minLines = 4
            )
        }

        OutlinedTextField(
            value = refresh,
            onValueChange = { value -> refresh = value.filter { it.isDigit() } },
            label = { Text("Odświeżanie zdalne (sekundy)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider()
        Text("Live UI", style = MaterialTheme.typography.titleLarge)

        LiveSwitch(
            title = "Pływające panele",
            subtitle = "Menu i główna przestrzeń mogą być przesuwane niezależnie.",
            checked = floatingPanels,
            onChecked = { floatingPanels = it }
        )
        LiveSwitch(
            title = "Etykiety w menu",
            subtitle = "Pokazuje pełne nazwy sekcji obok skrótów.",
            checked = navigationLabels,
            onChecked = { navigationLabels = it }
        )
        LiveSwitch(
            title = "Nagłówek panelu",
            subtitle = "Pasek do przesuwania i szybkiego otwierania ustawień układu.",
            checked = showPanelHeader,
            onChecked = { showPanelHeader = it }
        )

        LiveSlider(
            label = "Przezroczystość paneli",
            value = panelOpacity,
            valueRange = 0.62f..1f,
            valueText = "${(panelOpacity * 100).roundToInt()}%",
            onValueChange = { panelOpacity = it }
        )
        LiveSlider(
            label = "Zaokrąglenie",
            value = panelCornerDp,
            valueRange = 0f..48f,
            valueText = "${panelCornerDp.roundToInt()} dp",
            onValueChange = { panelCornerDp = it }
        )
        LiveSlider(
            label = "Szerokość menu",
            value = navWidthDp,
            valueRange = 88f..210f,
            valueText = "${navWidthDp.roundToInt()} dp",
            onValueChange = { navWidthDp = it }
        )
        LiveSlider(
            label = "Margines treści",
            value = contentPaddingDp,
            valueRange = 4f..32f,
            valueText = "${contentPaddingDp.roundToInt()} dp",
            onValueChange = { contentPaddingDp = it }
        )
        LiveSlider(
            label = "Odstęp między panelami",
            value = panelSpacingDp,
            valueRange = 2f..24f,
            valueText = "${panelSpacingDp.roundToInt()} dp",
            onValueChange = { panelSpacingDp = it }
        )
        LiveSlider(
            label = "Skala tekstu",
            value = fontScale,
            valueRange = 0.82f..1.38f,
            valueText = "${(fontScale * 100).roundToInt()}%",
            onValueChange = { fontScale = it }
        )
        LiveSlider(
            label = "Przyciemnienie tła",
            value = backgroundDim,
            valueRange = 0f..0.5f,
            valueText = "${(backgroundDim * 100).roundToInt()}%",
            onValueChange = { backgroundDim = it }
        )

        HorizontalDivider()

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val next = LiveConfig(
                        title = title.trim().ifEmpty { "QuestGPT" },
                        subtitle = subtitle.trim(),
                        quickActions = actions.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        boardTemplates = templates.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        refreshSeconds = refresh.toLongOrNull()?.coerceIn(5, 3600) ?: 15,
                        updatedAt = "lokalna edycja w APK",
                        ui = LiveUiConfig(
                            floatingPanels = floatingPanels,
                            panelOpacity = panelOpacity,
                            panelCornerDp = panelCornerDp,
                            navWidthDp = navWidthDp,
                            contentPaddingDp = contentPaddingDp,
                            panelSpacingDp = panelSpacingDp,
                            fontScale = fontScale,
                            backgroundDim = backgroundDim,
                            navigationLabels = navigationLabels,
                            showPanelHeader = showPanelHeader,
                        )
                    )
                    LiveUiRuntime.publish(next)
                    onApplyLocal(next)
                },
                modifier = Modifier.weight(1f)
            ) { Text("Zastosuj lokalnie") }

            OutlinedButton(
                onClick = onRefresh,
                enabled = !localOverride,
                modifier = Modifier.weight(1f)
            ) { Text("Pobierz z chatu") }

            if (localOverride) {
                OutlinedButton(
                    onClick = onUseRemote,
                    modifier = Modifier.weight(1f)
                ) { Text("Wróć do synchronizacji") }
            }
        }

        Text(
            "config/live.json na gałęzi main może zmieniać tytuły, szybkie akcje i parametry Live UI podczas działania aplikacji. Build APK jest potrzebny tylko do zmian kodu.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LiveSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
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
private fun LiveSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(valueText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}
