package io.github.xiaotong6666.feature.process

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaotong6666.tools.R
import io.github.xiaotong6666.uihelper.extensions.androidapp.AppIconImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.util.Locale

@Composable
fun ProcessPage() {
    val procState = rememberProcessState()
    val appRegistry = rememberProcessAppRegistry()
    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var sortMode by remember { mutableIntStateOf(1) }
    var filterMode by remember { mutableIntStateOf(0) }
    var detailPid by remember { mutableIntStateOf(-1) }
    var loadedDetail by remember { mutableStateOf<ToolsProcessData?>(null) }

    LaunchedEffect(detailPid) {
        if (detailPid <= 0) {
            loadedDetail = null
        } else {
            loadedDetail = null
            while (isActive) {
                loadedDetail = readProcessDetail(detailPid)?.takeIf { it.pid == detailPid }
                delay(ProcessDetailRefreshIntervalMs)
            }
        }
    }

    val sortLabels = listOf(
        stringResource(R.string.process_sort_pid),
        stringResource(R.string.process_sort_cpu),
        stringResource(R.string.process_sort_resident),
        stringResource(R.string.process_sort_name),
    )
    val filterLabels = listOf(
        stringResource(R.string.process_filter_all),
        stringResource(R.string.process_filter_apps),
    )
    val sortTabsState = rememberLazyListState()
    val filterTabsState = rememberLazyListState()

    val filtered = remember(procState.processes, searchQuery, filterMode, appRegistry) {
        var list = procState.processes
        if (searchQuery.isNotBlank()) {
            list = list.filter {
                val app = appRegistry.resolve(it)
                it.name.contains(searchQuery, true) ||
                    app?.label?.contains(searchQuery, true) == true ||
                    app?.packageName?.contains(searchQuery, true) == true ||
                    it.pid.toString().contains(searchQuery)
            }
        }
        if (filterMode == 1) {
            list.filter { it.isAppProcess || appRegistry.resolve(it) != null }
        } else {
            list
        }
    }

    val sorted = remember(filtered, sortMode, appRegistry) {
        when (sortMode) {
            1 -> filtered.sortedWith(
                compareByDescending<ToolsProcessData> { it.cpuPercent }
                    .thenByDescending { it.residentKB }
                    .thenBy { it.pid },
            )

            2 -> filtered.sortedWith(
                compareByDescending<ToolsProcessData> { it.residentKB }
                    .thenByDescending { it.cpuPercent }
                    .thenBy { it.pid },
            )

            3 -> filtered.sortedBy { appRegistry.resolve(it)?.label?.lowercase() ?: it.sortName }

            else -> filtered.sortedBy { it.pid }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SearchBar(
            inputField = {
                InputField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = { searchExpanded = false },
                    expanded = searchExpanded,
                    onExpandedChange = { searchExpanded = it },
                    label = stringResource(R.string.process_search_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            expanded = searchExpanded,
            onExpandedChange = { searchExpanded = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) {}

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabRowWithContour(
                tabs = sortLabels,
                selectedTabIndex = sortMode,
                onTabSelected = { sortMode = it },
                modifier = Modifier.fillMaxWidth(),
                listState = sortTabsState,
            )
            TabRowWithContour(
                tabs = filterLabels,
                selectedTabIndex = filterMode,
                onTabSelected = { filterMode = it },
                modifier = Modifier.fillMaxWidth(),
                listState = filterTabsState,
            )
        }

        SmallTitle(
            text = stringResource(R.string.process_count, sorted.size),
            modifier = Modifier.padding(top = 4.dp),
        )

        if (procState.loading) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                InfiniteProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .overScrollVertical(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                overscrollEffect = null,
            ) {
                items(sorted, key = { it.pid }) { proc ->
                    ProcessListCard(
                        proc = proc,
                        app = appRegistry.resolve(proc),
                        onClick = { detailPid = proc.pid },
                    )
                }
            }
        }
    }

    if (detailPid > 0) {
        val liveDetail = procState.processes.find { it.pid == detailPid }
        val detail = loadedDetail?.takeIf { it.pid == detailPid }?.let { loaded ->
            liveDetail?.let { live ->
                loaded.copy(
                    name = live.name,
                    cpuPercent = live.cpuPercent,
                    residentKB = live.residentKB,
                    state = live.state,
                    isAppProcess = live.isAppProcess,
                )
            } ?: loaded
        } ?: liveDetail
        OverlayDialog(
            show = true,
            title = detail?.name ?: stringResource(R.string.process_detail_title),
            summary = detail?.takeIf { it.uid >= 0 }?.let {
                stringResource(R.string.process_detail_summary, it.pid, it.uid)
            } ?: stringResource(R.string.process_pid_summary, detailPid),
            onDismissRequest = { detailPid = -1 },
            content = {
                if (detail != null) {
                    val detailScrollState = rememberScrollState()
                    Column {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(detailScrollState),
                        ) {
                            DetailRow(
                                stringResource(R.string.process_label_cpu),
                                formatCpuPercent(detail.cpuPercent),
                            )
                            if (detail.pssKB >= 0) {
                                DetailRow(
                                    stringResource(R.string.process_label_pss),
                                    formatResidentMemory(detail.pssKB),
                                )
                            }
                            DetailRow(
                                stringResource(R.string.process_label_resident),
                                formatResidentMemory(detail.residentKB),
                            )
                            if (detail.sharedKB >= 0) {
                                DetailRow(
                                    stringResource(R.string.process_label_shared),
                                    formatResidentMemory(detail.sharedKB),
                                )
                            }
                            if (detail.swapKB >= 0) {
                                DetailRow(
                                    stringResource(R.string.process_label_swap),
                                    formatResidentMemory(detail.swapKB),
                                )
                            }
                            if (detail.ioReadBytes >= 0) {
                                DetailRow(
                                    stringResource(R.string.process_label_io_read),
                                    formatIoBytes(detail.ioReadBytes),
                                )
                            }
                            if (detail.ioWriteBytes >= 0) {
                                DetailRow(
                                    stringResource(R.string.process_label_io_write),
                                    formatIoBytes(detail.ioWriteBytes),
                                )
                            }
                            if (detail.ioReadBytesPerSecond >= 0.0) {
                                DetailRow(
                                    stringResource(R.string.process_label_io_read_rate),
                                    formatIoRate(detail.ioReadBytesPerSecond),
                                )
                            }
                            if (detail.ioWriteBytesPerSecond >= 0.0) {
                                DetailRow(
                                    stringResource(R.string.process_label_io_write_rate),
                                    formatIoRate(detail.ioWriteBytesPerSecond),
                                )
                            }
                            if (detail.ppid >= 0) {
                                DetailRow(stringResource(R.string.process_label_ppid), detail.ppid.toString())
                            }
                            detail.user
                                .ifBlank { detail.uid.takeIf { it >= 0 }?.toString().orEmpty() }
                                .takeIf { it.isNotBlank() }
                                ?.let {
                                    DetailRow(stringResource(R.string.process_label_user), it)
                                }
                            DetailRow(
                                stringResource(R.string.process_label_state),
                                formatProcessState(detail.state),
                            )
                            if (detail.elapsedMs >= 0) {
                                DetailRow(
                                    stringResource(R.string.process_label_elapsed),
                                    formatProcessElapsed(detail.elapsedMs),
                                )
                            }
                            detail.command.takeIf { it.isNotBlank() }?.let {
                                DetailBlock(stringResource(R.string.process_label_command), it)
                            }
                            detail.cmdline.takeIf { it.isNotBlank() }?.let {
                                DetailBlock(stringResource(R.string.process_label_cmdline), it)
                            }
                            detail.cpuset.takeIf { it.isNotBlank() }?.let {
                                DetailRow(stringResource(R.string.process_label_cpuset), it)
                            }
                            detail.allowedCpus.takeIf { it.isNotBlank() }?.let {
                                DetailRow(stringResource(R.string.process_label_cpus), it)
                            }
                            if (detail.priority != Long.MIN_VALUE) {
                                DetailRow(
                                    stringResource(R.string.process_label_priority),
                                    detail.priority.toString(),
                                )
                            }
                            if (detail.nice != Long.MIN_VALUE) {
                                DetailRow(
                                    stringResource(R.string.process_label_nice),
                                    detail.nice.toString(),
                                )
                            }
                            detail.scheduler.takeIf { it.isNotBlank() }?.let {
                                DetailRow(stringResource(R.string.process_label_scheduler), it)
                            }
                            if (detail.oomScoreAdj != Int.MIN_VALUE) {
                                DetailRow(
                                    stringResource(R.string.process_label_oom_score_adj),
                                    detail.oomScoreAdj.toString(),
                                )
                            }
                            if (detail.oomAdj != Int.MIN_VALUE) {
                                DetailRow(
                                    stringResource(R.string.process_label_oom_adj),
                                    detail.oomAdj.toString(),
                                )
                            }
                            if (detail.threads >= 0) {
                                DetailRow(
                                    stringResource(R.string.process_label_threads),
                                    detail.threads.toString(),
                                )
                            }
                            detail.cgroup.takeIf { it.isNotBlank() }?.let {
                                DetailBlock(stringResource(R.string.process_label_cgroup), it)
                            }
                            detail.selinuxContext.takeIf { it.isNotBlank() }?.let {
                                DetailBlock(stringResource(R.string.process_label_selinux), it)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TextButton(
                                text = stringResource(R.string.process_close),
                                onClick = { detailPid = -1 },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                text = stringResource(R.string.process_force_stop),
                                onClick = {
                                    applyProcessKill(detailPid)
                                    detailPid = -1
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(
                                    color = MiuixTheme.colorScheme.error,
                                    textColor = MiuixTheme.colorScheme.onError,
                                ),
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ProcessListCard(
    proc: ToolsProcessData,
    app: ProcessAppMetadata?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        showIndication = true,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (app != null) {
                AppIconImage(
                    applicationInfo = app.applicationInfo,
                    label = app.label,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                if (app != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.label,
                            modifier = Modifier
                                .weight(1f)
                                .basicMarquee(iterations = Int.MAX_VALUE),
                            fontSize = MiuixTheme.textStyles.headline2.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = app.packageName,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .basicMarquee(iterations = Int.MAX_VALUE),
                            fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                } else {
                    Text(
                        text = proc.name,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                        fontSize = MiuixTheme.textStyles.headline2.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(
                    text = app?.let {
                        if (proc.name == it.packageName) {
                            stringResource(R.string.process_card_app_meta, proc.pid, proc.state)
                        } else {
                            stringResource(R.string.process_card_app_process_meta, proc.pid, proc.state, proc.name)
                        }
                    } ?: stringResource(R.string.process_card_meta, proc.pid, proc.state),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier.width(ProcessUsageColumnWidth),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = formatCpuPercent(proc.cpuPercent),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = if (proc.cpuPercent > 10f) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = formatResidentMemory(proc.residentKB),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DetailBlock(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

private fun formatCpuPercent(value: Float): String = String.format(Locale.getDefault(), "%.1f%%", value)

private val ProcessUsageColumnWidth = 72.dp
private const val ProcessDetailRefreshIntervalMs = 1_500L

private fun formatProcessState(state: String): String {
    if (state.isBlank()) return "?"
    val code = state.first()
    val label = when (code) {
        'R' -> "running"
        'S' -> "sleeping"
        'D' -> "disk sleep"
        'T' -> "stopped"
        't' -> "tracing stop"
        'Z' -> "zombie"
        'X', 'x' -> "dead"
        'I' -> "idle"
        'P' -> "parked"
        else -> null
    }
    return if (label != null) "$code ($label)" else state
}

private fun formatResidentMemory(kb: Long): String = when {
    kb >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1fG", kb / (1024f * 1024f))
    kb >= 1024L -> String.format(Locale.getDefault(), "%.1fM", kb / 1024f)
    kb > 0 -> "${kb}K"
    else -> "0K"
}

private fun formatIoBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.1fG", bytes / (1024f * 1024f * 1024f))
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1fM", bytes / (1024f * 1024f))
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.1fK", bytes / 1024f)
    else -> "$bytes B"
}

private fun formatIoRate(bytesPerSecond: Double): String = "${formatIoBytes(bytesPerSecond.toLong())}/s"

private fun formatProcessElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1_000L
    val days = totalSeconds / 86_400L
    val hours = totalSeconds % 86_400L / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (days > 0) {
        String.format(Locale.getDefault(), "%dd %02d:%02d:%02d", days, hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
