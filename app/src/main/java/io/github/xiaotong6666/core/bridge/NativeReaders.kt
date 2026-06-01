package io.github.xiaotong6666.core.bridge

import android.util.Log
import io.github.xiaotong6666.core.native.Core
import org.json.JSONArray
import org.json.JSONObject

private fun requireNativeSuccess(json: JSONObject, source: String) {
    if (json.has("error")) {
        throw IllegalStateException("$source failed: ${json.optString("error")}")
    }
}

internal fun readNativeBatteryInfo(): NativeBatteryInfo {
    Core.ensureInitialized()
    val json = JSONObject(Core.nativeGetBatteryInfo())
    return NativeBatteryInfo(
        level = json.optInt("capacity", -1),
        fullCapacityMah = json.optDouble("full_capacity_mah", 0.0),
        temperature = (json.optInt("temperature", -1).takeIf { it >= 0 } ?: -1) / 10f,
        currentNow = json.optLong("current_now", 0L),
        voltageNow = json.optInt("voltage_now", 0),
        power = json.optDouble("power", -1.0).toFloat(),
    )
}

internal fun readNativeChargeStats(): NativeChargeStats {
    val json = JSONObject(Core.executeCommand("battery-stats"))
    val peaks = json.optJSONObject("peaks") ?: JSONObject()
    val samplesJson = json.optJSONArray("samples") ?: JSONArray()
    val samples = ArrayList<NativeChargeStatsSample>(samplesJson.length())
    for (i in 0 until samplesJson.length()) {
        val item = samplesJson.optJSONObject(i) ?: continue
        samples.add(
            NativeChargeStatsSample(
                timestampMs = item.optLong("timestamp_ms", 0L),
                capacity = item.optInt("capacity", -1),
                currentMa = item.optInt("current_ma", -1),
                temperatureDeciC = item.optInt("temperature_deci_c", -1),
                powerMw = item.optDouble("power_mw", -1.0).toFloat(),
            ),
        )
    }
    return NativeChargeStats(
        sessionActive = json.optBoolean("session_active", false),
        status = json.optString("status", "—"),
        chargeType = json.optString("charge_type", "—"),
        sampleCount = json.optInt("sample_count", 0),
        sessionElapsedMs = json.optLong("session_elapsed_ms", 0L),
        peakCurrentMa = peaks.optInt("current_ma", -1),
        peakTemperatureDeciC = peaks.optInt("temperature_deci_c", -1),
        peakPowerMw = peaks.optDouble("power_mw", -1.0).toFloat(),
        peakVoltageMv = peaks.optInt("voltage_mv", -1),
        peakChargeCurrentLimitUa = peaks.optInt("charge_current_limit_ua", -1),
        samples = samples,
    )
}

internal fun resetNativeChargeStats() {
    Core.executeCommand("battery-stats-reset")
}

internal fun readNativeProcessListJson(): JSONArray = JSONArray(Core.executeCommand("process-list"))

internal fun readNativeProcessList(): List<NativeProcessItem> {
    val json = JSONArray(Core.executeCommand("process-list"))
    val processes = ArrayList<NativeProcessItem>(json.length())
    for (i in 0 until json.length()) {
        val item = json.optJSONObject(i) ?: continue
        parseNativeProcessItem(item)?.let(processes::add)
    }
    return processes
}

internal fun readNativeProcessDetail(pid: Int): NativeProcessItem? {
    val json = JSONObject(
        Core.executeCommand(
            "process-detail",
            JSONObject().put("pid", pid).toString(),
        ),
    )
    if (json.has("error")) return null
    return parseNativeProcessItem(json)
}

internal fun readNativeFocusedActivity(): String {
    val raw = Core.executeCommand("focused-activity").trim()
    if (raw.isEmpty()) return ""
    val match = Regex("""([A-Za-z0-9._]+)/([A-Za-z0-9.${'$'}_]+)""").find(raw)
    return match?.value
        ?: raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            .orEmpty()
}

