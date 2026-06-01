package io.github.xiaotong6666.feature.home.components.cards

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaotong6666.feature.home.CpuCoreData
import io.github.xiaotong6666.feature.home.HomePageState
import io.github.xiaotong6666.feature.home.ProcessSummaryData
import io.github.xiaotong6666.feature.home.components.charts.CoreSparkline
import io.github.xiaotong6666.feature.home.components.charts.CpuBarChart
import io.github.xiaotong6666.feature.home.components.charts.CpuLoadLineChart
import io.github.xiaotong6666.feature.home.components.widgets.CompactActionCard
import io.github.xiaotong6666.feature.home.components.widgets.DetailSummaryRow
import io.github.xiaotong6666.feature.home.components.widgets.InlineActionText
import io.github.xiaotong6666.feature.home.components.widgets.InlineSummaryHeader
import io.github.xiaotong6666.feature.home.components.widgets.MetricPill
import io.github.xiaotong6666.feature.home.components.widgets.MetricValue
import io.github.xiaotong6666.feature.home.components.widgets.SectionDivider
import io.github.xiaotong6666.feature.home.components.widgets.UsageMeter
import io.github.xiaotong6666.feature.home.components.widgets.buildBatterySummary
import io.github.xiaotong6666.feature.home.components.widgets.buildCpuTopSummary
import io.github.xiaotong6666.feature.home.components.widgets.buildDeviceSummary
import io.github.xiaotong6666.feature.home.components.widgets.buildDeviceTitle
import io.github.xiaotong6666.feature.home.components.widgets.buildMemorySummary
import io.github.xiaotong6666.feature.home.components.widgets.formatCpuFreqMini
import io.github.xiaotong6666.feature.home.components.widgets.formatGpuFreq
import io.github.xiaotong6666.feature.home.components.widgets.formatKB
import io.github.xiaotong6666.feature.home.components.widgets.formatLoadAverageCompact
import io.github.xiaotong6666.feature.home.components.widgets.formatPower
import io.github.xiaotong6666.feature.home.components.widgets.formatProcessCpuPercent
import io.github.xiaotong6666.feature.home.components.widgets.formatRamMb
import io.github.xiaotong6666.feature.home.components.widgets.formatUptime
import io.github.xiaotong6666.feature.home.components.widgets.formatVoltage
import io.github.xiaotong6666.tools.R
import io.github.xiaotong6666.ui.navigation.FeaturePage
import io.github.xiaotong6666.ui.theme.Colors
import io.github.xiaotong6666.ui.theme.homeInlineContainerColor
import io.github.xiaotong6666.ui.theme.usageRingTrackColor
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun HomeDashboardRow(
    state: HomePageState,
    onNavigate: (FeaturePage) -> Unit,
    onClearRam: () -> Unit,
    onCompactMemory: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MemoryOverviewCard(
            state = state,
            onNavigate = onNavigate,
            onClearRam = onClearRam,
            onCompactMemory = onCompactMemory,
            modifier = Modifier.fillMaxWidth(),
        )
        GpuCompactCard(
            load = state.gpu.load,
            freq = state.gpu.freq,
            renderer = state.gpu.renderer,
            vendor = state.gpu.vendor,
            glVersion = state.gpu.glVersion,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProcessCompactCard(
                state = state,
                onNavigate = onNavigate,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            CpuSummaryCard(
                state = state,
                onNavigate = onNavigate,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        CpuCoreCard(
            state = state,
            onNavigate = onNavigate,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BatteryCompactCard(
                state = state,
                onNavigate = onNavigate,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            DeviceCompactCard(
                state = state,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
internal fun MemoryOverviewCard(
    state: HomePageState,
    onNavigate: (FeaturePage) -> Unit,
    onClearRam: () -> Unit,
    onCompactMemory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val memory = state.memory
    val ramUsed = memory.totalKB - memory.availKB
    val swapUsed = memory.swapTotalKB - memory.swapFreeKB
    val totalMemory = memory.totalKB + memory.swapTotalKB
    val totalUsed = ramUsed + swapUsed
    val ratio = if (totalMemory > 0) totalUsed.toFloat() / totalMemory else 0f
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(12.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = { onNavigate(FeaturePage.Swap) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(72.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = ratio,
                        size = 72.dp,
                        strokeWidth = 5.dp,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            backgroundColor = usageRingTrackColor,
                        ),
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${(ratio * 100).toInt()}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InlineSummaryHeader(
                        title = stringResource(R.string.home_memory_title),
                        summary = buildMemorySummary(
                            memory = memory,
                            ramSummary = stringResource(R.string.home_memory_summary_ram),
                            zramSummary = stringResource(R.string.home_memory_summary_zram),
                        ),
                    )
                    UsageMeter(
                        label = stringResource(R.string.home_ram),
                        usedText = stringResource(R.string.home_memory_used, formatKB(ramUsed)),
                        totalText = formatKB(memory.totalKB),
                        used = ramUsed,
                        total = memory.totalKB,
                        color = Colors.balance,
                        indicatorColors = ProgressIndicatorDefaults.progressIndicatorColors(),
                        action = {
                            InlineActionText(
                                text = stringResource(R.string.home_memory_release),
                                onClick = onClearRam,
                                primary = true,
                            )
                        },
                    )
                    UsageMeter(
                        label = stringResource(R.string.home_zram),
                        usedText = stringResource(
                            R.string.home_memory_used,
                            formatKB(swapUsed),
                        ),
                        totalText = formatKB(memory.swapTotalKB),
                        used = swapUsed,
                        total = memory.swapTotalKB,
                        color = Colors.performance,
                        indicatorColors = ProgressIndicatorDefaults.progressIndicatorColors(),
                        action = {
                            InlineActionText(
                                text = stringResource(R.string.home_memory_compact),
                                onClick = onCompactMemory,
                            )
                        },
                    )
                }
            }

            DetailSummaryRow(
                first = stringResource(R.string.home_memory_cache, formatKB(memory.cachedKB)),
                second = stringResource(R.string.home_memory_buffer, formatKB(memory.buffersKB)),
                third = stringResource(R.string.home_memory_dirty, formatKB(memory.dirtyKB)),
            )
        }
    }
}

@Composable
internal fun GpuCompactCard(
    load: Int,
    freq: String,
    renderer: String = "",
    vendor: String = "",
    glVersion: String = "",
    modifier: Modifier = Modifier,
) {
    val gpuTitle = stringResource(R.string.home_gpu_title)
    val gpuName = listOf(vendor, renderer).filter { it.isNotEmpty() }.joinToString(" ")
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = (load / 100f).coerceIn(0f, 1f),
                    size = 72.dp,
                    strokeWidth = 5.dp,
                    colors = ProgressIndicatorDefaults.progressIndicatorColors(
                        backgroundColor = usageRingTrackColor,
                    ),
                )
                Text(
                    text = "$load%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                InlineSummaryHeader(title = gpuTitle, summary = gpuName.ifEmpty { gpuTitle })
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_gpu_gl),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(modifier = Modifier.weight(0.22f))
                    Text(
                        text = glVersion,
                        fontSize = 8.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                        maxLines = 2,
                        modifier = Modifier.weight(0.78f),
                        textAlign = TextAlign.End,
                    )
                }
                MetricValue(label = stringResource(R.string.home_frequency), value = formatGpuFreq(freq))
            }
        }
    }
}

