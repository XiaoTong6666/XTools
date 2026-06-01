package io.github.xiaotong6666.feature.home

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xiaotong6666.feature.home.components.cards.HomeDashboardRow
import io.github.xiaotong6666.feature.home.components.widgets.HomePageCardSpacing
import io.github.xiaotong6666.feature.home.components.widgets.HomePageHorizontalPadding
import io.github.xiaotong6666.ui.navigation.FeaturePage

@Composable
fun HomePage(
    state: HomePageState,
    modifier: Modifier = Modifier,
    onNavigate: (FeaturePage) -> Unit = {},
    onClearRam: () -> Unit = {},
    onCompactMemory: () -> Unit = {},
    onPowerMode: (String) -> Unit = {},
) {
    SideEffect {
        Log.d(
            "HomePageRender",
            "cpuLoad=${state.cpuLoad}, cpuCores=${state.cpuCores.size}, firstCoreFreq=${state.cpuCores.firstOrNull()?.curFreq}, " +
                "processes=${state.processes.size}, firstProcess=${state.processes.firstOrNull()?.name}, " +
                "battery=${state.battery.level}/${state.battery.temp}, memTotal=${state.memory.totalKB}, memAvail=${state.memory.availKB}, swap=${state.memory.swapTotalKB}",
        )
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = HomePageHorizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(HomePageCardSpacing),
    ) {
        item {
            HomeDashboardRow(
                state = state,
                onNavigate = onNavigate,
                onClearRam = onClearRam,
                onCompactMemory = onCompactMemory,
            )
        }
        item {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
