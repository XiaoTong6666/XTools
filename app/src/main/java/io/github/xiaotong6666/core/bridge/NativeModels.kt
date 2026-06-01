package io.github.xiaotong6666.core.bridge

internal data class NativeCpuCore(
    val index: Int,
    val online: Boolean,
    val curFreq: Long,
    val maxFreq: Long,
    val minFreq: Long,
    val governor: String,
    val load: Float,
)

internal data class NativeCpuStats(
    val cpuUsage: Float,
    val loadavg: String,
    val cores: List<NativeCpuCore>,
)

internal data class NativeMemoryInfo(
    val totalKB: Long,
    val availKB: Long,
    val swapTotalKB: Long,
    val swapFreeKB: Long,
    val cachedKB: Long,
    val buffersKB: Long,
    val dirtyKB: Long,
)

internal data class NativeBatteryInfo(
    val level: Int,
    val fullCapacityMah: Double,
    val temperature: Float,
    val currentNow: Long,
    val voltageNow: Int,
    val power: Float = -1f,
)

internal data class NativeChargeStatsSample(
    val timestampMs: Long,
    val capacity: Int,
    val currentMa: Int,
    val temperatureDeciC: Int,
    val powerMw: Float,
)

internal data class NativeChargeStats(
    val sessionActive: Boolean,
    val status: String,
    val chargeType: String,
    val sampleCount: Int,
    val sessionElapsedMs: Long,
    val peakCurrentMa: Int,
    val peakTemperatureDeciC: Int,
    val peakPowerMw: Float,
    val peakVoltageMv: Int,
    val peakChargeCurrentLimitUa: Int,
    val samples: List<NativeChargeStatsSample>,
)

internal data class NativeProcessItem(
    val pid: Int,
    val ppid: Int,
    val name: String,
    val state: String,
    val vmsize: Long = -1,
    val rssKB: Long = -1,
    val sharedKB: Long = -1,
    val swapKB: Long = -1,
    val pssKB: Long = -1,
    val threads: Long = -1,
    val oomAdj: Int = Int.MIN_VALUE,
    val oomScoreAdj: Int = Int.MIN_VALUE,
    val priority: Long = Long.MIN_VALUE,
    val nice: Long = Long.MIN_VALUE,
    val cpuPercent: Float = 0f,
    val cmdline: String = "",
    val command: String = "",
    val uid: Int = -1,
    val user: String = "",
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
)

internal data class NativeProcessList(
    val processes: List<NativeProcessItem>,
)

internal data class NativeGpuInfo(
    val vendor: String,
    val model: String,
    val renderer: String = "",
    val curFreq: Long,
    val minFreq: Long,
    val maxFreq: Long,
    val load: Int,
    val availableFreqs: List<String>,
)

internal data class NativeProcessInfo(
    val pid: Int,
    val ppid: Int,
    val name: String,
    val state: String,
    val vmsize: Long,
    val threads: Long,
    val oomAdj: Int,
)

internal data class NativeThermalZone(
    val type: String,
    val temp: Int,
)

internal data class NativeDeviceInfo(
    val model: String,
    val soc: String,
    val kernel: String,
    val cpuCores: Int,
    val totalRamMB: Long,
    val gpu: NativeGpuInfo,
    val thermals: List<NativeThermalZone>,
)

internal data class NativeHomeSnapshot(
    val cpu: NativeCpuStats,
    val memory: NativeMemoryInfo,
    val battery: NativeBatteryInfo,
    val device: NativeDeviceInfo,
    val focusedActivity: String,
    val processes: List<NativeProcessItem>,
    val heavyCacheReady: Boolean,
    val heavyRefreshScheduled: Boolean,
)

internal data class NativeCpuControlSnapshot(
    val cpu: NativeCpuStats,
    val clusterInfo: List<List<String>>,
    val availableFreqs: Map<Int, List<String>>,
    val availableGovernors: Map<Int, List<String>>,
    val currentFreqs: Map<Int, String>,
    val currentMinFreqs: Map<Int, String>,
    val currentMaxFreqs: Map<Int, String>,
    val currentGovernors: Map<Int, String>,
    val coreOnline: List<Boolean>,
    val gpu: NativeGpuInfo,
)