@Composable
internal fun CpuSummaryCard(
    state: HomePageState,
    onNavigate: (FeaturePage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val loadHistory = remember { mutableStateListOf<Float>() }

    LaunchedEffect(state.cpuLoad) {
        if (state.cpuLoad >= 0f) {
            loadHistory.add(state.cpuLoad)
            while (loadHistory.size > 60) loadHistory.removeAt(0)
        }
    }

    Card(
        modifier = modifier,
        insideMargin = PaddingValues(0.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = { onNavigate(FeaturePage.CpuControl) },
    ) {
        CompactActionCard(
            title = stringResource(R.string.home_cpu_title),
            summary = buildCpuTopSummary(
                state = state,
                unknownPlatform = stringResource(R.string.home_unknown_platform),
            ),
            primary = if (state.cpuLoad > 0) "${state.cpuLoad.toInt()}%" else "--%",
            secondary = stringResource(
                R.string.home_cpu_online_core_count,
                state.cpuCores.count { it.online },
                state.cpuCores.size.coerceAtLeast(1),
            ),
            secondaryColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    CpuBarChart(cores = state.cpuCores)
                    if (loadHistory.size >= 2) {
                        CpuLoadLineChart(
                            history = loadHistory,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetricPill(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.home_temperature),
                        value = if (state.cpuTemp > 0) String.format("%.1f°C", state.cpuTemp) else "--",
                    )
                    MetricPill(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.home_average_load),
                        value = formatLoadAverageCompact(state.loadAverage),
                    )
                }
            }
        }
    }
}

