package pl.somaskan.questgpt

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object WorldVisionRuntime {
    var enabled by mutableStateOf(false)
    var status by mutableStateOf("World Vision: wyłączone")
    var lastFrameAt by mutableStateOf(0L)
    var lastPreviewDataUrl by mutableStateOf<String?>(null)
    var lastError by mutableStateOf<String?>(null)
}
