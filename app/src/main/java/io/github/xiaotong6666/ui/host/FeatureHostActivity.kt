package io.github.xiaotong6666.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.xiaotong6666.feature.cpu.CpuControlPage
import io.github.xiaotong6666.feature.legacy.LegacyFeaturePage
import io.github.xiaotong6666.feature.legacy.PowerTilePage
import io.github.xiaotong6666.tools.R
import io.github.xiaotong6666.ui.navigation.FeaturePage
import io.github.xiaotong6666.ui.navigation.FeaturePageScreen
import io.github.xiaotong6666.ui.theme.Theme
import io.github.xiaotong6666.uihelper.chrome.DetailPageHost

class FeatureHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val route = intent.getStringExtra(EXTRA_ROUTE).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        setContent {
            Theme(darkTheme = isSystemInDarkTheme()) {
                ComposeFeatureScreen(
                    route = route,
                    title = title,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "compose_route"
        const val EXTRA_TITLE = "compose_title"

        const val ROUTE_CHARGE_CURVE = "charge_curve"
        const val ROUTE_BATTERY = "battery"
        const val ROUTE_CPU_MODES = "cpu_modes"
        const val ROUTE_HIDDEN_APPS = "hidden_apps"
        const val ROUTE_SYSTEM_TOOLS = "system_tools"
        const val ROUTE_APP_TOOL_DETAIL = "app_tool_detail"
        const val ROUTE_POWER_TILE = "power_tile"
        const val ROUTE_IMG_TOOLS = "img_tools"

        fun createIntent(context: Context, route: String, title: String? = null): Intent = Intent(context, FeatureHostActivity::class.java).apply {
            putExtra(EXTRA_ROUTE, route)
            if (!title.isNullOrBlank()) {
                putExtra(EXTRA_TITLE, title)
            }
        }

        fun featureRoute(page: FeaturePage): String = "feature:${page.name}"
    }
}

@Composable
private fun ComposeFeatureScreen(
    route: String,
    title: String,
    onBack: () -> Unit,
) {
    if (route.startsWith("feature:")) {
        val feature = FeaturePage.valueOf(route.removePrefix("feature:"))
        FeaturePageScreen(page = feature, onBack = onBack)
        return
    }

    val pageTitle = title.ifBlank {
        stringResource(
            when (route) {
                FeatureHostActivity.ROUTE_CHARGE_CURVE -> R.string.feature_title_charge_stat
                FeatureHostActivity.ROUTE_BATTERY -> R.string.feature_title_charge_control
                FeatureHostActivity.ROUTE_CPU_MODES -> R.string.feature_title_tuner
                FeatureHostActivity.ROUTE_HIDDEN_APPS -> R.string.route_title_hidden_apps
                FeatureHostActivity.ROUTE_SYSTEM_TOOLS -> R.string.route_title_system_tools
                FeatureHostActivity.ROUTE_POWER_TILE -> R.string.route_title_power_mode_tile
                FeatureHostActivity.ROUTE_APP_TOOL_DETAIL -> R.string.feature_title_app_tools
                FeatureHostActivity.ROUTE_IMG_TOOLS -> R.string.route_title_img_tools
                else -> R.string.app_name_short
            },
        )
    }

    DetailPageHost(
        title = pageTitle,
        onBack = onBack,
    ) { contentPadding, scrollModifier ->
        Box(
            modifier = Modifier
                .then(scrollModifier)
                .padding(contentPadding),
        ) {
            when (route) {
                FeatureHostActivity.ROUTE_CHARGE_CURVE -> LegacyFeaturePage(pageTitle)
                FeatureHostActivity.ROUTE_BATTERY -> LegacyFeaturePage(pageTitle)
                FeatureHostActivity.ROUTE_CPU_MODES -> CpuControlPage()
                FeatureHostActivity.ROUTE_HIDDEN_APPS -> LegacyFeaturePage(pageTitle)
                FeatureHostActivity.ROUTE_SYSTEM_TOOLS -> LegacyFeaturePage(pageTitle)
                FeatureHostActivity.ROUTE_POWER_TILE -> PowerTilePage()
                FeatureHostActivity.ROUTE_APP_TOOL_DETAIL -> LegacyFeaturePage(pageTitle)
                FeatureHostActivity.ROUTE_IMG_TOOLS -> LegacyFeaturePage(pageTitle)
            }
        }
    }
}
