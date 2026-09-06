package pl.somaskan.questgpt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FilesScreen(onOpenFile: () -> Unit, onCreateFile: () -> Unit, onOpenFolder: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Pliki", style = MaterialTheme.typography.headlineSmall)
        Text("Otwieraj i edytuj dokumenty udostępnione aplikacji przez systemowy picker. Dostęp może być zachowany na stałe dla wskazanych plików lub folderów.")
        Button(onClick = onOpenFile, modifier = Modifier.fillMaxWidth()) { Text("Otwórz / edytuj plik") }
        Button(onClick = onCreateFile, modifier = Modifier.fillMaxWidth()) { Text("Utwórz nowy plik") }
        Button(onClick = onOpenFolder, modifier = Modifier.fillMaxWidth()) { Text("Wybierz folder roboczy") }
        Text("Android nie daje zwykłej aplikacji nieograniczonego dostępu do całego systemu plików. Używamy Storage Access Framework, czyli maksymalnego bezpiecznego dostępu przyznanego przez użytkownika.")
    }
}

@Composable
fun UpdatesScreen(onAllowInstalls: () -> Unit, onCheck: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Aktualizacje", style = MaterialTheme.typography.headlineSmall)
        Text("Aplikacja może sprawdzać nową wersję, pobrać APK i otworzyć instalator systemowy. Horizon OS/Android nadal wymaga zgody na instalację zwykłej aplikacji użytkownika.")
        Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Sprawdź aktualizacje") }
        OutlinedButton(onClick = onAllowInstalls, modifier = Modifier.fillMaxWidth()) { Text("Zezwól na instalację aktualizacji") }
    }
}

@Composable
fun SettingsScreen() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ustawienia", style = MaterialTheme.typography.headlineSmall)
        Text("Uprawnienia są przyznawane dokładnie wtedy, gdy dana funkcja ich potrzebuje: mikrofon, powiadomienia, przechwytywanie ekranu, dostęp do wybranych plików/folderów i instalacja pobranej aktualizacji.")
        Text("Funkcje systemowe niedostępne dla zwykłego APK (np. cichy self-update, stały systemowy overlay, pełny root filesystem) nie są emulowane ani obchodzone.")
    }
}
