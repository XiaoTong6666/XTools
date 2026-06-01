package io.github.xiaotong6666.feature.home

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.xiaotong6666.core.bridge.NativeDeviceInfo
import io.github.xiaotong6666.core.bridge.readNativeHomeSnapshot
import io.github.xiaotong6666.core.daemon.RootDaemonManager
import io.github.xiaotong6666.core.native.Core
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class CpuCoreData(
    val index: Int,
    val load: Float,
    val curFreq: Long,
    val maxFreq: Long,
    val minFreq: Long,
    val online: Boolean,
)

data class GpuData(
    val load: Int,
    val freq: String,
    val renderer: String = "",
    val vendor: String = "",
    val glVersion: String = "",
)

data class MemoryData(
    val totalKB: Long,
    val availKB: Long,
    val swapTotalKB: Long,
    val swapFreeKB: Long,
    val cachedKB: Long,
    val buffersKB: Long,
    val dirtyKB: Long,
)

data class BatteryData(
    val level: Int,
    val temp: Float,
    val current: Long,
    val voltage: Int,
    val capacity: Double,
    val power: Float = -1f,
)

data class ProcessSummaryData(
    val pid: Int,
    val name: String,
    val state: String,
    val rssKB: Long,
    val cpuPercent: Float,
    val virtualBytes: Long,
    val threads: Long,
)

data class DeviceSummaryData(
    val model: String,
    val soc: String,
    val totalRamMB: Long,
    val hottestThermal: Float,
    val focusedActivity: String,
)

data class HomePageState(
    val cpuLoad: Float = 0f,
    val cpuCores: List<CpuCoreData> = emptyList(),
    val loadAverage: String = "",
    val cpuPlatform: String = "",
    val cpuTemp: Float = 0f,
    val gpu: GpuData = GpuData(0, ""),
    val memory: MemoryData = MemoryData(0, 0, 0, 0, 0, 0, 0),
    val battery: BatteryData = BatteryData(0, 0f, 0, 0, 0.0),
    val processes: List<ProcessSummaryData> = emptyList(),
    val device: DeviceSummaryData = DeviceSummaryData("", "", 0, 0f, ""),
    val uptime: Long = 0,
    val powerMode: String = "balance",
    val isRoot: Boolean = true,
)

