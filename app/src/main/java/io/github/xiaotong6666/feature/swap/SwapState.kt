package io.github.xiaotong6666.feature.swap

import io.github.xiaotong6666.core.native.Core
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

internal const val DefaultSwapfilePath = "/data/swapfile"
internal const val SwapPageRefreshIntervalMs = 1_000L

data class SwapEntry(
    val path: String = "",
    val kind: String = "",
    val sizeKB: Long = 0,
    val usedKB: Long = 0,
    val priority: Int = 0,
    val isZram: Boolean = false,
    val isLoop: Boolean = false,
    val isManaged: Boolean = false,
) {
    val sizeMB: Int get() = (sizeKB / 1024L).toInt().coerceAtLeast(0)
    val usedMB: Int get() = (usedKB / 1024L).toInt().coerceAtLeast(0)
}

data class ZramDeviceStats(
    val device: String = "",
    val logicalSizeKB: Long = 0,
    val logicalUsedKB: Long = 0,
    val diskSizeBytes: Long = -1,
    val origDataBytes: Long = -1,
    val compressedDataBytes: Long = -1,
    val memoryUsedBytes: Long = -1,
    val statsSource: String = "",
    val backingDevice: String = "",
    val backingWrites: Long = -1,
)

data class SwapState(
    val activeSwaps: List<SwapEntry> = emptyList(),
    val swapExists: Boolean = false,
    val activeFileSwapPath: String = "",
    val activeFileSwapSizeMB: Int = 0,
    val activeFileSwapUsedMB: Int = 0,
    val activeFileSwapPriority: Int = -2,
    val swapFileSize: Int = 0,
    val swapUsedSize: Int = 0,
    val swapPriority: Int = -2,
    val swapUsesLoop: Boolean = false,
    val zramEnabled: Boolean = false,
    val zramSizeMB: Int = 0,
    val zramUsedSize: Int = 0,
    val zramLogicalSizeKB: Long = 0,
    val zramLogicalUsedKB: Long = 0,
    val zramAlgorithm: String = "lz4",
    val compAlgorithms: List<String> = emptyList(),
    val zramDevices: List<ZramDeviceStats> = emptyList(),
    val swappiness: Int = 60,
    val extraFreeKbytes: Long = 0,
    val extraFreeSupported: Boolean = false,
    val watermarkScaleFactor: Int = -1,
    val watermarkBoostFactor: Int = -1,
    val dirtyRatio: Int = -1,
    val dirtyBackgroundRatio: Int = -1,
    val legacyLmkSupported: Boolean = false,
    val legacyLmkMinfree: String = "",
    val loopSwapSupported: Boolean = false,
    val oplusSwappinessSupported: Boolean = false,
    val sceneControllerDetected: Boolean = false,
    val memSummary: List<String> = emptyList(),
    val zramSummary: List<String> = emptyList(),
    val vmStatSummary: List<String> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val lastUpdatedMs: Long = 0L,
) {
    val watermarkSupported: Boolean get() = watermarkScaleFactor >= 0
}

