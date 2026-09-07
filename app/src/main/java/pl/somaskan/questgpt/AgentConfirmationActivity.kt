package pl.somaskan.questgpt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.somaskan.questgpt.adb.AgentConfirmationCenter

class AgentConfirmationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    ConfirmationContent()
                }
            }
        }
    }

    override fun onBackPressed() {
        AgentConfirmationCenter.deny()
        finish()
    }

    @Composable
    private fun ConfirmationContent() {
        val pending = AgentConfirmationCenter.pending
        LaunchedEffect(pending?.id) {
            if (pending == null) finish()
        }
        if (pending == null) return

        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            ElevatedCard(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("QuestGPT prosi o zgodę", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(pending.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(pending.details, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Akcja nie została jeszcze wykonana. Bez zatwierdzenia zostanie anulowana automatycznie po 30 sekundach.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                AgentConfirmationCenter.deny()
                                finish()
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                        ) { Text("Odrzuć") }
                        Button(
                            onClick = {
                                AgentConfirmationCenter.approve()
                                finish()
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                        ) { Text("Zezwól") }
                    }
                }
            }
        }
    }
}
