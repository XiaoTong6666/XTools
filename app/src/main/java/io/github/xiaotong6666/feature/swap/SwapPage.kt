package io.github.xiaotong6666.feature.swap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.xiaotong6666.tools.R
import io.github.xiaotong6666.ui.theme.usageRingTrackColor
import io.github.xiaotong6666.uihelper.adaptive.SettingsGroup
import io.github.xiaotong6666.uihelper.adaptive.SettingsGroupDivider
import io.github.xiaotong6666.uihelper.adaptive.SettingsGroupHeader
import io.github.xiaotong6666.uihelper.adaptive.SettingsInfoItem
import io.github.xiaotong6666.uihelper.adaptive.SettingsNavigationItem
import io.github.xiaotong6666.uihelper.adaptive.SettingsToggleItem
import io.github.xiaotong6666.uihelper.dialog.rememberLoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

private const val SwapSizeUnitMb = 128
private const val MaxSwapUnits = 64
private const val MaxExtraFreeMb = 512

@Composable
fun SwapPage() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val refreshMutex = remember { Mutex() }

    var swapState by remember { mutableStateOf(SwapState()) }
    var actionBusy by remember { mutableStateOf(false) }
    val loadingDialog = rememberLoadingDialog()
    val snackbarHostState = remember { SnackbarHostState() }
    var autoApplyProfile by remember { mutableStateOf(MemoryProfileStore.isAutoApplyEnabled(context)) }

    var showSwapCreateDialog by remember { mutableStateOf(false) }
    var showSwapCloseDialog by remember { mutableStateOf(false) }
    var showZramDialog by remember { mutableStateOf(false) }
    var showVmDialog by remember { mutableStateOf(false) }
    var showMaintenanceDialog by remember { mutableStateOf(false) }

    var swapSizeUnits by remember { mutableFloatStateOf((2048 / SwapSizeUnitMb).toFloat()) }
    var swapSizeChanged by remember { mutableStateOf(false) }
    var pendingSwapPriority by remember { mutableIntStateOf(-2) }
    var pendingSwapUseLoop by remember { mutableStateOf(false) }
    var removeSwapFile by remember { mutableStateOf(false) }
    var zramSizeUnits by remember { mutableFloatStateOf((2048 / SwapSizeUnitMb).toFloat()) }
    var zramSizeChanged by remember { mutableStateOf(false) }
    var pendingZramAlgorithm by remember { mutableStateOf("lz4") }
    var pendingSwappiness by remember { mutableFloatStateOf(60f) }
    var pendingExtraFreeMb by remember { mutableFloatStateOf(0f) }
    var pendingWatermarkScaleFactor by remember { mutableFloatStateOf(100f) }

    suspend fun refreshState(showLoading: Boolean) {
        refreshMutex.withLock {
            if (showLoading) {
                swapState = swapState.copy(loading = true, error = null)
            }
            val previous = swapState
            runCatching {
                withContext(Dispatchers.IO) { loadSwapState() }
            }.onSuccess { loaded ->
                if (loaded.copy(lastUpdatedMs = 0L) != previous.copy(lastUpdatedMs = 0L)) {
                    swapState = loaded
                }
            }.onFailure { error ->
                swapState = previous.copy(
                    loading = false,
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun showSnackbar(message: String) {
        scope.launch {
            snackbarHostState.newestSnackbarData()?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    fun runAction(
        successMessage: String,
        progressMessage: String = context.getString(R.string.swap_action_processing),
        block: suspend () -> Unit,
    ) {
        if (actionBusy) return
        actionBusy = true
        scope.launch { snackbarHostState.newestSnackbarData()?.dismiss() }
        scope.launch {
            loadingDialog.withLoading(progressMessage) {
                runCatching {
                    withContext(Dispatchers.IO) { block() }
                }.onSuccess {
                    showSnackbar(successMessage)
                }.onFailure { error ->
                    val message = if (error.message == "swapfile_busy") {
                        context.getString(R.string.swap_operation_busy)
                    } else if (error.message in setOf("swap-create-start failed", "swap-disable-start failed")) {
                        context.getString(R.string.swap_operation_start_failed)
                    } else {
                        error.message ?: error.javaClass.simpleName
                    }
                    showSnackbar(message)
                }
            }
            refreshState(showLoading = false)
            actionBusy = false
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                if (!actionBusy) {
                    refreshState(showLoading = swapState.lastUpdatedMs == 0L)
                }
                delay(SwapPageRefreshIntervalMs)
            }
        }
    }

    val swapActionHint = swapState.activeFileSwapPath.ifBlank {
        stringResource(R.string.swap_no_active_file_swap)
    }
    val zramActionHint = swapState.compAlgorithms.joinToString(" / ").ifBlank {
        stringResource(R.string.swap_daemon_managed)
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MemoryProfileCard(
                autoApply = autoApplyProfile,
                busy = actionBusy,
                onSaveAndApply = {
                    MemoryProfileStore.save(context, swapState)
                    val profile = MemoryProfileStore.load(context)
                    if (profile != null) {
                        runAction(context.getString(R.string.swap_feedback_profile_applied)) {
                            applyMemoryProfile(profile)
                        }
                    }
                },
                onAutoApplyChange = { enabled ->
                    if (enabled) {
                        MemoryProfileStore.save(context, swapState)
                    }
                    MemoryProfileStore.setAutoApplyEnabled(context, enabled)
                    autoApplyProfile = enabled
                },
            )

            SwapCard(
                title = "SWAP",
                ratio = swapUsageRatio(
                    swapState.activeFileSwapUsedMB.toLong(),
                    swapState.activeFileSwapSizeMB.toLong(),
                ),
                state = stringResource(
                    if (swapState.activeFileSwapPath.isNotBlank()) {
                        R.string.swap_state_enabled
                    } else {
                        R.string.swap_state_disabled
                    },
                ),
                size = "${swapState.activeFileSwapUsedMB}MB / ${swapState.activeFileSwapSizeMB}MB",
                note = swapActionHint,
                primaryAction = stringResource(R.string.swap_create),
                secondaryAction = stringResource(R.string.swap_close),
                primaryEnabled = !actionBusy,
                secondaryEnabled = swapState.swapExists && !actionBusy,
                onPrimary = {
                    swapSizeUnits = ((swapState.swapFileSize.takeIf { it > 0 } ?: 2048) / SwapSizeUnitMb)
                        .coerceIn(1, MaxSwapUnits)
                        .toFloat()
                    swapSizeChanged = false
                    pendingSwapPriority = swapState.swapPriority
                    pendingSwapUseLoop = swapState.swapUsesLoop
                    showSwapCreateDialog = true
                },
                onSecondary = {
                    if (!swapState.swapExists) {
                        showSnackbar(context.getString(R.string.swap_no_active_file_swap))
                    } else {
                        removeSwapFile = false
                        showSwapCloseDialog = true
                    }
                },
            )

            ZramCard(
                title = "ZRAM",
                ratio = swapUsageRatio(swapState.zramLogicalUsedKB, swapState.zramLogicalSizeKB),
                state = stringResource(
                    if (swapState.zramEnabled) R.string.zram_state_created else R.string.zram_state_not_created,
                ),
                algo = swapState.zramAlgorithm,
                size = "${swapState.zramUsedSize}MB / ${swapState.zramSizeMB}MB",
                note = zramActionHint,
                actionEnabled = !actionBusy && swapState.zramDevices.size <= 1,
                onAction = {
                    zramSizeUnits = ((swapState.zramSizeMB.takeIf { it > 0 } ?: 2048) / SwapSizeUnitMb)
                        .coerceIn(1, MaxSwapUnits)
                        .toFloat()
                    zramSizeChanged = false
                    pendingZramAlgorithm = swapState.compAlgorithms.firstOrNull { it == swapState.zramAlgorithm }
                        ?: swapState.zramAlgorithm
                    showZramDialog = true
                },
            )

            SettingsGroupHeader(stringResource(R.string.swap_vm_parameters_title))
            SettingsGroup {
                SettingsInfoItem("swappiness", swapState.swappiness.toString())
                SettingsGroupDivider()
                SettingsInfoItem(
                    "extra_free_kbytes",
                    if (swapState.extraFreeSupported) {
                        swapState.extraFreeKbytes.toString()
                    } else {
                        stringResource(R.string.swap_parameter_unsupported)
                    },
                )
                SettingsGroupDivider()
                SettingsInfoItem(
                    "watermark_scale_factor",
                    if (swapState.watermarkSupported) {
                        swapState.watermarkScaleFactor.toString()
                    } else {
                        stringResource(R.string.swap_parameter_unsupported)
                    },
                )
                SettingsGroupDivider()
                SettingsNavigationItem(
                    title = stringResource(R.string.swap_adjust),
                    description = stringResource(R.string.swap_vm_parameters_summary),
                    onClick = {
                        if (!actionBusy) {
                            pendingSwappiness = swapState.swappiness.toFloat()
                            pendingExtraFreeMb = (swapState.extraFreeKbytes / 1024f).coerceAtLeast(0f)
                            pendingWatermarkScaleFactor =
                                (swapState.watermarkScaleFactor.takeIf { it > 0 } ?: 100).toFloat()
                            showVmDialog = true
                        }
                    },
                )
            }

            MemoryMaintenanceCard(
                busy = actionBusy,
                onOpen = { showMaintenanceDialog = true },
            )

            ActiveSwapListCard(entries = swapState.activeSwaps)
            ZramDeviceStatsCard(devices = swapState.zramDevices)

            LegacyLmkCard(
                supported = swapState.legacyLmkSupported,
                minfree = swapState.legacyLmkMinfree,
            )

            MemoryCapabilitiesCard(
                loopSwapSupported = swapState.loopSwapSupported,
                oplusSwappinessSupported = swapState.oplusSwappinessSupported,
                sceneControllerDetected = swapState.sceneControllerDetected,
            )

            SettingsGroupHeader(stringResource(R.string.swap_status_summary))
            SettingsGroup {
                SettingsInfoItem(
                    "SWAP",
                    stringResource(
                        if (swapState.activeFileSwapPath.isNotBlank()) {
                            R.string.swap_state_enabled_short
                        } else {
                            R.string.swap_state_disabled
                        },
                    ),
                )
                SettingsGroupDivider()
                SettingsInfoItem(
                    "ZRAM",
                    stringResource(
                        if (swapState.zramEnabled) {
                            R.string.swap_state_enabled_short
                        } else {
                            R.string.swap_state_disabled
                        },
                    ),
                )
                SettingsGroupDivider()
                SettingsInfoItem(stringResource(R.string.swap_zram_algorithm), swapState.zramAlgorithm)
                SettingsGroupDivider()
                SettingsInfoItem(stringResource(R.string.swap_zram_size), "${swapState.zramSizeMB} MB")
                SettingsGroupDivider()
                SettingsInfoItem(stringResource(R.string.swap_priority), priorityLabel(swapState.swapPriority))
                SettingsGroupDivider()
                SettingsInfoItem("dirty_ratio", unavailableOrValue(swapState.dirtyRatio))
                SettingsGroupDivider()
                SettingsInfoItem("dirty_background_ratio", unavailableOrValue(swapState.dirtyBackgroundRatio))
                SettingsGroupDivider()
                SettingsInfoItem("watermark_boost_factor", unavailableOrValue(swapState.watermarkBoostFactor))
            }

            SummaryGroup(title = stringResource(R.string.swap_memory_summary), lines = swapState.memSummary)
            SummaryGroup(title = stringResource(R.string.swap_zram_statistics), lines = swapState.zramSummary)
            SummaryGroup(title = stringResource(R.string.swap_io_statistics), lines = swapState.vmStatSummary)
            Spacer(Modifier.height(20.dp))
        }

        SnackbarHost(
            state = snackbarHostState,
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding(),
            canSwipeToDismiss = !actionBusy,
            content = { data ->
                Snackbar(
                    data = data,
                    colors = SnackbarDefaults.snackbarColors(
                        containerColor = MiuixTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MiuixTheme.colorScheme.onSurface,
                    ),
                )
            },
        )
    }

    WindowDialog(
        show = showSwapCreateDialog,
        title = stringResource(R.string.swap_create_dialog_title),
        summary = DefaultSwapfilePath,
        onDismissRequest = {
            if (!actionBusy) showSwapCreateDialog = false
        },
        content = {
            Column {
                Text(
                    text = stringResource(R.string.swap_create_dialog_summary, DefaultSwapfilePath),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                SizeSelectionHeader(
                    title = stringResource(R.string.swap_file_size),
                    value = "${swapUnitsToMb(swapSizeUnits)} MB",
                )
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = swapSizeUnits,
                    onValueChange = {
                        swapSizeUnits = it
                        swapSizeChanged = true
                    },
                    valueRange = 1f..MaxSwapUnits.toFloat(),
                    steps = MaxSwapUnits - 2,
                )
                Spacer(Modifier.height(12.dp))
                SwapPrioritySelector(
                    priority = pendingSwapPriority,
                    onPriorityChange = { pendingSwapPriority = it },
                )
                if (swapState.loopSwapSupported) {
                    Spacer(Modifier.height(8.dp))
                    SettingsGroup {
                        SettingsToggleItem(
                            checked = pendingSwapUseLoop,
                            title = stringResource(R.string.swap_use_loop),
                            description = stringResource(R.string.swap_use_loop_summary),
                            onToggle = { pendingSwapUseLoop = !pendingSwapUseLoop },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = stringResource(R.string.home_cancel),
                        onClick = { if (!actionBusy) showSwapCreateDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.swap_create),
                        onClick = {
                            val requestedSize = if (swapSizeChanged) {
                                swapUnitsToMb(swapSizeUnits)
                            } else {
                                swapState.swapFileSize.takeIf { it > 0 }
                                    ?: swapUnitsToMb(swapSizeUnits)
                            }
                            if (swapState.swapExists && requestedSize == swapState.swapFileSize &&
                                pendingSwapUseLoop == swapState.swapUsesLoop
                            ) {
                                if (pendingSwapPriority == swapState.swapPriority) {
                                    showSwapCreateDialog = false
                                } else {
                                    showSwapCreateDialog = false
                                    runAction(context.getString(R.string.swap_feedback_priority_updated)) {
                                        awaitMemoryJob(startSwapPriority(pendingSwapPriority))
                                    }
                                }
                            } else {
                                showSwapCreateDialog = false
                                runAction(
                                    successMessage = context.getString(R.string.swap_feedback_created),
                                    progressMessage = context.getString(R.string.swap_action_creating),
                                ) {
                                    awaitMemoryJob(
                                        startSwapCreate(
                                            requestedSize,
                                            pendingSwapPriority,
                                            pendingSwapUseLoop,
                                        ),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )

    WindowDialog(
        show = showSwapCloseDialog,
        title = stringResource(R.string.swap_close_dialog_title),
        summary = stringResource(R.string.swap_close_dialog_summary),
        onDismissRequest = {
            if (!actionBusy) showSwapCloseDialog = false
        },
        content = {
            SettingsGroup {
                SettingsToggleItem(
                    checked = removeSwapFile,
                    title = stringResource(R.string.swap_remove_file),
                    description = stringResource(R.string.swap_remove_file_summary),
                    onToggle = { removeSwapFile = !removeSwapFile },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(R.string.home_cancel),
                    onClick = { if (!actionBusy) showSwapCloseDialog = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.swap_close),
                    onClick = {
                        showSwapCloseDialog = false
                        runAction(
                            successMessage = context.getString(R.string.swap_feedback_disabled),
                            progressMessage = context.getString(R.string.swap_action_closing),
                        ) {
                            awaitMemoryJob(startSwapDisable(removeFile = removeSwapFile))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )

    WindowDialog(
        show = showZramDialog,
        title = "ZRAM",
        summary = stringResource(R.string.swap_zram_adjust_summary),
        onDismissRequest = {
            if (!actionBusy) showZramDialog = false
        },
        content = {
            val dialogScroll = rememberScrollState()
            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(dialogScroll),
                ) {
                    SizeSelectionHeader(
                        title = stringResource(R.string.swap_zram_size),
                        value = "${swapUnitsToMb(zramSizeUnits)} MB",
                    )
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = zramSizeUnits,
                        onValueChange = {
                            zramSizeUnits = it
                            zramSizeChanged = true
                        },
                        valueRange = 1f..MaxSwapUnits.toFloat(),
                        steps = MaxSwapUnits - 2,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.swap_zram_algorithm),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingsGroup {
                        swapState.compAlgorithms.forEachIndexed { index, algorithm ->
                            if (index > 0) SettingsGroupDivider()
                            BasicComponent(
                                title = algorithm,
                                summary = if (algorithm == pendingZramAlgorithm) {
                                    stringResource(R.string.swap_selected)
                                } else {
                                    null
                                },
                                onClick = { pendingZramAlgorithm = algorithm },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = stringResource(R.string.home_cancel),
                        onClick = { if (!actionBusy) showZramDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.swap_adjust),
                        onClick = {
                            val algorithmChanged = pendingZramAlgorithm != swapState.zramAlgorithm
                            if (swapState.zramEnabled && !zramSizeChanged && !algorithmChanged) {
                                showZramDialog = false
                            } else {
                                showZramDialog = false
                                runAction(context.getString(R.string.swap_feedback_zram_updated)) {
                                    awaitMemoryJob(
                                        startZramResize(
                                            sizeMB = if (zramSizeChanged || swapState.zramSizeMB <= 0) {
                                                swapUnitsToMb(zramSizeUnits)
                                            } else {
                                                swapState.zramSizeMB
                                            },
                                            algorithm = pendingZramAlgorithm,
                                        ),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )

    WindowDialog(
        show = showVmDialog,
        title = stringResource(R.string.swap_vm_parameters_title),
        summary = stringResource(R.string.swap_vm_parameters_summary),
        onDismissRequest = {
            if (!actionBusy) showVmDialog = false
        },
        content = {
            val dialogScroll = rememberScrollState()
            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(dialogScroll),
                ) {
                    VmSliderRow(
                        label = "swappiness",
                        value = pendingSwappiness,
                        range = 0f..200f,
                        onValueChange = { pendingSwappiness = it },
                        display = pendingSwappiness.roundToInt().toString(),
                    )
                    if (swapState.extraFreeSupported) {
                        Spacer(Modifier.height(16.dp))
                        VmSliderRow(
                            label = "extra_free_kbytes",
                            value = pendingExtraFreeMb,
                            range = 0f..MaxExtraFreeMb.toFloat(),
                            onValueChange = { pendingExtraFreeMb = it },
                            display = "${pendingExtraFreeMb.roundToInt() * 1024} KB",
                        )
                    }
                    if (swapState.watermarkSupported) {
                        Spacer(Modifier.height(16.dp))
                        VmSliderRow(
                            label = "watermark_scale_factor",
                            value = pendingWatermarkScaleFactor,
                            range = 1f..1000f,
                            onValueChange = { pendingWatermarkScaleFactor = it },
                            display = pendingWatermarkScaleFactor.roundToInt().toString(),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = stringResource(R.string.home_cancel),
                        onClick = { if (!actionBusy) showVmDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.swap_apply_parameters),
                        onClick = {
                            showVmDialog = false
                            runAction(context.getString(R.string.swap_feedback_parameters_updated)) {
                                applyVmParameters(
                                    swappiness = pendingSwappiness.roundToInt(),
                                    extraFreeKbytes = (pendingExtraFreeMb.roundToInt() * 1024L)
                                        .takeIf { swapState.extraFreeSupported },
                                    watermarkScaleFactor =
                                    pendingWatermarkScaleFactor.roundToInt().takeIf {
                                        swapState.watermarkSupported
                                    },
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )

    WindowDialog(
        show = showMaintenanceDialog,
        title = stringResource(R.string.swap_maintenance_title),
        summary = stringResource(R.string.swap_maintenance_summary),
        onDismissRequest = { if (!actionBusy) showMaintenanceDialog = false },
        content = {
            SettingsGroup {
                BasicComponent(
                    title = stringResource(R.string.swap_drop_page_cache),
                    onClick = {
                        showMaintenanceDialog = false
                        runAction(context.getString(R.string.swap_feedback_maintenance_complete)) {
                            applyDropCaches(1)
                        }
                    },
                )
                SettingsGroupDivider()
                BasicComponent(
                    title = stringResource(R.string.swap_drop_dentries),
                    onClick = {
                        showMaintenanceDialog = false
                        runAction(context.getString(R.string.swap_feedback_maintenance_complete)) {
                            applyDropCaches(2)
                        }
                    },
                )
                SettingsGroupDivider()
                BasicComponent(
                    title = stringResource(R.string.swap_drop_all_caches),
                    onClick = {
                        showMaintenanceDialog = false
                        runAction(context.getString(R.string.swap_feedback_maintenance_complete)) {
                            applyDropCaches(3)
                        }
                    },
                )
                SettingsGroupDivider()
                BasicComponent(
                    title = stringResource(R.string.swap_compact_memory),
                    onClick = {
                        showMaintenanceDialog = false
                        runAction(context.getString(R.string.swap_feedback_maintenance_complete)) {
                            applyCompactMemory()
                        }
                    },
                )
            }
        },
    )
}

@Composable
private fun ActiveSwapListCard(entries: List<SwapEntry>) {
    SettingsGroupHeader(stringResource(R.string.swap_active_list_title))
    SettingsGroup {
        if (entries.isEmpty()) {
            SettingsInfoItem(
                title = stringResource(R.string.swap_active_list_title),
                value = stringResource(R.string.common_no_data),
            )
        } else {
            entries.forEachIndexed { index, entry ->
                if (index > 0) SettingsGroupDivider()
                SettingsInfoItem(
                    title = entry.path,
                    value = stringResource(
                        R.string.swap_active_entry_summary,
                        entry.kind,
                        entry.usedMB,
                        entry.sizeMB,
                        entry.priority,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SwapPrioritySelector(priority: Int, onPriorityChange: (Int) -> Unit) {
    Text(
        text = stringResource(R.string.swap_priority),
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(5, 0, -2).forEach { candidate ->
            val selected = candidate == priority
            TextButton(
                text = priorityLabel(candidate),
                onClick = { onPriorityChange(candidate) },
                modifier = Modifier.weight(1f).height(40.dp),
                minWidth = 0.dp,
                minHeight = 40.dp,
                insideMargin = PaddingValues(horizontal = 4.dp),
                textStyle = MiuixTheme.textStyles.button.copy(fontSize = 12.sp),
                colors = if (selected) {
                    ButtonDefaults.textButtonColorsPrimary()
                } else {
                    ButtonDefaults.textButtonColors()
                },
            )
        }
    }
}

@Composable
private fun MemoryMaintenanceCard(busy: Boolean, onOpen: () -> Unit) {
    SettingsGroupHeader(stringResource(R.string.swap_maintenance_title))
    SettingsGroup {
        SettingsNavigationItem(
            title = stringResource(R.string.swap_maintenance_title),
            description = stringResource(R.string.swap_maintenance_summary),
            onClick = { if (!busy) onOpen() },
        )
    }
}

@Composable
private fun ZramDeviceStatsCard(devices: List<ZramDeviceStats>) {
    if (devices.isEmpty()) return
    SettingsGroupHeader(stringResource(R.string.swap_zram_devices_title))
    SettingsGroup {
        devices.forEachIndexed { index, device ->
            if (index > 0) SettingsGroupDivider()
            SettingsInfoItem(
                title = device.device,
                value = "${device.logicalUsedKB / 1024L}MB / ${device.logicalSizeKB / 1024L}MB · ${device.statsSource}",
            )
            SettingsGroupDivider()
            SettingsInfoItem("compressed", formatBytes(device.compressedDataBytes))
            SettingsGroupDivider()
            SettingsInfoItem("zram_ram", formatBytes(device.memoryUsedBytes))
            device.backingDevice.takeIf { it.isNotBlank() }?.let {
                SettingsGroupDivider()
                SettingsInfoItem("backing", it)
            }
        }
    }
}

@Composable
private fun LegacyLmkCard(supported: Boolean, minfree: String) {
    if (!supported) return
    SettingsGroupHeader(stringResource(R.string.swap_legacy_lmk_title))
    SettingsGroup {
        SettingsInfoItem(
            title = "minfree",
            value = minfree.ifBlank { stringResource(R.string.swap_parameter_unsupported) },
        )
    }
}

@Composable
private fun MemoryCapabilitiesCard(
    loopSwapSupported: Boolean,
    oplusSwappinessSupported: Boolean,
    sceneControllerDetected: Boolean,
) {
    SettingsGroupHeader(stringResource(R.string.swap_capabilities_title))
    SettingsGroup {
        SettingsInfoItem("loop_swap", supportedLabel(loopSwapSupported))
        SettingsGroupDivider()
        SettingsInfoItem("oplus_hybridswap", supportedLabel(oplusSwappinessSupported))
        if (sceneControllerDetected) {
            SettingsGroupDivider()
            SettingsInfoItem("scene_controller", stringResource(R.string.swap_scene_controller_detected))
        }
    }
}

@Composable
private fun MemoryProfileCard(
    autoApply: Boolean,
    busy: Boolean,
    onSaveAndApply: () -> Unit,
    onAutoApplyChange: (Boolean) -> Unit,
) {
    SettingsGroupHeader(stringResource(R.string.swap_profile_title))
    SettingsGroup {
        SettingsToggleItem(
            checked = autoApply,
            title = stringResource(R.string.swap_autostart_title),
            description = stringResource(R.string.swap_autostart_summary),
            onToggle = { if (!busy) onAutoApplyChange(!autoApply) },
        )
        SettingsGroupDivider()
        BasicComponent(
            title = stringResource(R.string.swap_save_and_apply_profile),
            summary = stringResource(R.string.swap_profile_summary),
            onClick = { if (!busy) onSaveAndApply() },
            enabled = !busy,
        )
    }
}

@Composable
private fun VmSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    display: String,
) {
    Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Slider(value = value, onValueChange = onValueChange, valueRange = range, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        ValueChip(text = display)
    }
}

@Composable
private fun SizeSelectionHeader(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ValueChip(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.primary,
    )
}

private fun swapUsageRatio(used: Long, total: Long): Float {
    if (used <= 0 || total <= 0) return 0f
    return (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

private fun priorityLabel(priority: Int): String = when (priority) {
    5 -> "High (5)"
    0 -> "Medium (0)"
    else -> "Low (-2)"
}

@Composable
private fun supportedLabel(supported: Boolean): String = stringResource(
    if (supported) R.string.swap_capability_supported else R.string.swap_parameter_unsupported,
)

private fun unavailableOrValue(value: Int): String = if (value < 0) "—" else value.toString()

private fun swapUnitsToMb(units: Float): Int = units.roundToInt().coerceIn(1, MaxSwapUnits) * SwapSizeUnitMb

@Composable
private fun CircleChart(ratio: Float, modifier: Modifier = Modifier) {
    val primary = MiuixTheme.colorScheme.primary
    val trackColor = usageRingTrackColor
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
        val arcSize = Size(size.width, size.height)
        drawArc(trackColor, 0f, 360f, false, style = stroke, topLeft = Offset.Zero, size = arcSize)
        drawArc(primary, -90f, 360f * ratio, false, style = stroke, topLeft = Offset.Zero, size = arcSize)
    }
}

@Composable
private fun SwapCard(
    title: String,
    ratio: Float,
    state: String,
    size: String,
    note: String,
    primaryAction: String,
    secondaryAction: String,
    primaryEnabled: Boolean,
    secondaryEnabled: Boolean,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(98.dp).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(70.dp), contentAlignment = Alignment.Center) {
                CircleChart(ratio, Modifier.size(70.dp))
                Text(
                    "${(ratio * 100).toInt()}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                Text(state, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                Text(
                    size,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
                Text(
                    note,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
            Column {
                Card(
                    modifier = Modifier.padding(vertical = 2.dp),
                    onClick = { if (primaryEnabled) onPrimary() },
                    pressFeedbackType = PressFeedbackType.Sink,
                ) {
                    Text(
                        primaryAction,
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 12.sp,
                        color = if (primaryEnabled) Color.Unspecified else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Card(
                    modifier = Modifier.padding(vertical = 2.dp),
                    onClick = { if (secondaryEnabled) onSecondary() },
                    pressFeedbackType = PressFeedbackType.Sink,
                ) {
                    Text(
                        secondaryAction,
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 12.sp,
                        color = if (secondaryEnabled) Color.Unspecified else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZramCard(
    title: String,
    ratio: Float,
    state: String,
    algo: String,
    size: String,
    note: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(98.dp).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(70.dp), contentAlignment = Alignment.Center) {
                CircleChart(ratio, Modifier.size(70.dp))
                Text(
                    "${(ratio * 100).toInt()}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                Text(state, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                Text(
                    "$size · $algo",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
                Text(
                    note,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
            Card(Modifier.padding(vertical = 2.dp), onClick = { if (actionEnabled) onAction() }) {
                Text(
                    stringResource(R.string.swap_adjust),
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    color = if (actionEnabled) Color.Unspecified else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun SummaryGroup(title: String, lines: List<String>) {
    val entries = lines.mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isBlank()) return@mapNotNull null

        val separator = trimmed.indexOf(':').takeIf { it > 0 }
            ?: trimmed.indexOfFirst { it.isWhitespace() }.takeIf { it > 0 }
            ?: return@mapNotNull trimmed to ""
        trimmed.substring(0, separator) to trimmed.substring(separator + 1).trim()
    }

    SettingsGroupHeader(title)
    SettingsGroup {
        if (entries.isEmpty()) {
            SettingsInfoItem(title = title, value = stringResource(R.string.common_no_data))
        } else {
            entries.forEachIndexed { index, (label, value) ->
                if (index > 0) SettingsGroupDivider()
                SettingsInfoItem(title = label, value = value)
            }
        }
    }
}
