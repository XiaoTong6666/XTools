package io.github.xiaotong6666.feature.process

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.xiaotong6666.core.bridge.NativeProcessItem
import io.github.xiaotong6666.core.bridge.executeToolsCommand
import io.github.xiaotong6666.core.bridge.readNativeProcessDetail
import io.github.xiaotong6666.core.bridge.readNativeProcessList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ToolsProcessData(
    val pid: Int = 0,
    val ppid: Int = -1,
    val uid: Int = -1,
    val user: String = "",
    val name: String = "",
    val cpuPercent: Float = 0f,
    val residentKB: Long = 0,
    val sharedKB: Long = -1,
    val swapKB: Long = -1,
    val pssKB: Long = -1,
    val state: String = "",
    val oomAdj: Int = Int.MIN_VALUE,
    val oomScoreAdj: Int = Int.MIN_VALUE,
    val priority: Long = Long.MIN_VALUE,
    val nice: Long = Long.MIN_VALUE,
    val threads: Long = -1,
    val command: String = "",
    val cmdline: String = "",
    val cpuset: String = "",
    val cgroup: String = "",
    val allowedCpus: String = "",
    val scheduler: String = "",
    val selinuxContext: String = "",
    val elapsedMs: Long = -1,
    val ioReadBytes: Long = -1,
    val ioWriteBytes: Long = -1,
    val ioReadBytesPerSecond: Double = -1.0,
    val ioWriteBytesPerSecond: Double = -1.0,
    val isKernelThread: Boolean = false,
    val isAppProcess: Boolean = false,
) {
    val sortName: String = name.lowercase()
}

data class ProcessPageState(
    val processes: List<ToolsProcessData> = emptyList(),
    val loading: Boolean = true,
)

@Composable
fun rememberProcessState(): ProcessPageState {
    var state by remember { mutableStateOf(ProcessPageState()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    val list = withContext(Dispatchers.IO) {
                        readNativeProcessList().map(NativeProcessItem::toToolsProcessData)
                    }
                    state = ProcessPageState(processes = list, loading = false)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    state = state.copy(loading = false)
                }
                delay(PROCESS_REFRESH_INTERVAL_MS)
            }
        }
    }

    return state
}

suspend fun readProcessDetail(pid: Int): ToolsProcessData? = withContext(Dispatchers.IO) {
    readNativeProcessDetail(pid)?.toToolsProcessData()
}

fun applyProcessKill(pid: Int) {
    CoroutineScope(Dispatchers.IO).launch {
        executeToolsCommand("process-kill", org.json.JSONObject().put("pid", pid).put("sig", 9))
    }
}

private const val PROCESS_REFRESH_INTERVAL_MS = 1_500L

private fun NativeProcessItem.toToolsProcessData() = ToolsProcessData(
    pid = pid,
    ppid = ppid,
    uid = uid,
    user = user,
    name = name,
    cpuPercent = cpuPercent.coerceAtLeast(0f),
    residentKB = rssKB.coerceAtLeast(0),
    sharedKB = sharedKB,
    swapKB = swapKB,
    pssKB = pssKB,
    state = state,
    oomAdj = oomAdj,
    oomScoreAdj = oomScoreAdj,
    priority = priority,
    nice = nice,
    threads = threads,
    command = command,
    cmdline = cmdline,
    cpuset = cpuset,
    cgroup = cgroup,
    allowedCpus = allowedCpus,
    scheduler = scheduler,
    selinuxContext = selinuxContext,
    elapsedMs = elapsedMs,
    ioReadBytes = ioReadBytes,
    ioWriteBytes = ioWriteBytes,
    ioReadBytesPerSecond = ioReadBytesPerSecond,
    ioWriteBytesPerSecond = ioWriteBytesPerSecond,
    isKernelThread = isKernelThread,
    isAppProcess = isAppProcess,
)
