package io.github.xiaotong6666.feature.tuner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaotong6666.tools.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun TunerPage(
    modifier: Modifier = Modifier,
    dynamicEnabled: Boolean = true,
    onDynamicToggle: (Boolean) -> Unit = {},
    pedestalEnabled: Boolean = false,
    onPedestalToggle: (Boolean) -> Unit = {},
    extremeEnabled: Boolean = false,
    onExtremeToggle: (Boolean) -> Unit = {},
    configAuthor: String = "Tools",
    configState: String = "",
    onConfigClick: () -> Unit = {},
    onPowerSave: () -> Unit = {},
    onBalance: () -> Unit = {},
    onPerformance: () -> Unit = {},
    onFast: () -> Unit = {},
    onAppsTools: () -> Unit = {},
    onAutoClick: () -> Unit = {},
    accessibilityActive: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        if (!accessibilityActive) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFFFF3E0),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = Color(0xFFFF9800))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.tuner_accessibility_inactive),
                        fontSize = 12.sp,
                        color = Color(0xFF795548),
                    )
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            onClick = {},
            pressFeedbackType = PressFeedbackType.Sink,
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SportsEsports, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tuner_game_list), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Text("→", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MiuixTheme.colorScheme.surfaceContainer,
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(configAuthor, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.SwapHoriz,
                        stringResource(R.string.tuner_switch_config),
                        Modifier.size(18.dp).clickable { onConfigClick() },
                        tint = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.tuner_dynamic_control),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Switch(checked = dynamicEnabled, onCheckedChange = onDynamicToggle)
                }

                if (configState.isNotEmpty()) {
                    Text(
                        configState,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    ModeButton(
                        stringResource(R.string.tuner_mode_power_save),
                        Color(0xFF4CAF50),
                        Modifier.weight(1f),
                        onPowerSave,
                    )
                    ModeButton(
                        stringResource(R.string.tuner_mode_balance),
                        Color(0xFF2196F3),
                        Modifier.weight(1f),
                        onBalance,
                    )
                    ModeButton(
                        stringResource(R.string.tuner_mode_performance),
                        Color(0xFFFF9800),
                        Modifier.weight(1f),
                        onPerformance,
                    )
                    ModeButton(
                        stringResource(R.string.tuner_mode_fast),
                        Color(0xFFF44336),
                        Modifier.weight(1f),
                        onFast,
                    )
                }

                if (dynamicEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .padding(vertical = 8.dp)
                            .background(MiuixTheme.colorScheme.surfaceContainerHighest),
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) {
                        Text(
                            stringResource(R.string.tuner_pedestal_mode),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = pedestalEnabled, onCheckedChange = onPedestalToggle)
                    }
                }
            }
        }

        NavCard(
            Icons.Filled.Dangerous,
            Color(0xFFFF5252),
            stringResource(R.string.tuner_extreme_performance),
            stringResource(R.string.tuner_extreme_performance_summary),
            actionSwitch = { Switch(checked = extremeEnabled, onCheckedChange = onExtremeToggle) },
        )

        NavCard(
            Icons.Filled.AccountBox,
            MiuixTheme.colorScheme.primary,
            stringResource(R.string.tuner_app_scenarios),
            stringResource(R.string.tuner_app_scenarios_summary),
            showArrow = true,
            onClick = onAppsTools,
        )

        NavCard(
            Icons.Filled.TouchApp,
            MiuixTheme.colorScheme.primary,
            stringResource(R.string.feature_title_auto_click),
            stringResource(R.string.tuner_auto_click_summary),
            showArrow = true,
            onClick = onAutoClick,
        )

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ModeButton(
    label: String,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = { onClick() },
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
        }
    }
}

@Composable
private fun NavCard(
    icon: ImageVector,
    iconBg: Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    showArrow: Boolean = false,
    actionSwitch: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    Surface(
        onClick = { onClick() },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MiuixTheme.colorScheme.surfaceContainer,
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(22.dp), tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (actionSwitch != null) {
                actionSwitch()
            } else if (showArrow) {
                Icon(Icons.Filled.ChevronRight, null, Modifier.size(22.dp), tint = Color(0xFF808080))
            }
        }
    }
}
