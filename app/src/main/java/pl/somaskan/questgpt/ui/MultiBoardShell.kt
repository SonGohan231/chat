package pl.somaskan.questgpt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.somaskan.questgpt.data.Board
import pl.somaskan.questgpt.navigation.AppScreen

@Composable
fun MultiBoardShell(current: AppScreen, onScreen: (AppScreen) -> Unit, body: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        NavigationRail(modifier = Modifier.width(118.dp)) {
            Column(
                Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Spacer(Modifier.height(6.dp))
                AppScreen.entries.forEach { screen ->
                    NavigationRailItem(
                        selected = current == screen,
                        onClick = { onScreen(screen) },
                        icon = { Text(screen.short) },
                        label = { Text(screen.title, maxLines = 1) },
                        alwaysShowLabel = true
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        VerticalDivider()
        Box(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) { body() }
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