@Composable
fun rememberHomePageState(context: Context, active: Boolean): HomePageState {
    var state by remember { mutableStateOf(HomePageState()) }
    var lastUpdate by remember { mutableLongStateOf(0L) }
    var lastHeavyUpdate by remember { mutableLongStateOf(0L) }
    var cachedDeviceInfo by remember { mutableStateOf<NativeDeviceInfo?>(null) }
    var cachedFocusedActivity by remember { mutableStateOf("") }
    var cachedProcesses by remember { mutableStateOf<List<ProcessSummaryData>>(emptyList()) }
    var bootstrapReady by remember { mutableStateOf(false) }

    var gpuRenderer by remember { mutableStateOf("") }
    var gpuVendor by remember { mutableStateOf("") }
    var gpuGlVersion by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val uptime = SystemClock.elapsedRealtime()
            state = state.copy(uptime = uptime)
            delay((1000L - uptime % 1000L).coerceAtLeast(200L))
        }
    }

    LaunchedEffect(Unit) {
        val bootstrap = withContext(Dispatchers.IO) {
            val root = runCatching {
                RootDaemonManager.ensureStarted(context)
                Core.ensureInitialized()
                Core.nativeIsAlive()
            }.getOrDefault(false)
            val capacity = runCatching {
                readNativeHomeSnapshot().battery.fullCapacityMah.takeIf { it > 0.0 } ?: 0.0
            }.getOrDefault(0.0)
            BootstrapState(
                root = root,
                capacity = capacity,
            )
        }
        val root = bootstrap.root
        val cap = bootstrap.capacity
        state = HomePageState(isRoot = root, battery = BatteryData(0, 0f, 0, 0, cap), uptime = SystemClock.elapsedRealtime())

        val gpuInfo = withContext(Dispatchers.IO) {
            try {
                val raw = Core.nativeGetGpuRenderer()
                val j = org.json.JSONObject(raw)
                Triple(
                    j.optString("renderer", "").takeIf { it.isNotEmpty() } ?: "",
                    j.optString("vendor", "").takeIf { it.isNotEmpty() } ?: "",
                    j.optString("version", "").takeIf { it.isNotEmpty() } ?: "",
                )
            } catch (_: Exception) {
                Triple("", "", "")
            }
        }
        gpuRenderer = gpuInfo.first
        gpuVendor = gpuInfo.second
        gpuGlVersion = gpuInfo.third
        bootstrapReady = true
    }

    LaunchedEffect(active, bootstrapReady) {
        if (!active || !bootstrapReady) {
            return@LaunchedEffect
        }
        while (true) {
            try {
                val oldState = state
                val snapshotState = withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val snapshot = readNativeHomeSnapshot()
                    val cpuStats = snapshot.cpu
                    val cpuLoadSum = cpuStats.cpuUsage.takeIf { it > 0f } ?: 0f

                    val cores = ArrayList<CpuCoreData>()
                    cpuStats.cores.forEach { core ->
                        val load = core.load.takeIf { it >= 0f } ?: 0f
                        cores.add(CpuCoreData(core.index, load.coerceAtLeast(0f), core.curFreq, core.maxFreq, core.minFreq, core.online))
                    }

                    val memInfo = snapshot.memory
                    val mem = MemoryData(
                        totalKB = memInfo.totalKB,
                        availKB = memInfo.availKB,
                        swapTotalKB = memInfo.swapTotalKB,
                        swapFreeKB = memInfo.swapFreeKB,
                        cachedKB = memInfo.cachedKB,
                        buffersKB = memInfo.buffersKB,
                        dirtyKB = memInfo.dirtyKB,
                    )

                    val rawDeviceInfo = snapshot.device
                    val deviceInfo = when {
                        rawDeviceInfo.model.isNotBlank() || rawDeviceInfo.soc.isNotBlank() || rawDeviceInfo.totalRamMB > 0L -> rawDeviceInfo
                        else -> cachedDeviceInfo ?: rawDeviceInfo
                    }

                    val gpuInfo = deviceInfo.gpu
                    val gpuLoad = gpuInfo.load
                    val gpuFreq = gpuInfo.curFreq.takeIf { it > 0L }?.toString() ?: ""

                    val platform = deviceInfo.soc.takeUnless { it.isBlank() || it == "unknown" }.orEmpty()
                    val focusedActivity = snapshot.focusedActivity.takeIf { it.isNotBlank() } ?: cachedFocusedActivity

                    val cpuTemp = deviceInfo.thermals
                        .asSequence()
                        .map { normalizeThermal(it.temp) }
                        .firstOrNull { it > 0f }
                        ?: 0f

                    val freshProcesses = snapshot.processes
                        .asSequence()
                        .filter { it.pid > 0 && it.name.isNotBlank() }
                        .map {
                            ProcessSummaryData(
                                pid = it.pid,
                                name = it.name,
                                state = it.state,
                                rssKB = it.rssKB,
                                cpuPercent = it.cpuPercent,
                                virtualBytes = it.vmsize,
                                threads = it.threads,
                            )
                        }
                        .sortedWith(
                            compareByDescending<ProcessSummaryData> { it.cpuPercent }
                                .thenByDescending { it.rssKB }
                                .thenBy { it.pid },
                        )
                        .toList()
                    val processes = if (freshProcesses.isNotEmpty()) freshProcesses else cachedProcesses

                    if (processes.isNotEmpty()) {
                        Log.d(
                            "ToolsHomeProcesses",
                            processes.joinToString(prefix = "top=[", postfix = "]", limit = 4) {
                                "${it.name}(pid=${it.pid},cpu=${"%.1f".format(it.cpuPercent)}%,rss=${it.rssKB}KB,vm=${it.virtualBytes}B)"
                            },
                        )
                    }

                    val bat = try {
                        val nativeBattery = snapshot.battery
                        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        val fallbackLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
                        val fallbackCurrent = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0L
                        val fallbackTemp = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
                        val fallbackVoltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                        BatteryData(
                            level = nativeBattery.level.takeIf { it >= 0 } ?: fallbackLevel,
                            temp = nativeBattery.temperature.takeIf { it >= 0f } ?: fallbackTemp,
                            current = nativeBattery.currentNow.takeIf { it != 0L } ?: fallbackCurrent,
                            voltage = nativeBattery.voltageNow.takeIf { it > 0 } ?: fallbackVoltage,
                            capacity = nativeBattery.fullCapacityMah.takeIf { it > 0.0 } ?: oldState.battery.capacity,
                            power = nativeBattery.power,
                        )
                    } catch (_: Exception) {
                        oldState.battery
                    }

                    val up = SystemClock.elapsedRealtime()
                    val hottestThermal = deviceInfo.thermals
                        .map { normalizeThermal(it.temp) }
                        .filter { it > 0f }
                        .maxOrNull()
                        ?: 0f
                    val device = DeviceSummaryData(
                        model = deviceInfo.model.takeUnless { it.isBlank() || it == "unknown" } ?: Build.MODEL,
                        soc = platform.ifBlank {
                            deviceInfo.soc.takeUnless { it.isBlank() || it == "unknown" }.orEmpty()
                        },
                        totalRamMB = deviceInfo.totalRamMB.takeIf { it > 0 } ?: (mem.totalKB / 1024),
                        hottestThermal = hottestThermal,
                        focusedActivity = focusedActivity,
                    )

                    Log.d(
                        "ToolsHomeState",
                        "cpuCores=${cores.size}, cpuLoad=$cpuLoadSum, firstCore=${cores.firstOrNull()?.index}:${cores.firstOrNull()?.load}, battery=${bat.level}, temp=${bat.temp}, procs=${processes.size}, heavyReady=${snapshot.heavyCacheReady}, heavyScheduled=${snapshot.heavyRefreshScheduled}",
                    )

                    SnapshotResult(
                        state = HomePageState(
                            cpuLoad = cpuLoadSum,
                            cpuCores = cores,
                            loadAverage = cpuStats.loadavg,
                            cpuPlatform = platform,
                            cpuTemp = cpuTemp,
                            gpu = GpuData(
                                gpuLoad,
                                gpuFreq.ifEmpty { if (gpuLoad > 0) "--" else "" },
                                renderer = gpuRenderer,
                                vendor = gpuVendor,
                                glVersion = gpuGlVersion,
                            ),
                            memory = mem,
                            battery = bat,
                            processes = processes,
                            device = device,
                            uptime = up,
                            powerMode = oldState.powerMode,
                            isRoot = oldState.isRoot,
                        ),
                        now = now,
                        cachedDeviceInfo = deviceInfo,
                        focusedActivity = focusedActivity,
                        processes = processes,
                    )
                }

                lastUpdate = snapshotState.now
                cachedDeviceInfo = snapshotState.cachedDeviceInfo
                cachedFocusedActivity = snapshotState.focusedActivity
                cachedProcesses = snapshotState.processes
                lastHeavyUpdate = snapshotState.now
                state = snapshotState.state
            } catch (e: Exception) {
                val daemonUnavailable = e.message?.contains("daemon_unavailable") == true
                if (daemonUnavailable) {
                    Log.w("ToolsHomeState", "snapshot loop waiting for daemon: ${e.message}")
                    delay(5000L)
                } else {
                    Log.e("ToolsHomeState", "snapshot loop failed: ${e.message}", e)
                    delay(350L)
                }
                continue
            }
            delay((1000L - (SystemClock.elapsedRealtime() % 1000L)).coerceAtLeast(50L))
        }
    }

    return state
}

private data class BootstrapState(
    val root: Boolean,
    val capacity: Double,
)

private fun normalizeThermal(rawTemp: Int): Float {
    if (rawTemp <= 0) return 0f
    return if (rawTemp >= 1000) rawTemp / 1000f else rawTemp.toFloat()
}

private data class SnapshotResult(
    val state: HomePageState,
    val now: Long,
    val cachedDeviceInfo: NativeDeviceInfo,
    val focusedActivity: String,
    val processes: List<ProcessSummaryData>,
)
