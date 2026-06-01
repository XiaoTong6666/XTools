package io.github.xiaotong6666.feature.battery

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.xiaotong6666.core.bridge.executeToolsCommand
import io.github.xiaotong6666.core.bridge.readNativeChargeStats
import io.github.xiaotong6666.core.bridge.resetNativeChargeStats
import io.github.xiaotong6666.core.native.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class BatteryState(
    val capacity: Int = -1,
    val temperature: Float = -1f,
    val status: String = "Unknown",
    val health: String = "—",
    val chargeType: String = "—",
    val qcSupport: Boolean = false,
    val qcLimit: Int = -1,
    val bpSupport: Boolean = false,
    val stepChargeSupport: Boolean = false,
    val pdSupported: Boolean = false,
    val pdAllowed: Boolean = false,
    val pdActive: Boolean = false,
    val currentNowUa: Long = 0L,
    val voltageNowUv: Long = 0L,
    val powerMw: Float = -1f,
    val batteryInfo: String = "",
    val usbInfo: String = "",
    val loading: Boolean = true,
)

data class ChargeStatsState(
    val sessionActive: Boolean = false,
    val status: String = "—",
    val chargeType: String = "—",
    val sampleCount: Int = 0,
    val sessionElapsedMs: Long = 0L,
    val peakCurrentMa: Int = -1,
    val peakTemperature: Float = -1f,
    val peakPowerMw: Float = -1f,
    val peakVoltageMv: Int = -1,
    val peakChargeLimitMa: Int = -1,
    val samples: List<ChargeStatsSampleData> = emptyList(),
    val loading: Boolean = true,
)

data class ChargeStatsSampleData(
    val timestampMs: Long = 0L,
    val capacity: Int = -1,
    val currentMa: Int = -1,
    val temperature: Float = -1f,
    val powerMw: Float = -1f,
)

@Composable
fun rememberBatteryState(): BatteryState {
    var state by remember { mutableStateOf(BatteryState()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (true) {
                try {
                    val json = JSONObject(Core.executeCommand("battery-info"))
                    state = BatteryState(
                        capacity = json.optInt("capacity", -1),
                        temperature = json.optInt("temperature", -1).let { if (it >= 0) it / 10f else -1f },
                        status = json.optString("status", "—"),
                        health = json.optString("health", "—"),
                        chargeType = json.optString("charge_type", "—"),
                        qcSupport = json.optLong("charge_current_max", -1L) >= 0L,
                        qcLimit = json.optLong("charge_current_max", -1L)
                            .takeIf { it >= 0L }
                            ?.div(1000L)
                            ?.toInt()
                            ?: -1,
                        bpSupport = true,
                        stepChargeSupport = false,
                        pdSupported = json.optString("charge_type", "").isNotBlank(),
                        pdAllowed = false,
                        pdActive = json.optString("charge_type", "").contains("PD", ignoreCase = true),
                        currentNowUa = json.optLong("current_now", 0L),
                        voltageNowUv = json.optLong("voltage_now", 0L),
                        powerMw = json.optDouble("power", -1.0).toFloat(),
                        batteryInfo = json.toString(2),
                        usbInfo = json.optString("charge_type", ""),
                        loading = false,
                    )
                } catch (_: Exception) {
                    state = state.copy(loading = false)
                }
                delay(3000)
            }
        }
    }
    return state
}

fun applyChargeCurrentLimit(limit: Int, context: Context) {
    CoroutineScope(Dispatchers.IO).launch {
        executeToolsCommand(
            "set-charge-current",
            JSONObject().put("ua", limit.toLong() * 1000L),
        )
    }
}

fun applyChargeEnabled(enable: Boolean) {
    CoroutineScope(Dispatchers.IO).launch {
        executeToolsCommand(
            "battery-charge-enable",
            JSONObject().put("enable", enable),
        )
    }
}

@Composable
fun rememberChargeStatsState(): ChargeStatsState {
    var state by remember { mutableStateOf(ChargeStatsState()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (true) {
                try {
                    val native = readNativeChargeStats()
                    state = ChargeStatsState(
                        sessionActive = native.sessionActive,
                        status = native.status,
                        chargeType = native.chargeType,
                        sampleCount = native.sampleCount,
                        sessionElapsedMs = native.sessionElapsedMs,
                        peakCurrentMa = native.peakCurrentMa,
                        peakTemperature = native.peakTemperatureDeciC.let { if (it >= 0) it / 10f else -1f },
                        peakPowerMw = native.peakPowerMw,
                        peakVoltageMv = native.peakVoltageMv,
                        peakChargeLimitMa = native.peakChargeCurrentLimitUa.let { if (it >= 0) it / 1000 else -1 },
                        samples = native.samples.map {
                            ChargeStatsSampleData(
                                timestampMs = it.timestampMs,
                                capacity = it.capacity,
                                currentMa = it.currentMa,
                                temperature = if (it.temperatureDeciC >= 0) it.temperatureDeciC / 10f else -1f,
                                powerMw = it.powerMw,
                            )
                        },
                        loading = false,
                    )
                } catch (_: Exception) {
                    state = state.copy(loading = false)
                }
                delay(3000)
            }
        }
    }
    return state
}

fun resetChargeStats() {
    CoroutineScope(Dispatchers.IO).launch {
        resetNativeChargeStats()
    }
}