internal fun loadSwapState(): SwapState {
    val snapshot = requireJsonCommand("swap-snapshot")
    val activeSwaps = parseSwapEntries(snapshot.optJSONArray("active_swaps"))
    val primarySwap = activeSwaps.firstOrNull { it.isManaged }
    val activeFileSwap = primarySwap ?: activeSwaps.firstOrNull { !it.isZram }
    val zramInfo = snapshot.optJSONObject("zram") ?: JSONObject()
    val zramDevices = parseZramDevices(snapshot.optJSONArray("zram_devices"))
    val zramSizeBytes = zramInfo.optLong("disksize", -1L)
    val zramLogicalSizeKB = zramInfo.optLong("logical_size_kb", 0L).coerceAtLeast(0L)
    val zramLogicalUsedKB = zramInfo.optLong("logical_used_kb", 0L).coerceAtLeast(0L)
    val algorithms = parseAlgorithms(zramInfo)
    val currentAlgorithm = zramInfo.optString("current_algorithm")
        .ifBlank { currentAlgorithmFromRaw(zramInfo.optString("comp_algorithm")) }
        .ifBlank { algorithms.firstOrNull().orEmpty() }
        .ifBlank { "lz4" }
    val mem = snapshot.optJSONObject("memory") ?: JSONObject()

    return SwapState(
        activeSwaps = activeSwaps,
        swapExists = primarySwap != null,
        activeFileSwapPath = activeFileSwap?.path.orEmpty(),
        activeFileSwapSizeMB = activeFileSwap?.sizeMB ?: 0,
        activeFileSwapUsedMB = activeFileSwap?.usedMB ?: 0,
        activeFileSwapPriority = activeFileSwap?.priority ?: -2,
        swapFileSize = primarySwap?.sizeMB ?: 0,
        swapUsedSize = primarySwap?.usedMB ?: 0,
        swapPriority = primarySwap?.priority ?: -2,
        swapUsesLoop = primarySwap?.isLoop == true,
        zramEnabled = zramSizeBytes > 0L || activeSwaps.any { it.isZram },
        zramSizeMB = (zramSizeBytes / 1024L / 1024L).toInt().coerceAtLeast(0),
        zramUsedSize = (zramLogicalUsedKB / 1024L).toInt().coerceAtLeast(0),
        zramLogicalSizeKB = zramLogicalSizeKB,
        zramLogicalUsedKB = zramLogicalUsedKB,
        zramAlgorithm = currentAlgorithm,
        compAlgorithms = algorithms,
        zramDevices = zramDevices,
        swappiness = snapshot.optInt("swappiness", 60),
        extraFreeKbytes = snapshot.optLong("extra_free_kbytes", 0L),
        extraFreeSupported = snapshot.optBoolean("extra_free_supported", false),
        watermarkScaleFactor = snapshot.optInt("watermark_scale_factor", -1),
        watermarkBoostFactor = snapshot.optInt("watermark_boost_factor", -1),
        dirtyRatio = snapshot.optInt("dirty_ratio", -1),
        dirtyBackgroundRatio = snapshot.optInt("dirty_background_ratio", -1),
        legacyLmkSupported = snapshot.optJSONObject("legacy_lmk")?.optBoolean("supported", false) == true,
        legacyLmkMinfree = snapshot.optJSONObject("legacy_lmk")?.optString("minfree", "").orEmpty(),
        loopSwapSupported = snapshot.optJSONObject("capabilities")?.optBoolean("loop_swap_supported", false) == true,
        oplusSwappinessSupported =
        snapshot.optJSONObject("capabilities")?.optBoolean("oplus_swappiness_supported", false) == true,
        sceneControllerDetected =
        snapshot.optJSONObject("capabilities")?.optBoolean("scene_controller_detected", false) == true,
        memSummary = buildMemorySummary(mem),
        zramSummary = buildZramSummary(zramInfo),
        vmStatSummary = buildVmStatSummary(snapshot.optJSONObject("vmstat") ?: JSONObject()),
        loading = false,
        error = null,
        lastUpdatedMs = System.currentTimeMillis(),
    )
}

internal fun applySwapCreate(sizeMB: Int, priority: Int, useLoop: Boolean = false) {
    require(sizeMB > 0) { "invalid size" }
    requireJsonCommand(
        "create-swapfile",
        JSONObject()
            .put("size_mb", sizeMB)
            .put("priority", priority)
            .put("use_loop", useLoop),
    )
}

internal fun startSwapCreate(sizeMB: Int, priority: Int, useLoop: Boolean): Long = startMemoryJob(
    "swap-create-start",
    JSONObject()
        .put("size_mb", sizeMB)
        .put("priority", priority)
        .put("use_loop", useLoop),
)

internal fun applySwapDisable(removeFile: Boolean) {
    requireJsonCommand(
        "swap-disable",
        JSONObject()
            .put("remove_file", removeFile),
    )
}

internal fun startSwapDisable(removeFile: Boolean): Long = startMemoryJob(
    "swap-disable-start",
    JSONObject().put("remove_file", removeFile),
)

internal fun applySwapPriority(priority: Int) {
    requireJsonCommand("swap-set-priority", JSONObject().put("priority", priority))
}

internal fun startSwapPriority(priority: Int): Long = startMemoryJob(
    "swap-priority-start",
    JSONObject().put("priority", priority),
)

internal fun applyZramResize(sizeMB: Int, algorithm: String) {
    require(sizeMB > 0) { "invalid size" }
    requireJsonCommand(
        "resize-zram",
        JSONObject()
            .put("size_mb", sizeMB)
            .put("algorithm", algorithm),
    )
}

