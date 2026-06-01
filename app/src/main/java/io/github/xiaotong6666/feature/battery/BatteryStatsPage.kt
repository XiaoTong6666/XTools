package io.github.xiaotong6666.feature.battery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaotong6666.tools.R
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BatteryStatsPage() {
    val stats = rememberChargeStatsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    GaugeCard(stringResource(R.string.battery_stats_peak_current), "${stats.peakCurrentMa} mA", MiuixTheme.colorScheme.primary)
                    GaugeCard(stringResource(R.string.battery_stats_peak_power), formatFloatValue(stats.peakPowerMw, "mW"), MiuixTheme.colorScheme.error)
                    GaugeCard(stringResource(R.string.battery_stats_peak_temperature), formatFloatValue(stats.peakTemperature, "°C"), MiuixTheme.colorScheme.error)
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.battery_stats_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.battery_stats_summary), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(16.dp))
                    StatRow(stringResource(R.string.battery_stats_session_status), stringResource(if (stats.sessionActive) R.string.battery_stats_collecting else R.string.battery_stats_idle))
                    StatRow(stringResource(R.string.battery_stats_battery_status), stats.status)
                    StatRow(stringResource(R.string.battery_stats_charge_type), stats.chargeType)
                    StatRow(stringResource(R.string.battery_stats_sample_count), stats.sampleCount.toString())
                    StatRow(stringResource(R.string.battery_stats_session_duration), formatDuration(stats.sessionElapsedMs))
                    StatRow(stringResource(R.string.battery_stats_peak_charge_limit), if (stats.peakChargeLimitMa >= 0) "${stats.peakChargeLimitMa} mA" else "—")
                    StatRow(stringResource(R.string.battery_stats_peak_voltage), if (stats.peakVoltageMv >= 0) "${stats.peakVoltageMv} mV" else "—")
                }
            }
        }

        if (stats.samples.isNotEmpty()) {
            item {
                SmallTitle(text = stringResource(R.string.battery_stats_recent_samples), modifier = Modifier.padding(top = 4.dp))
            }
            items(stats.samples.takeLast(20).reversed()) { sample ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(formatDuration(sample.timestampMs), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.battery_capacity_format, sample.capacity), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${sample.currentMa} mA", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
                            Text(formatFloatValue(sample.temperature, "°C"), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        }

        item {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    resetChargeStats()
                },
                text = stringResource(R.string.battery_stats_clear),
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

private fun formatFloatValue(value: Float, unit: String): String {
    if (value < 0f) return "—"
    return "%.1f %s".format(value, unit)
}

@Composable
private fun GaugeCard(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
    }
}