internal fun readNativeDeviceInfo(): NativeDeviceInfo {
    val json = JSONObject(Core.executeCommand("device-info"))
    val gpuJson = json.optJSONObject("gpu") ?: JSONObject()
    val freqsJson = gpuJson.optJSONArray("available_freqs") ?: JSONArray()
    val freqs = ArrayList<String>(freqsJson.length())
    for (i in 0 until freqsJson.length()) {
        val value = freqsJson.optString(i).trim()
        if (value.isNotEmpty()) {
            freqs.add(value)
        }
    }
    val thermalsJson = json.optJSONArray("thermals") ?: JSONArray()
    val thermals = ArrayList<NativeThermalZone>(thermalsJson.length())
    for (i in 0 until thermalsJson.length()) {
        val item = thermalsJson.optJSONObject(i) ?: continue
        thermals.add(
            NativeThermalZone(
                type = item.optString("type", ""),
                temp = item.optInt("temp", -1),
            ),
        )
    }
    return NativeDeviceInfo(
        model = json.optString("model", ""),
        soc = json.optString("soc", ""),
        kernel = json.optString("kernel", ""),
        cpuCores = json.optInt("cpu_cores", -1),
        totalRamMB = json.optLong("total_ram_mb", -1L),
        gpu = NativeGpuInfo(
            vendor = gpuJson.optString("vendor", ""),
            model = gpuJson.optString("model", ""),
            curFreq = gpuJson.optLong("cur_freq", -1L),
            minFreq = gpuJson.optLong("min_freq", -1L),
            maxFreq = gpuJson.optLong("max_freq", -1L),
            load = gpuJson.optInt("load", -1),
            availableFreqs = freqs,
        ),
        thermals = thermals,
    )
}

internal fun readNativeHomeSnapshot(): NativeHomeSnapshot {
    val startedAt = System.nanoTime()
    val raw = Core.executeCommand("home-snapshot")
    val json = JSONObject(raw)
    requireNativeSuccess(json, "home-snapshot")

    val cpuJson = json.optJSONObject("cpu") ?: JSONObject()
    val coresJson = cpuJson.optJSONArray("cores") ?: JSONArray()
    val cores = ArrayList<NativeCpuCore>(coresJson.length())
    for (i in 0 until coresJson.length()) {
        val item = coresJson.optJSONObject(i) ?: continue
        cores.add(
            NativeCpuCore(
                index = item.optInt("core", i),
                online = item.optBoolean("online", true),
                curFreq = item.optLong("cur_freq", 0L),
                maxFreq = item.optLong("max_freq", 0L),
                minFreq = item.optLong("min_freq", 0L),
                governor = item.optString("governor", "—"),
                load = item.optDouble("load", 0.0).toFloat(),
            ),
        )
    }
    val cpu = NativeCpuStats(
        cpuUsage = cpuJson.optDouble("cpu_usage", 0.0).toFloat().coerceAtLeast(0f),
        loadavg = cpuJson.optString("loadavg", ""),
        cores = cores.sortedBy { it.index },
    )

    val memoryJson = json.optJSONObject("memory") ?: JSONObject()
    val memory = NativeMemoryInfo(
        totalKB = memoryJson.optLong("mem_total_kb", 0L),
        availKB = memoryJson.optLong("mem_avail_kb", 0L),
        swapTotalKB = memoryJson.optLong("swap_total_kb", 0L),
        swapFreeKB = memoryJson.optLong("swap_free_kb", 0L),
        cachedKB = memoryJson.optLong("cached_kb", 0L),
        buffersKB = memoryJson.optLong("buffers_kb", 0L),
        dirtyKB = memoryJson.optLong("dirty_kb", 0L),
    )

    val batteryJson = json.optJSONObject("battery") ?: JSONObject()
    val battery = NativeBatteryInfo(
        level = batteryJson.optInt("capacity", -1),
        fullCapacityMah = batteryJson.optDouble("full_capacity_mah", 0.0),
        temperature = (batteryJson.optInt("temperature", -1).takeIf { it >= 0 } ?: -1) / 10f,
        currentNow = batteryJson.optLong("current_now", 0L),
        voltageNow = batteryJson.optInt("voltage_now", 0),
        power = batteryJson.optDouble("power", -1.0).toFloat(),
    )

    val deviceJson = json.optJSONObject("device") ?: JSONObject()
    val gpuJson = deviceJson.optJSONObject("gpu") ?: JSONObject()
    val freqsJson = gpuJson.optJSONArray("available_freqs") ?: JSONArray()
    val freqs = ArrayList<String>(freqsJson.length())
    for (i in 0 until freqsJson.length()) {
        val value = freqsJson.optString(i).trim()
        if (value.isNotEmpty()) freqs.add(value)
    }
    val thermalsJson = deviceJson.optJSONArray("thermals") ?: JSONArray()
    val thermals = ArrayList<NativeThermalZone>(thermalsJson.length())
    for (i in 0 until thermalsJson.length()) {
        val item = thermalsJson.optJSONObject(i) ?: continue
        thermals.add(
            NativeThermalZone(
                type = item.optString("type", ""),
                temp = item.optInt("temp", -1),
            ),
        )
    }
    val device = NativeDeviceInfo(
        model = deviceJson.optString("model", ""),
        soc = deviceJson.optString("soc", ""),
        kernel = deviceJson.optString("kernel", ""),
        cpuCores = deviceJson.optInt("cpu_cores", -1),
        totalRamMB = deviceJson.optLong("total_ram_mb", -1L),
        gpu = NativeGpuInfo(
            vendor = gpuJson.optString("vendor", ""),
            model = gpuJson.optString("model", ""),
            curFreq = gpuJson.optLong("cur_freq", -1L),
            minFreq = gpuJson.optLong("min_freq", -1L),
            maxFreq = gpuJson.optLong("max_freq", -1L),
            load = gpuJson.optInt("load", -1),
            availableFreqs = freqs,
        ),
        thermals = thermals,
    )

    val processJson = json.optJSONArray("processes") ?: JSONArray()
    val processes = ArrayList<NativeProcessItem>(processJson.length())
    for (i in 0 until processJson.length()) {
        val item = processJson.optJSONObject(i) ?: continue
        parseNativeProcessItem(item)?.let(processes::add)
    }

    val snapshot = NativeHomeSnapshot(
        cpu = cpu,
        memory = memory,
        battery = battery,
        device = device,
        focusedActivity = deviceJson.optString("focused_activity", ""),
        processes = processes,
        heavyCacheReady = json.optJSONObject("cache")?.optBoolean("heavy_ready", false) == true,
        heavyRefreshScheduled = json.optJSONObject("cache")?.optBoolean("heavy_refresh_scheduled", false) == true,
    )
    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
    Log.d(
        "ToolsHomeSnapshot",
        "elapsedMs=$elapsedMs rawLength=${raw.length} cores=${snapshot.cpu.cores.size} procs=${snapshot.processes.size} gpuLoad=${snapshot.device.gpu.load}",
    )
    return snapshot
}

