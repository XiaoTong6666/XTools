package io.github.xiaotong6666.feature.cpu

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.xiaotong6666.core.bridge.readKernelProp
import io.github.xiaotong6666.core.bridge.readKernelProps
import io.github.xiaotong6666.core.bridge.readPathEntries
import io.github.xiaotong6666.core.bridge.writeKernelProp
import io.github.xiaotong6666.core.bridge.writeTextPath
import io.github.xiaotong6666.tools.R
import io.github.xiaotong6666.uihelper.adaptive.AppTextField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

private fun applyGpuMinFreq(value: String) {
    CoroutineScope(Dispatchers.IO).launch {
        listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq",
            "/sys/class/kgsl/kgsl-3d0/min_gpuclk",
        ).forEach { path ->
            runCatching { writeKernelProp(path, value) }
        }
    }
}

private fun applyGpuMaxFreq(value: String) {
    CoroutineScope(Dispatchers.IO).launch {
        listOf(
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
        ).forEach { path ->
            runCatching { writeKernelProp(path, value) }
        }
    }
}

@Composable
fun CpuControlPage() {
    val cpuState = rememberCpuControlState()
    val scope = rememberCoroutineScope()
    var applyBoot by remember { mutableStateOf(false) }

    var cpusetBg by remember { mutableStateOf("—") }
    var cpusetSysBg by remember { mutableStateOf("—") }
    var cpusetFg by remember { mutableStateOf("—") }
    var cpusetTop by remember { mutableStateOf("—") }
    val totalCores = cpuState.coreOnline.size.coerceAtLeast(1)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val paths = listOf(
                "/dev/cpuset/background/cpus",
                "/dev/cpuset/system-background/cpus",
                "/dev/cpuset/foreground/cpus",
                "/dev/cpuset/top-app/cpus",
            )
            val props = try {
                readKernelProps(paths)
            } catch (_: Exception) {
                emptyMap()
            }
            cpusetBg = props[paths[0]].takeUnless { it.isNullOrBlank() } ?: "—"
            cpusetSysBg = props[paths[1]].takeUnless { it.isNullOrBlank() } ?: "—"
            cpusetFg = props[paths[2]].takeUnless { it.isNullOrBlank() } ?: "—"
            cpusetTop = props[paths[3]].takeUnless { it.isNullOrBlank() } ?: "—"
        }
    }

    var gpuMin by remember(cpuState.gpuMinFreq) { mutableStateOf(cpuState.gpuMinFreq) }
    var gpuMax by remember(cpuState.gpuMaxFreq) { mutableStateOf(cpuState.gpuMaxFreq) }
    val gpuFreqs = cpuState.gpuAvailableFreqs

    var cores by remember(cpuState) { mutableStateOf(cpuState.coreOnline) }

    var clusterGovs by remember(cpuState) { mutableStateOf(cpuState.currentGovernors) }
    var clusterMins by remember(cpuState) { mutableStateOf(cpuState.currentMinFreqs) }
    var clusterMaxs by remember(cpuState) { mutableStateOf(cpuState.currentMaxFreqs) }
    val cpusetBackgroundUserTitle = stringResource(R.string.cpu_control_cpuset_background_user)
    val cpusetBackgroundSystemTitle = stringResource(R.string.cpu_control_cpuset_background_system)
    val cpusetForegroundTitle = stringResource(R.string.cpu_control_cpuset_foreground)
    val cpusetTopAppTitle = stringResource(R.string.cpu_control_cpuset_top_app)
    val noDataLabel = stringResource(R.string.common_no_data)
    val clusters = cpuState.clusterInfo.ifEmpty {
        cpuState.currentFreqs.keys.sorted().map { listOf(it.toString()) }
    }
    var paramsTitle by remember { mutableStateOf("") }
    var paramsPath by remember { mutableStateOf("") }
    var paramsItems by remember { mutableStateOf<List<CpuGovernorParamItem>>(emptyList()) }
    var paramsLoading by remember { mutableStateOf(false) }
    var paramsError by remember { mutableStateOf<String?>(null) }
    var showParamsDialog by remember { mutableStateOf(false) }
    var editingParam by remember { mutableStateOf<CpuGovernorParamItem?>(null) }
    var editingParamValue by remember { mutableStateOf("") }
    var savingParam by remember { mutableStateOf(false) }
    var timingTitle by remember { mutableStateOf("") }
    var timingRows by remember { mutableStateOf<List<CpuTimeInStateRow>>(emptyList()) }
    var timingLoading by remember { mutableStateOf(false) }
    var timingError by remember { mutableStateOf<String?>(null) }
    var showTimingDialog by remember { mutableStateOf(false) }

    fun refreshGovernorParams() {
        val targetPath = paramsPath
        if (targetPath.isBlank()) {
            paramsItems = emptyList()
            paramsLoading = false
            return
        }
        paramsLoading = true
        paramsError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    readPathEntries(targetPath)
                        .filter { !it.isDir && it.path.isNotBlank() }
                        .map { CpuGovernorParamItem(it.name, it.path, it.value) }
                }
            }.onSuccess { items ->
                paramsItems = items
                paramsLoading = false
            }.onFailure { error ->
                paramsError = error.message ?: error.javaClass.simpleName
                paramsItems = emptyList()
                paramsLoading = false
            }
        }
    }

    fun openGovernorParams(clusterIndex: Int, firstCore: Int, governor: String) {
        editingParam = null
        editingParamValue = ""
        savingParam = false
        if (governor.isBlank() || governor == "—") {
            paramsTitle = "CPU $clusterIndex"
            paramsPath = ""
            paramsItems = emptyList()
            paramsLoading = false
            paramsError = noDataLabel
            showParamsDialog = true
            return
        }
        paramsTitle = "CPU $clusterIndex · $governor"
        paramsPath = cpufreqGovernorPath(firstCore, governor)
        paramsItems = emptyList()
        paramsError = null
        showParamsDialog = true
        refreshGovernorParams()
    }

    fun openTiming(clusterIndex: Int, firstCore: Int) {
        timingTitle = "CPU $clusterIndex"
        timingRows = emptyList()
        timingError = null
        timingLoading = true
        showTimingDialog = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    parseTimeInStateRows(readKernelProp(cpufreqTimeInStatePath(firstCore)))
                }
            }.onSuccess { rows ->
                timingRows = rows
                timingLoading = false
            }.onFailure { error ->
                timingError = error.message ?: error.javaClass.simpleName
                timingLoading = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        SmallTitle(text = stringResource(R.string.cpu_control_section_switches))
        Card(
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
        ) {
            SwitchPreference(
                title = stringResource(R.string.cpu_control_apply_on_boot_title),
                summary = stringResource(R.string.cpu_control_apply_on_boot_summary),
                checked = applyBoot,
                onCheckedChange = { applyBoot = it },
            )
        }

        SmallTitle(text = stringResource(R.string.cpu_control_section_cpuset))
        Card(
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
        ) {
            CpusetDropdownPreference(
                title = cpusetBackgroundUserTitle,
                mask = cpusetBg,
                path = "/dev/cpuset/background/cpus",
                totalCores = totalCores,
                onMaskChange = { cpusetBg = it },
            )
            CpusetDropdownPreference(
                title = cpusetBackgroundSystemTitle,
                mask = cpusetSysBg,
                path = "/dev/cpuset/system-background/cpus",
                totalCores = totalCores,
                onMaskChange = { cpusetSysBg = it },
            )
            CpusetDropdownPreference(
                title = cpusetForegroundTitle,
                mask = cpusetFg,
                path = "/dev/cpuset/foreground/cpus",
                totalCores = totalCores,
                onMaskChange = { cpusetFg = it },
            )
            CpusetDropdownPreference(
                title = cpusetTopAppTitle,
                mask = cpusetTop,
                path = "/dev/cpuset/top-app/cpus",
                totalCores = totalCores,
                onMaskChange = { cpusetTop = it },
            )
        }

        SmallTitle(text = stringResource(R.string.cpu_control_section_cpu_frequency))
        Card(
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
        ) {
            clusters.forEachIndexed { clusterIndex, clusterCores ->
                val firstCore = clusterCores.firstOrNull()?.toIntOrNull() ?: clusterIndex
                val freqs = cpuState.availableFreqs[clusterIndex] ?: emptyList()
                val governors = cpuState.availableGovernors[clusterIndex] ?: emptyList()
                val minValue = clusterMins[clusterIndex] ?: "—"
                val maxValue = clusterMaxs[clusterIndex] ?: "—"
                val governorValue = clusterGovs[clusterIndex] ?: "—"
                val freqLabels = freqs.map { formatCpuFreqValue(it) }
                val minIndex = freqs.indexOf(minValue).coerceAtLeast(0)
                val maxIndex = freqs.indexOf(maxValue).coerceAtLeast(0)
                val govIndex = governors.indexOf(governorValue).coerceAtLeast(0)

                if (clusterIndex > 0) {
                    SectionDivider()
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "CPU $clusterIndex",
                        modifier = Modifier.weight(1f),
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    TextButton(
                        text = stringResource(R.string.cpu_control_button_parameters),
                        onClick = { openGovernorParams(clusterIndex, firstCore, governorValue) },
                    )
                    TextButton(
                        text = stringResource(R.string.cpu_control_button_timing),
                        onClick = { openTiming(clusterIndex, firstCore) },
                    )
                }
                OverlayDropdownPreference(
                    title = stringResource(R.string.cpu_control_label_min_frequency),
                    summary = formatCpuFreqValue(minValue),
                    items = freqLabels,
                    selectedIndex = minIndex,
                    onSelectedIndexChange = { index ->
                        val value = freqs.getOrNull(index) ?: return@OverlayDropdownPreference
                        clusterMins = clusterMins.toMutableMap().also { map -> map[clusterIndex] = value }
                        applyCpuFrequency(firstCore, value, null)
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.cpu_control_label_max_frequency),
                    summary = formatCpuFreqValue(maxValue),
                    items = freqLabels,
                    selectedIndex = maxIndex,
                    onSelectedIndexChange = { index ->
                        val value = freqs.getOrNull(index) ?: return@OverlayDropdownPreference
                        clusterMaxs = clusterMaxs.toMutableMap().also { map -> map[clusterIndex] = value }
                        applyCpuFrequency(firstCore, null, value)
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.cpu_control_label_governor),
                    summary = governorValue,
                    items = governors,
                    selectedIndex = govIndex,
                    onSelectedIndexChange = { index ->
                        val value = governors.getOrNull(index) ?: return@OverlayDropdownPreference
                        clusterGovs = clusterGovs.toMutableMap().also { map -> map[clusterIndex] = value }
                        applyCpuGovernor(firstCore, value)
                    },
                )
            }
        }

        SmallTitle(text = "GPU")
        Card(
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
        ) {
            val gpuFreqLabels = gpuFreqs.map { formatGpuFreqValue(it) }
            val gpuMinIndex = gpuFreqs.indexOf(gpuMin).coerceAtLeast(0)
            val gpuMaxIndex = gpuFreqs.indexOf(gpuMax).coerceAtLeast(0)
            OverlayDropdownPreference(
                title = stringResource(R.string.cpu_control_label_min_frequency),
                summary = formatGpuFreqValue(gpuMin),
                items = gpuFreqLabels,
                selectedIndex = gpuMinIndex,
                onSelectedIndexChange = { index ->
                    val value = gpuFreqs.getOrNull(index) ?: return@OverlayDropdownPreference
                    gpuMin = value
                    applyGpuMinFreq(value)
                },
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.cpu_control_label_max_frequency),
                summary = formatGpuFreqValue(gpuMax),
                items = gpuFreqLabels,
                selectedIndex = gpuMaxIndex,
                onSelectedIndexChange = { index ->
                    val value = gpuFreqs.getOrNull(index) ?: return@OverlayDropdownPreference
                    gpuMax = value
                    applyGpuMaxFreq(value)
                },
            )
        }

        SmallTitle(text = "Core Online")
        Card(
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                val columns = if (cores.size == 6) 3 else 4
                val rows = (cores.size + columns - 1) / columns
                for (r in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (c in 0 until columns) {
                            val idx = r * columns + c
                            if (idx < cores.size) {
                                val chipShape = RoundedCornerShape(8.dp)
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(2.dp)
                                        .clip(chipShape)
                                        .clickable {
                                            val newVal = !cores[idx]
                                            cores = cores.toMutableList().also { it[idx] = newVal }
                                            applyCoreOnline(idx, newVal)
                                        },
                                    shape = chipShape,
                                    color = if (cores[idx]) {
                                        MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    } else {
                                        MiuixTheme.colorScheme.surfaceContainerHighest
                                    },
                                ) {
                                    Box(
                                        Modifier.padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "CPU$idx",
                                            fontSize = 11.sp,
                                            color = if (cores[idx]) {
                                                MiuixTheme.colorScheme.primary
                                            } else {
                                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            },
                                        )
                                    }
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (r < rows - 1) Spacer(Modifier.height(4.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun returnToParameterList() {
        focusManager.clearFocus()
        keyboardController?.hide()
        editingParam = null
    }

    fun dismissParameterDialog() {
        if (savingParam) return
        focusManager.clearFocus()
        keyboardController?.hide()
        showParamsDialog = false
    }

    OverlayDialog(
        show = showParamsDialog,
        title = editingParam?.name ?: paramsTitle.ifBlank { stringResource(R.string.cpu_control_button_parameters) },
        summary = editingParam?.path ?: paramsPath.ifBlank { stringResource(R.string.cpu_control_parameters_summary) },
        onDismissRequest = ::dismissParameterDialog,
        onDismissFinished = {
            editingParam = null
        },
        content = {
            val dialogScroll = rememberScrollState()
            val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
            NavigationBackHandler(
                state = navEventState,
                isBackEnabled = showParamsDialog && editingParam != null && !savingParam,
                onBackCompleted = ::returnToParameterList,
            )
            AnimatedContent(
                targetState = editingParam,
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(200)) using
                        SizeTransform { _, _ -> tween(250) }
                },
                label = "CpuGovernorParameterContent",
            ) { selectedParam ->
                if (selectedParam == null) {
                    Column {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(dialogScroll),
                        ) {
                            when {
                                paramsLoading -> Text(
                                    stringResource(R.string.cpu_control_loading),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )

                                !paramsError.isNullOrBlank() -> Text(
                                    paramsError.orEmpty(),
                                    color = Color(0xFFD32F2F),
                                )

                                paramsItems.isEmpty() -> Text(
                                    stringResource(R.string.cpu_control_parameter_empty),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )

                                else -> paramsItems.forEach { item ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        onClick = {
                                            editingParam = item
                                            editingParamValue = item.value
                                        },
                                    ) {
                                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                            Text(
                                                item.name,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MiuixTheme.colorScheme.onSurface,
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                item.value.ifBlank { "—" },
                                                fontSize = 12.sp,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(
                                text = stringResource(R.string.cpu_control_close),
                                onClick = ::dismissParameterDialog,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(20.dp))
                            TextButton(
                                text = stringResource(R.string.cpu_control_action_refresh),
                                onClick = { refreshGovernorParams() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                } else {
                    val item = selectedParam
                    Column(Modifier.fillMaxWidth()) {
                        AppTextField(
                            value = editingParamValue,
                            onValueChange = { editingParamValue = it },
                            label = stringResource(R.string.cpu_control_parameter_value),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(
                                text = stringResource(R.string.home_cancel),
                                onClick = {
                                    if (!savingParam) {
                                        returnToParameterList()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(20.dp))
                            TextButton(
                                text = stringResource(R.string.cpu_control_action_apply),
                                onClick = {
                                    if (savingParam) return@TextButton
                                    savingParam = true
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                writeTextPath(item.path, editingParamValue)
                                            }
                                        }.onSuccess {
                                            savingParam = false
                                            returnToParameterList()
                                            refreshGovernorParams()
                                        }.onFailure { error ->
                                            savingParam = false
                                            paramsError = error.message ?: error.javaClass.simpleName
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }
            }
        },
    )

    OverlayDialog(
        show = showTimingDialog,
        title = timingTitle.ifBlank { stringResource(R.string.cpu_control_button_timing) },
        summary = stringResource(R.string.cpu_control_timing_summary),
        onDismissRequest = { showTimingDialog = false },
        content = {
            val dialogScroll = rememberScrollState()
            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(dialogScroll),
                ) {
                    when {
                        timingLoading -> Text(
                            stringResource(R.string.cpu_control_loading),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )

                        !timingError.isNullOrBlank() -> Text(
                            timingError.orEmpty(),
                            color = Color(0xFFD32F2F),
                        )

                        timingRows.isEmpty() -> Text(
                            stringResource(R.string.cpu_control_timing_empty),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )

                        else -> timingRows.forEach { row ->
                            BasicComponent(
                                title = row.frequency,
                                summary = stringResource(
                                    R.string.cpu_control_timing_count,
                                    row.count,
                                    row.ratio,
                                ),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    text = stringResource(R.string.cpu_control_close),
                    onClick = { showTimingDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

fun parseCpusetMask(mask: String, total: Int): List<Boolean> {
    val result = MutableList(total) { false }
    if (mask.isEmpty() || mask == "—") return result
    mask.split(",").forEach { part ->
        if (part.contains("-")) {
            val (s, e) = part.split("-")
            for (i in (s.toIntOrNull() ?: 0)..(e.toIntOrNull() ?: 0)) {
                if (i < total) result[i] = true
            }
        } else {
            part.toIntOrNull()?.let { if (it < total) result[it] = true }
        }
    }
    return result
}

fun maskToString(sel: List<Boolean>): String {
    val parts = mutableListOf<String>()
    var i = 0
    while (i < sel.size) {
        if (sel[i]) {
            val start = i
            while (i < sel.size && sel[i]) i++
            val end = i - 1
            parts.add(if (start == end) "$start" else "$start-$end")
        } else {
            i++
        }
    }
    return if (parts.isEmpty()) "0" else parts.joinToString(",")
}

@Composable
private fun SectionDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 4.dp))
}

@Composable
private fun CpusetDropdownPreference(
    title: String,
    mask: String,
    path: String,
    totalCores: Int,
    onMaskChange: (String) -> Unit,
) {
    val selections = parseCpusetMask(mask, totalCores)
    val allSelected = selections.all { it }

    fun applySelections(updatedSelections: List<Boolean>) {
        val updatedMask = maskToString(updatedSelections)
        onMaskChange(updatedMask)
        CoroutineScope(Dispatchers.IO).launch { writeKernelProp(path, updatedMask) }
    }

    val entries = listOf(
        DropdownEntry(
            items = listOf(
                DropdownItem(
                    text = stringResource(R.string.cpu_control_cpuset_select_all),
                    selected = allSelected,
                    onClick = { applySelections(List(totalCores) { !allSelected }) },
                ),
            ),
        ),
        DropdownEntry(
            items = selections.mapIndexed { index, selected ->
                DropdownItem(
                    text = "CPU$index",
                    selected = selected,
                    onClick = {
                        applySelections(selections.toMutableList().also { it[index] = !selected })
                    },
                )
            },
        ),
    )

    OverlayDropdownPreference(
        entries = entries,
        title = title,
        summary = mask,
        showValue = false,
        collapseOnSelection = false,
    )
}

private fun formatCpuFreqValue(value: String): String {
    val trimmed = value.trim()
    val freq = trimmed.toLongOrNull() ?: return trimmed
    return if (freq >= 1_000L) "${freq / 1_000L} MHz" else trimmed
}

private fun formatGpuFreqValue(value: String): String {
    val trimmed = value.trim()
    val freq = trimmed.toLongOrNull() ?: return trimmed
    val mhz = when {
        freq >= 1_000_000L -> freq / 1_000_000L
        freq >= 1_000L -> freq / 1_000L
        freq > 0L -> freq
        else -> return trimmed
    }
    return "$mhz MHz"
}

private data class CpuGovernorParamItem(
    val name: String,
    val path: String,
    val value: String,
)

private data class CpuTimeInStateRow(
    val frequency: String,
    val count: Long,
    val ratio: String,
)

private fun cpufreqGovernorPath(core: Int, governor: String): String = "/sys/devices/system/cpu/cpu$core/cpufreq/${governor.trim()}"

private fun cpufreqTimeInStatePath(core: Int): String = "/sys/devices/system/cpu/cpu$core/cpufreq/stats/time_in_state"

private fun parseTimeInStateRows(raw: String): List<CpuTimeInStateRow> {
    val entries = raw.lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@mapNotNull null
            val freq = parts[0].toLongOrNull() ?: return@mapNotNull null
            val count = parts[1].toLongOrNull() ?: return@mapNotNull null
            freq to count
        }
        .toList()
    val total = entries.sumOf { it.second.coerceAtLeast(0L) }
    return entries.map { (freq, count) ->
        CpuTimeInStateRow(
            frequency = formatCpuFreqValue(freq.toString()),
            count = count,
            ratio = if (total > 0L) {
                String.format(Locale.US, "%.1f%%", (count * 100.0) / total)
            } else {
                "0.0%"
            },
        )
    }
}
