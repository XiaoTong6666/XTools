package io.github.xiaotong6666.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.xiaotong6666.feature.battery.BatteryPage
import io.github.xiaotong6666.feature.battery.BatteryStatsPage
import io.github.xiaotong6666.feature.cpu.CpuControlPage
import io.github.xiaotong6666.feature.legacy.LegacyFeaturePage
import io.github.xiaotong6666.feature.legacy.SettingsPage
import io.github.xiaotong6666.feature.process.ProcessPage
import io.github.xiaotong6666.feature.swap.SwapPage
import io.github.xiaotong6666.uihelper.chrome.DetailPageHost

@Composable
fun FeaturePageScreen(page: FeaturePage, onBack: () -> Unit) {
    val pageTitle = stringResource(page.titleRes)

    DetailPageHost(
        title = pageTitle,
        onBack = onBack,
    ) { contentPadding, scrollModifier ->
        Box(
            modifier = Modifier
                .then(scrollModifier)
                .padding(contentPadding),
        ) {
            when (page) {
                FeaturePage.Tuner -> LegacyFeaturePage(pageTitle)
                FeaturePage.CpuControl -> CpuControlPage()
                FeaturePage.Swap -> SwapPage()
                FeaturePage.ChargeControl -> BatteryPage()
                FeaturePage.ChargeStat -> BatteryStatsPage()
                FeaturePage.PowerStat -> LegacyFeaturePage(pageTitle)
                FeaturePage.Process -> ProcessPage()
                FeaturePage.PerfBench -> LegacyFeaturePage(pageTitle)
                FeaturePage.PowerBench -> LegacyFeaturePage(pageTitle)
                FeaturePage.FpsSessions -> LegacyFeaturePage(pageTitle)
                FeaturePage.ScreenTest -> LegacyFeaturePage(pageTitle)
                FeaturePage.AppTools -> LegacyFeaturePage(pageTitle)
                FeaturePage.Applications -> LegacyFeaturePage(pageTitle)
                FeaturePage.AutoClick -> LegacyFeaturePage(pageTitle)
                FeaturePage.Magisk -> LegacyFeaturePage(pageTitle)
                FeaturePage.Addin -> LegacyFeaturePage(pageTitle)
                FeaturePage.OtherSettings -> SettingsPage()
                FeaturePage.Backup -> LegacyFeaturePage(pageTitle)
            }
        }
    }
}
