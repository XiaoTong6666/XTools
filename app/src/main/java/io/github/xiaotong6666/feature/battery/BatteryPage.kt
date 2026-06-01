package io.github.xiaotong6666.feature.battery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaotong6666.tools.R
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun BatteryPage() {
    val batState = rememberBatteryState()
    val context = LocalContext.current
    var qcLimit by remember(batState.qcLimit) {
        mutableFloatStateOf(batState.qcLimit.coerceAtLeast(0).toFloat())
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(stringResource(R.string.battery_realtime_status), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.battery_capacity_format, batState.capacity), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.battery_temperature_format, batState.temperature), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text(stringResource(R.string.battery_health_format, batState.health), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.battery_status_format, batState.status), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.battery_charge_type_format, batState.chargeType), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text(
                            stringResource(
                                R.string.battery_charge_limit_format,
                                if (batState.qcLimit > 0) "${batState.qcLimit}mA" else "—",
                            ),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(stringResource(R.string.battery_telemetry), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                TelemetryRow(stringResource(R.string.battery_current), if (batState.currentNowUa != 0L) "${batState.currentNowUa} uA" else "—")
                TelemetryRow(stringResource(R.string.battery_voltage), if (batState.voltageNowUv != 0L) "${batState.voltageNowUv} uV" else "—")
                TelemetryRow(stringResource(R.string.battery_power), if (batState.powerMw >= 0f) "${batState.powerMw} mW" else "—")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(stringResource(R.string.battery_charge_capabilities), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                TelemetryRow(stringResource(R.string.battery_pd_supported), stringResource(if (batState.pdSupported) R.string.common_yes else R.string.common_no))
                TelemetryRow(stringResource(R.string.battery_pd_active), stringResource(if (batState.pdActive) R.string.common_yes else R.string.common_no))
                TelemetryRow(stringResource(R.string.battery_bypass_charge), stringResource(if (batState.bpSupport) R.string.battery_available_to_try else R.string.battery_unavailable))
                TelemetryRow(stringResource(R.string.battery_step_charge), stringResource(if (batState.stepChargeSupport) R.string.battery_connected else R.string.battery_not_connected))
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(stringResource(R.string.battery_charge_current_control), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Slider(value = qcLimit, onValueChange = { qcLimit = it }, valueRange = 0f..12000f, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Surface(color = MiuixTheme.colorScheme.surfaceContainerHighest) {
                        Text("${qcLimit.toInt()}", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(text = stringResource(R.string.battery_apply_charge_limit), onClick = { applyChargeCurrentLimit(qcLimit.toInt(), context) })
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(stringResource(R.string.battery_charge_toggle), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(Modifier.weight(1f), onClick = { applyChargeEnabled(false) }, pressFeedbackType = PressFeedbackType.Sink) {
                        Text(stringResource(R.string.battery_stop_charging), Modifier.padding(12.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.error)
                    }
                    Card(Modifier.weight(1f), onClick = { applyChargeEnabled(true) }, pressFeedbackType = PressFeedbackType.Sink) {
                        Text(stringResource(R.string.battery_resume_charging), Modifier.padding(12.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(stringResource(R.string.battery_daemon_raw_info), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    batState.batteryInfo.ifBlank { stringResource(R.string.common_no_data) },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
    }
}