internal fun startZramResize(sizeMB: Int, algorithm: String): Long = startMemoryJob(
    "zram-resize-start",
    JSONObject()
        .put("size_mb", sizeMB)
        .put("algorithm", algorithm),
)

internal fun applyZramDisable() {
    requireJsonCommand("zram-disable")
}

private fun startMemoryJob(type: String, payload: JSONObject): Long {
    val job = requireJsonCommand(type, payload)
    return job.optLong("job_id", 0L).takeIf { it > 0L }
        ?: throw IllegalStateException("$type returned no job id")
}

internal suspend fun awaitMemoryJob(jobId: Long) {
    repeat(360) {
        val job = requireJsonCommand("memory-job-status", JSONObject().put("job_id", jobId))
        when (job.optString("state")) {
            "completed" -> return
            "failed" -> throw IllegalStateException(job.optString("error", "memory operation failed"))
        }
        delay(500)
    }
    throw IllegalStateException("memory operation timed out")
}

internal fun applyVmParameters(
    swappiness: Int,
    extraFreeKbytes: Long?,
    watermarkScaleFactor: Int?,
) {
    requireJsonCommand(
        "set-vm-parameters",
        JSONObject()
            .put("swappiness", swappiness)
            .apply {
                extraFreeKbytes?.let { put("extra_free_kbytes", it) }
                watermarkScaleFactor?.takeIf { it >= 0 }?.let { put("watermark_scale_factor", it) }
            },
    )
}

internal fun applyDropCaches(level: Int) {
    requireJsonCommand("drop-caches-level", JSONObject().put("level", level))
}

internal fun applyCompactMemory() {
    requireJsonCommand("compact-memory")
}

private fun requireJsonCommand(type: String, payload: JSONObject? = null): JSONObject {
    val raw = if (payload == null) {
        Core.executeCommand(type)
    } else {
        Core.executeCommand(type, payload.toString())
    }
    val json = try {
        JSONObject(raw)
    } catch (_: Exception) {
        throw IllegalStateException(raw.ifBlank { "$type returned invalid response" })
    }
    json.optString("error").takeIf { it.isNotBlank() }?.let { error ->
        throw IllegalStateException(error)
    }
    return json
}

private fun parseSwapEntries(array: JSONArray?): List<SwapEntry> {
    if (array == null) return emptyList()
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                SwapEntry(
                    path = item.optString("path", ""),
                    kind = item.optString("type", ""),
                    sizeKB = item.optLong("size_kb", 0L),
                    usedKB = item.optLong("used_kb", 0L),
                    priority = item.optInt("priority", 0),
                    isZram = item.optBoolean("is_zram", false),
                    isLoop = item.optBoolean("is_loop", false),
                    isManaged = item.optBoolean("is_managed", false),
                ),
            )
        }
    }
}

private fun parseZramDevices(array: JSONArray?): List<ZramDeviceStats> {
    if (array == null) return emptyList()
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                ZramDeviceStats(
                    device = item.optString("device", ""),
                    logicalSizeKB = item.optLong("logical_size_kb", 0L).coerceAtLeast(0L),
                    logicalUsedKB = item.optLong("logical_used_kb", 0L).coerceAtLeast(0L),
                    diskSizeBytes = item.optLong("disksize", -1L),
                    origDataBytes = item.optLong("orig_data_size", -1L),
                    compressedDataBytes = item.optLong("compr_data_size", -1L),
                    memoryUsedBytes = item.optLong("mem_used_total", -1L),
                    statsSource = item.optString("stats_source", ""),
                    backingDevice = item.optString("backing_dev", ""),
                    backingWrites = item.optLong("bd_writes", -1L),
                ),
            )
        }
    }
}

private fun parseAlgorithms(zramInfo: JSONObject): List<String> {
    val items = zramInfo.optJSONArray("available_algorithms")
    if (items != null) {
        val parsed = buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.optString(index).trim()
                if (item.isNotEmpty()) {
                    add(item)
                }
            }
        }
        if (parsed.isNotEmpty()) {
            return parsed
        }
    }
    return zramInfo.optString("comp_algorithm")
        .split(Regex("\\s+"))
        .map { it.trim('[', ']', ' ') }
        .filter { it.isNotEmpty() }
}

