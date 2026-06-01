package io.github.xiaotong6666.ui.navigation

import androidx.annotation.StringRes
import io.github.xiaotong6666.tools.R

enum class FeaturePage(
    @param:StringRes val titleRes: Int,
) {
    CpuControl(R.string.feature_title_cpu_control),
    Swap(R.string.feature_title_swap),
    ChargeControl(R.string.feature_title_charge_control),
    ChargeStat(R.string.feature_title_charge_stat),
    PowerStat(R.string.feature_title_power_stat),
    Process(R.string.feature_title_process),
    PerfBench(R.string.feature_title_perf_bench),
    PowerBench(R.string.feature_title_power_bench),
    FpsSessions(R.string.feature_title_fps_sessions),
    ScreenTest(R.string.feature_title_screen_test),
    AppTools(R.string.feature_title_app_tools),
    Applications(R.string.feature_title_applications),
    AutoClick(R.string.feature_title_auto_click),
    Magisk(R.string.feature_title_magisk),
    Addin(R.string.feature_title_addin),
    OtherSettings(R.string.feature_title_other_settings),
    Tuner(R.string.feature_title_tuner),
    Backup(R.string.feature_title_backup),
}
