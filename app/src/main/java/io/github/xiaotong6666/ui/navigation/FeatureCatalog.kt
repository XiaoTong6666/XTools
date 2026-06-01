package io.github.xiaotong6666.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xiaotong6666.tools.R
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class FeatureItem(
    @param:StringRes val summaryRes: Int,
    @param:StringRes val titleRes: Int,
    val icon: ImageVector,
    val page: FeaturePage? = null,
    val tint: Color = Color.Unspecified,
)

data class FeatureCategory(
    @param:StringRes val titleRes: Int,
    val items: List<FeatureItem>,
)

@Composable
fun FeatureCatalog(
    modifier: Modifier = Modifier,
    onNavigate: (FeaturePage) -> Unit,
) {
    val categories = remember {
        listOf(
            FeatureCategory(
                R.string.feature_category_performance,
                listOf(
                    FeatureItem(R.string.feature_summary_cpu, FeaturePage.CpuControl.titleRes, Icons.Filled.Memory, FeaturePage.CpuControl, tint = Color(0xFF2196F3)),
                    FeatureItem(R.string.feature_summary_swap, FeaturePage.Swap.titleRes, Icons.Filled.Storage, FeaturePage.Swap, tint = Color(0xFFFF9800)),
                    FeatureItem(R.string.feature_summary_process, FeaturePage.Process.titleRes, Icons.Filled.GridView, FeaturePage.Process, tint = Color(0xFF795548)),
                    FeatureItem(R.string.feature_summary_perf_bench, FeaturePage.PerfBench.titleRes, Icons.Filled.Speed, FeaturePage.PerfBench, tint = Color(0xFFF44336)),
                    FeatureItem(R.string.feature_summary_fps, FeaturePage.FpsSessions.titleRes, Icons.Filled.Monitor, FeaturePage.FpsSessions, tint = Color(0xFF00BCD4)),
                    FeatureItem(R.string.feature_summary_screen, FeaturePage.ScreenTest.titleRes, Icons.Filled.Smartphone, FeaturePage.ScreenTest, tint = Color(0xFF9E9E9E)),
                ),
            ),
            FeatureCategory(
                R.string.feature_category_power,
                listOf(
                    FeatureItem(R.string.feature_summary_charge_ctl, FeaturePage.ChargeControl.titleRes, Icons.Filled.BatteryChargingFull, FeaturePage.ChargeControl, tint = Color(0xFF4CAF50)),
                    FeatureItem(R.string.feature_summary_charge_stat, FeaturePage.ChargeStat.titleRes, Icons.Filled.Bolt, FeaturePage.ChargeStat, tint = Color(0xFFFF9800)),
                    FeatureItem(R.string.feature_summary_power_stat, FeaturePage.PowerStat.titleRes, Icons.Filled.Battery5Bar, FeaturePage.PowerStat, tint = Color(0xFF2196F3)),
                    FeatureItem(R.string.feature_summary_power_bench, FeaturePage.PowerBench.titleRes, Icons.Filled.ElectricBolt, FeaturePage.PowerBench, tint = Color(0xFFFF5722)),
                ),
            ),
            FeatureCategory(
                R.string.feature_category_apps,
                listOf(
                    FeatureItem(R.string.feature_summary_app_tools, FeaturePage.AppTools.titleRes, Icons.Filled.Apps, FeaturePage.AppTools, tint = Color(0xFF3F51B5)),
                    FeatureItem(R.string.feature_summary_apps, FeaturePage.Applications.titleRes, Icons.Filled.Widgets, FeaturePage.Applications, tint = Color(0xFF009688)),
                ),
            ),
            FeatureCategory(
                R.string.feature_category_tools,
                listOf(
                    FeatureItem(R.string.feature_summary_magisk, FeaturePage.Magisk.titleRes, Icons.Filled.Build, FeaturePage.Magisk, tint = Color(0xFF1A73E8)),
                    FeatureItem(R.string.feature_summary_addin, FeaturePage.Addin.titleRes, Icons.Filled.Extension, FeaturePage.Addin, tint = Color(0xFF673AB7)),
                ),
            ),
            FeatureCategory(
                R.string.feature_category_scenes,
                listOf(
                    FeatureItem(R.string.feature_summary_backup, FeaturePage.Backup.titleRes, Icons.Filled.Backup, FeaturePage.Backup, tint = Color(0xFF607D8B)),
                ),
            ),
            FeatureCategory(
                R.string.feature_category_settings,
                listOf(
                    FeatureItem(R.string.feature_summary_settings, FeaturePage.OtherSettings.titleRes, Icons.Filled.Tune, FeaturePage.OtherSettings, tint = Color(0xFF546E7A)),
                ),
            ),
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        categories.forEach { category ->
            item { SmallTitle(text = stringResource(category.titleRes)) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    category.items.forEachIndexed { index, item ->
                        val title = stringResource(item.titleRes)
                        val iconContainerColor = if (item.tint != Color.Unspecified) item.tint else MiuixTheme.colorScheme.primary
                        ArrowPreference(
                            title = title,
                            summary = stringResource(item.summaryRes),
                            startAction = {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 10.dp)
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(iconContainerColor),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = title,
                                        modifier = Modifier.size(18.dp),
                                        tint = Color.White,
                                    )
                                }
                            },
                            onClick = {
                                item.page?.let { onNavigate(it) }
                            },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
