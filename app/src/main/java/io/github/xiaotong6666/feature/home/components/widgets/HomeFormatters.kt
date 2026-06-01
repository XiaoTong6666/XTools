package io.github.xiaotong6666.feature.home.components.widgets

import io.github.xiaotong6666.feature.home.HomePageState
import io.github.xiaotong6666.feature.home.MemoryData

internal fun buildCpuTopSummary(
    state: HomePageState,
    unknownPlatform: String,
): String {
    val soc = runCatching { android.os.Build.SOC_MODEL.takeIf { it.isNotBlank() } }.getOrDefault(null)
        ?: state.device.model.takeIf { it.isNotBlank() && it != "unknown" }
        ?: state.cpuPlatform
    return if (soc.isNotBlank()) soc else unknownPlatform
}

internal fun formatLoadAverageCompact(loadAverage: String): String {
    val parts = loadAverage
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "--"
        parts.size == 1 -> parts[0]
        else -> parts.take(2).joinToString(" ")
    }
}

internal fun buildMemorySummary(
    memory: MemoryData,
    ramSummary: String,
    zramSummary: String,
): String = listOf(
    ramSummary.format(formatKB(memory.totalKB)),
    if (memory.swapTotalKB > 0) zramSummary.format(formatKB(memory.swapTotalKB)) else null,
).filterNotNull().joinToString(" · ")

internal fun buildBatterySummary(state: HomePageState): String = formatBatteryCapacity(state.battery.capacity)

internal fun buildDeviceTitle(
    state: HomePageState,
    fallbackTitle: String,
): String = android.os.Build.MANUFACTURER.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: fallbackTitle

internal fun buildDeviceSummary(state: HomePageState): String = android.os.Build.MODEL.takeIf { it.isNotBlank() } ?: ""

internal fun formatGpuFreq(freq: String): String = when {
    freq.isEmpty() -> "--"

    freq.toLongOrNull() != null -> {
        val hz = freq.toLong()
        when {
            hz >= 1_000_000L -> String.format("%.0fM", hz / 1_000_000f)
            hz >= 1_000L -> "${hz / 1_000}M"
            else -> "${hz}K"
        }
    }

    else -> freq
}

internal fun formatCpuFreqMini(freq: Long): String = when {
    freq > 0 -> String.format("%dM", freq / 1_000)
    else -> "--"
}

internal fun formatKB(kb: Long): String = when {
    kb >= 1_048_576 -> String.format("%.1fG", kb / 1_048_576f)
    kb >= 1_024 -> String.format("%.0fM", kb / 1_024f)
    kb > 0 -> "${kb}K"
    else -> "0"
}

internal fun formatUptime(millis: Long): String {
    val total = millis / 1000
    if (total <= 0) return "--"
    return String.format("%02d:%02d:%02d", total / 3600, total % 3600 / 60, total % 60)
}

internal fun formatProcessCpuPercent(percent: Float): String = String.format("%.1f%%", percent.coerceAtLeast(0f))

internal fun formatBatteryCapacity(value: Double): String = when {
    value > 0.0 -> String.format("%.0fmAh", value)
    else -> "--"
}

internal fun formatVoltage(value: Int): String {
    if (value <= 0) return "--"
    val v = value.toDouble()
    return when {
        v > 100 -> String.format("%.2fV", v / 1000.0)
        else -> String.format("%.2fV", v)
    }
}

internal fun formatPower(w: Float): String {
    if (w > -0.01f && w < 0.01f) return "--"
    return String.format("%.2fW", w)
}

internal fun formatRamMb(value: Long): String = when {
    value >= 1024 -> String.format("%.1fG", value / 1024f)
    value > 0 -> "${value}M"
    else -> "--"
}
