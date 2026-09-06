package pl.somaskan.questgpt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.somaskan.questgpt.data.Board
import pl.somaskan.questgpt.navigation.AppScreen

@Composable
fun MultiBoardShell(
    current: AppScreen,
    onScreen: (AppScreen) -> Unit,
    body: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        NavigationRail {
            AppScreen.entries.forEach { screen ->
                NavigationRailItem(
                    selected = current == screen,
                    onClick = { onScreen(screen) },
                    icon = { Text(screen.title.take(1)) },
                    label = { Text(screen.title) }
                )
            }
        }
        Box(Modifier.fillMaxSize().padding(12.dp)) { body() }
    }
}

@Composable
fun BoardsScreen() {
    val boards = remember {
        mutableStateListOf(
            Board("chat", "Rozmowa", "Bieżący kontekst i zadania"),
            Board("research", "Research", "Materiały, linki i analizy"),
            Board("files", "Pliki", "Dokumenty robocze"),
        )
    }
    var name by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Plansze", style = MaterialTheme.typography.headlineSmall)
        Text("Oddzielne przestrzenie robocze. Każda może mieć własny kontekst, pliki i notatki.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nazwa nowej planszy") }, modifier = Modifier.weight(1f))
            Button(onClick = {
                if (name.isNotBlank()) {
                    boards += Board(System.currentTimeMillis().toString(), name.trim())
                    name = ""
                }
            }) { Text("Dodaj") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(boards, key = { it.id }) { board ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(board.name, style = MaterialTheme.typography.titleMedium)
                        if (board.description.isNotBlank()) Text(board.description)
                    }
                }
            }
        }
    }
}