@Composable
internal fun CpuCoreCard(
    state: HomePageState,
    onNavigate: (FeaturePage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val history = remember { mutableStateListOf<List<Float>>() }

    LaunchedEffect(state.cpuCores) {
        val row = state.cpuCores.map { it.load }
        history.add(row)
        while (history.size > 30) history.removeAt(0)
    }

    Card(
        modifier = modifier,
        insideMargin = PaddingValues(0.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = { onNavigate(FeaturePage.CpuControl) },
    ) {
        CompactActionCard(
            title = stringResource(R.string.home_cpu_cores_title),
            summary = stringResource(R.string.home_cpu_cores_summary),
            primary = state.cpuCores.size.toString(),
            secondary = stringResource(R.string.home_cpu_total_cores),
        ) {
            CompactCpuCoreGrid(cores = state.cpuCores, history = history)
        }
    }
}

@Composable
internal fun BatteryCompactCard(
    state: HomePageState,
    onNavigate: (FeaturePage) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPowerDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("battery_power", Context.MODE_PRIVATE) }
    var currentInvert by remember { mutableStateOf(prefs.getBoolean("invert_current", false)) }
    var dialogInvert by remember { mutableStateOf(false) }

    val displayPower = if (currentInvert) -state.battery.power else state.battery.power

    Card(
        modifier = modifier,
        insideMargin = PaddingValues(0.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = { onNavigate(FeaturePage.PowerStat) },
    ) {
        CompactActionCard(
            title = stringResource(R.string.home_battery_title),
            summary = buildBatterySummary(state),
            primary = "${state.battery.level}%",
            secondary = formatVoltage(state.battery.voltage),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricPill(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        dialogInvert = currentInvert
                        showPowerDialog = true
                    },
                    label = stringResource(R.string.home_power),
                    value = formatPower(displayPower),
                )
                MetricPill(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.home_temperature),
                    value = if (state.battery.temp > 0f) String.format("%.1f°C", state.battery.temp) else "--",
                )
            }
        }
    }

    if (showPowerDialog) {
        val reverseCurrentInteractionSource = remember { MutableInteractionSource() }
        OverlayDialog(
            show = true,
            title = stringResource(R.string.home_power_settings_title),
            summary = stringResource(R.string.home_power_settings_summary),
            onDismissRequest = { showPowerDialog = false },
            content = {
                BasicComponent(
                    modifier = Modifier.clickable(
                        interactionSource = reverseCurrentInteractionSource,
                        indication = null,
                        role = Role.Switch,
                    ) { dialogInvert = !dialogInvert },
                    title = stringResource(R.string.home_reverse_current),
                    summary = stringResource(
                        if (dialogInvert) {
                            R.string.home_current_charge_positive
                        } else {
                            R.string.home_current_charge_negative
                        },
                    ),
                    endActions = {
                        Switch(
                            checked = dialogInvert,
                            onCheckedChange = { dialogInvert = it },
                            colors = SwitchDefaults.switchColors(),
                        )
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = stringResource(R.string.home_cancel),
                        onClick = { showPowerDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.home_confirm),
                        onClick = {
                            currentInvert = dialogInvert
                            prefs.edit().putBoolean("invert_current", dialogInvert).apply()
                            showPowerDialog = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            },
        )
    }
}

@Composable
internal fun DeviceCompactCard(
    state: HomePageState,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(0.dp),
    ) {
        CompactActionCard(
            title = buildDeviceTitle(
                state = state,
                fallbackTitle = stringResource(R.string.home_device_info),
            ),
            summary = buildDeviceSummary(state),
            primary = formatUptime(state.uptime),
            secondary = stringResource(R.string.home_device_uptime),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricPill(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.home_android),
                    value = android.os.Build.VERSION.RELEASE,
                )
                MetricPill(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.home_memory_title),
                    value = if (state.device.totalRamMB > 0) formatRamMb(state.device.totalRamMB) else "--",
                )
            }
        }
    }
}

@Composable
internal fun ProcessCompactCard(
    state: HomePageState,
    onNavigate: (FeaturePage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(0.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = { onNavigate(FeaturePage.Process) },
    ) {
        CompactActionCard(
            title = stringResource(R.string.home_process_title),
            summary = stringResource(
                if (state.processes.isEmpty()) {
                    R.string.home_process_empty_summary
                } else {
                    R.string.home_process_active_summary
                },
            ),
            primary = state.processes.firstOrNull()?.name ?: "--",
            secondary = stringResource(R.string.home_process_count, state.processes.size),
            showStatusBlock = false,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            contentSpacing = 4.dp,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.processes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_process_unavailable),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    state.processes.take(3).forEachIndexed { index, process ->
                        if (index > 0) SectionDivider()
                        CompactProcessRow(process = process)
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompactCpuCoreGrid(cores: List<CpuCoreData>, history: List<List<Float>> = emptyList()) {
    val columns = when {
        cores.size >= 8 -> 4
        cores.size == 6 -> 3
        else -> cores.size.coerceAtLeast(2)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cores.take(8).chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { core ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(homeInlineContainerColor)
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (history.size >= 2) {
                                    CoreSparkline(history, core.index, Modifier.fillMaxSize())
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "${core.load.toInt()}%",
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Medium,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                    Text(
                                        text = formatCpuFreqMini(core.curFreq),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (core.online) {
                                            MiuixTheme.colorScheme.onSurface
                                        } else {
                                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        },
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun CompactProcessRow(process: ProcessSummaryData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = process.name,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.home_process_meta, process.pid, process.threads),
                fontSize = 9.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatProcessCpuPercent(process.cpuPercent),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