private fun parseNativeProcessItem(item: JSONObject): NativeProcessItem? {
    val name = item.optString("name", "").trim()
    if (name.isEmpty()) return null
    return NativeProcessItem(
        pid = item.optInt("pid", -1),
        ppid = item.optInt("ppid", -1),
        name = name,
        state = item.optString("state", ""),
        vmsize = item.optLong("vmsize", -1L),
        rssKB = item.optLong("rss_kb", -1L),
        sharedKB = item.optLong("shared_kb", -1L),
        swapKB = item.optLong("swap_kb", -1L),
        pssKB = item.optLong("pss_kb", -1L),
        threads = item.optLong("threads", -1L),
        oomAdj = item.optInt("oom_adj", Int.MIN_VALUE),
        oomScoreAdj = item.optInt("oom_score_adj", Int.MIN_VALUE),
        priority = item.optLong("priority", Long.MIN_VALUE),
        nice = item.optLong("nice", Long.MIN_VALUE),
        cpuPercent = item.optDouble("cpu_percent", 0.0).toFloat(),
        cmdline = item.optString("cmdline", ""),
        command = item.optString("command", ""),
        uid = item.optInt("uid", -1),
        user = item.optString("user", ""),
        cpuset = item.optString("cpuset", ""),
        cgroup = item.optString("cgroup", ""),
        allowedCpus = item.optString("allowed_cpus", ""),
        scheduler = item.optString("scheduler", ""),
        selinuxContext = item.optString("selinux_context", ""),
        elapsedMs = item.optLong("elapsed_ms", -1L),
        ioReadBytes = item.optLong("io_read_bytes", -1L),
        ioWriteBytes = item.optLong("io_write_bytes", -1L),
        ioReadBytesPerSecond = item.optDouble("io_read_bps", -1.0),
        ioWriteBytesPerSecond = item.optDouble("io_write_bps", -1.0),
        isKernelThread = item.optBoolean("is_kernel_thread", false),
        isAppProcess = item.optBoolean("is_app_process", false),
    )
}

private fun parseStringMapOfLists(json: JSONObject, key: String): Map<Int, List<String>> {
    val result = linkedMapOf<Int, List<String>>()
    val obj = json.optJSONObject(key) ?: return result
    for (entryKey in obj.keys()) {
        val index = entryKey.toIntOrNull() ?: continue
        val array = obj.optJSONArray(entryKey) ?: JSONArray()
        val values = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i).trim()
            if (value.isNotEmpty()) values.add(value)
        }
        result[index] = values
    }
    return result
}