private fun currentAlgorithmFromRaw(raw: String): String = raw.split(Regex("\\s+"))
    .firstOrNull { it.startsWith('[') && it.endsWith(']') }
    ?.trim('[', ']')
    .orEmpty()

private fun buildMemorySummary(memory: JSONObject): List<String> = buildList {
    add("MemTotal: ${memory.optLong("mem_total_kb", 0L) / 1024L} MB")
    add("MemAvail: ${memory.optLong("mem_avail_kb", 0L) / 1024L} MB")
    add("Cached: ${memory.optLong("cached_kb", 0L) / 1024L} MB")
    add("Buffers: ${memory.optLong("buffers_kb", 0L) / 1024L} MB")
    add("Dirty: ${memory.optLong("dirty_kb", 0L) / 1024L} MB")
    add("Writeback: ${memory.optLong("writeback_kb", 0L) / 1024L} MB")
}

private fun buildZramSummary(zramInfo: JSONObject): List<String> = buildList {
    add("logical_used: ${zramInfo.optLong("logical_used_kb", 0L) / 1024L} MB")
    add("logical_size: ${zramInfo.optLong("logical_size_kb", 0L) / 1024L} MB")
    add("disk_size: ${formatBytes(zramInfo.optLong("disksize", -1L))}")
    add("mem_used_total: ${formatBytes(zramInfo.optLong("mem_used_total", -1L))}")
    add("orig_data_size: ${formatBytes(zramInfo.optLong("orig_data_size", -1L))}")
    add("compr_data_size: ${formatBytes(zramInfo.optLong("compr_data_size", -1L))}")
    add(
        "compression: ${formatCompression(
            zramInfo.optLong("orig_data_size", -1L),
            zramInfo.optLong("compr_data_size", -1L),
        )}",
    )
    add("mem_limit: ${formatZramLimit(zramInfo.optLong("mem_limit", -1L))}")
    add("mem_used_max: ${formatBytes(zramInfo.optLong("mem_used_max", -1L))}")
    add("same_pages: ${formatCount(zramInfo.optLong("same_pages", -1L))}")
    add("pages_compacted: ${formatCount(zramInfo.optLong("pages_compacted", -1L))}")
    add("stats_source: ${zramInfo.optString("stats_source", "unavailable")}")
    add("algorithm: ${zramInfo.optString("current_algorithm", "—")}")
    zramInfo.optString("backing_dev")
        .takeIf { it.isNotBlank() }
        ?.let { add("backing_dev: $it") }
    zramInfo.optLong("bd_count", -1L)
        .takeIf { it >= 0L }
        ?.let { add("backed_pages: $it") }
    zramInfo.optLong("bd_reads", -1L)
        .takeIf { it >= 0L }
        ?.let { add("backing_reads: $it") }
    zramInfo.optLong("bd_writes", -1L)
        .takeIf { it >= 0L }
        ?.let { add("backing_writes: $it") }
}

private fun buildVmStatSummary(vmstat: JSONObject): List<String> = buildList {
    add("pswpin: ${formatVmPages(vmstat.optLong("pswpin", -1L))}")
    add("pswpout: ${formatVmPages(vmstat.optLong("pswpout", -1L))}")
    add("pgscan_kswapd: ${formatCount(vmstat.optLong("pgscan_kswapd", -1L))}")
    add("pgscan_direct: ${formatCount(vmstat.optLong("pgscan_direct", -1L))}")
    add("oom_kill: ${formatCount(vmstat.optLong("oom_kill", -1L))}")
}

internal fun formatBytes(value: Long): String = when {
    value < 0L -> "—"
    else -> "${value / 1024L / 1024L} MB"
}

private fun formatCompression(original: Long, compressed: Long): String = when {
    original <= 0L || compressed < 0L -> "—"
    else -> "${(compressed * 1000L / original) / 10.0}%"
}

private fun formatZramLimit(value: Long): String = when {
    value < 0L -> "—"
    value == 0L -> "∞"
    else -> formatBytes(value)
}

private fun formatVmPages(value: Long): String = when {
    value < 0L -> "—"
    else -> "${value * 4L / 1024L} MB"
}

private fun formatCount(value: Long): String = if (value < 0L) "—" else value.toString()
