package io.github.xiaotong6666.feature.cpu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.xiaotong6666.core.bridge.executeToolsCommand
import io.github.xiaotong6666.core.bridge.readNativeCpuControlSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class CpuControlState(
    val clusterInfo: List<List<String>> = emptyList(),
    val availableFreqs: Map<Int, List<String>> = emptyMap(),
    val availableGovernors: Map<Int, List<String>> = emptyMap(),
    val currentFreqs: Map<Int, String> = emptyMap(),
    val currentMinFreqs: Map<Int, String> = emptyMap(),
    val currentMaxFreqs: Map<Int, String> = emptyMap(),
    val currentGovernors: Map<Int, String> = emptyMap(),
    val coreOnline: List<Boolean> = List(8) { true },
    val gpuMinFreq: String = "— MHz",
    val gpuMaxFreq: String = "— MHz",
    val gpuAvailableFreqs: List<String> = emptyList(),
    val loading: Boolean = true,
)

@Composable
fun rememberCpuControlState(): CpuControlState {
    var state by remember { mutableStateOf(CpuControlState()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val snapshot = readNativeCpuControlSnapshot()
                val gpuInfo = snapshot.gpu
                val gpuMin = gpuInfo.minFreq.takeIf { it > 0L }?.toString() ?: "—"
                val gpuMax = gpuInfo.maxFreq.takeIf { it > 0L }?.toString() ?: "—"

                state = CpuControlState(
                    clusterInfo = snapshot.clusterInfo,
                    availableFreqs = snapshot.availableFreqs,
                    availableGovernors = snapshot.availableGovernors,
                    currentFreqs = snapshot.currentFreqs,
                    currentMinFreqs = snapshot.currentMinFreqs,
                    currentMaxFreqs = snapshot.currentMaxFreqs,
                    currentGovernors = snapshot.currentGovernors,
                    coreOnline = snapshot.coreOnline,
                    gpuMinFreq = gpuMin,
                    gpuMaxFreq = gpuMax,
                    gpuAvailableFreqs = gpuInfo.availableFreqs,
                    loading = false,
                )
            } catch (_: Exception) {
                state = state.copy(loading = false)
            }
        }
    }
    return state
}

fun applyCpuFrequency(core: Int, minFreq: String?, maxFreq: String?) {
    CoroutineScope(Dispatchers.IO).launch {
        executeToolsCommand(
            "set-cpu-freq",
            JSONObject().apply {
                put("core", core)
                minFreq?.toLongOrNull()?.let { put("min", it) }
                maxFreq?.toLongOrNull()?.let { put("max", it) }
            },
        )
    }
}

fun applyCpuGovernor(core: Int, governor: String) {
    CoroutineScope(Dispatchers.IO).launch {
        executeToolsCommand(
            "set-governor",
            JSONObject().put("core", core).put("governor", governor),
        )
    }
}

fun applyCoreOnline(coreIndex: Int, online: Boolean) {
    CoroutineScope(Dispatchers.IO).launch {
        executeToolsCommand(
            "core-ctl",
            JSONObject().put("core", coreIndex).put("online", online),
        )
    }
}