private fun parseStringMap(json: JSONObject, key: String): Map<Int, String> {
    val result = linkedMapOf<Int, String>()
    val obj = json.optJSONObject(key) ?: return result
    for (entryKey in obj.keys()) {
        val index = entryKey.toIntOrNull() ?: continue
        result[index] = obj.optString(entryKey, "—")
    }
    return result
}

internal fun readNativeCpuControlSnapshot(): NativeCpuControlSnapshot {
    val startedAt = System.nanoTime()
    val raw = Core.executeCommand("cpu-control-snapshot")
    val json = JSONObject(raw)
    requireNativeSuccess(json, "cpu-control-snapshot")

    val cpuJson = json.optJSONObject("cpu") ?: JSONObject()
    val coresJson = cpuJson.optJSONArray("cores") ?: JSONArray()
    val cores = ArrayList<NativeCpuCore>(coresJson.length())
    for (i in 0 until coresJson.length()) {
        val item = coresJson.optJSONObject(i) ?: continue
        cores.add(
            NativeCpuCore(
                index = item.optInt("core", i),
                online = item.optBoolean("online", true),
                curFreq = item.optLong("cur_freq", 0L),
                maxFreq = item.optLong("max_freq", 0L),
                minFreq = item.optLong("min_freq", 0L),
                governor = item.optString("governor", "—"),
                load = item.optDouble("load", 0.0).toFloat(),
            ),
        )
    }
    val cpu = NativeCpuStats(
        cpuUsage = cpuJson.optDouble("cpu_usage", 0.0).toFloat().coerceAtLeast(0f),
        loadavg = cpuJson.optString("loadavg", ""),
        cores = cores.sortedBy { it.index },
    )

    val clusterInfoJson = json.optJSONArray("cluster_info") ?: JSONArray()
    val clusterInfo = ArrayList<List<String>>(clusterInfoJson.length())
    for (i in 0 until clusterInfoJson.length()) {
        val clusterArray = clusterInfoJson.optJSONArray(i) ?: JSONArray()
        val cluster = ArrayList<String>(clusterArray.length())
        for (j in 0 until clusterArray.length()) {
            val value = clusterArray.optString(j).trim()
            if (value.isNotEmpty()) cluster.add(value)
        }
        clusterInfo.add(cluster)
    }

    val coreOnlineJson = json.optJSONArray("core_online") ?: JSONArray()
    val coreOnline = ArrayList<Boolean>(coreOnlineJson.length())
    for (i in 0 until coreOnlineJson.length()) {
        coreOnline.add(coreOnlineJson.optBoolean(i, true))
    }

    val gpuJson = json.optJSONObject("gpu") ?: JSONObject()
    val freqsJson = gpuJson.optJSONArray("available_freqs") ?: JSONArray()
    val freqs = ArrayList<String>(freqsJson.length())
    for (i in 0 until freqsJson.length()) {
        val value = freqsJson.optString(i).trim()
        if (value.isNotEmpty()) freqs.add(value)
    }
    val gpu = NativeGpuInfo(
        vendor = gpuJson.optString("vendor", ""),
        model = gpuJson.optString("model", ""),
        curFreq = gpuJson.optLong("cur_freq", -1L),
        minFreq = gpuJson.optLong("min_freq", -1L),
        maxFreq = gpuJson.optLong("max_freq", -1L),
        load = gpuJson.optInt("load", -1),
        availableFreqs = freqs,
    )

    val snapshot = NativeCpuControlSnapshot(
        cpu = cpu,
        clusterInfo = clusterInfo,
        availableFreqs = parseStringMapOfLists(json, "available_freqs"),
        availableGovernors = parseStringMapOfLists(json, "available_governors"),
        currentFreqs = parseStringMap(json, "current_freqs"),
        currentMinFreqs = parseStringMap(json, "current_min_freqs"),
        currentMaxFreqs = parseStringMap(json, "current_max_freqs"),
        currentGovernors = parseStringMap(json, "current_governors"),
        coreOnline = coreOnline,
        gpu = gpu,
    )
    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
    Log.d(
        "ToolsCpuSnapshot",
        "elapsedMs=$elapsedMs rawLength=${raw.length} cores=${snapshot.cpu.cores.size} clusters=${snapshot.clusterInfo.size} gpuFreqs=${snapshot.gpu.availableFreqs.size}",
    )
    return snapshot
}
