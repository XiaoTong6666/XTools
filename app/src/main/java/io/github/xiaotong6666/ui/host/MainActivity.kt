package io.github.xiaotong6666.ui.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplayTransitionEffects
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.xiaotong6666.core.bridge.executeToolsCommand
import io.github.xiaotong6666.feature.home.HomePage
import io.github.xiaotong6666.feature.home.rememberHomePageState
import io.github.xiaotong6666.feature.tuner.TunerPage
import io.github.xiaotong6666.tools.R
import io.github.xiaotong6666.ui.navigation.FeatureCatalog
import io.github.xiaotong6666.ui.navigation.FeaturePage
import io.github.xiaotong6666.ui.navigation.FeaturePageScreen
import io.github.xiaotong6666.ui.theme.Theme
import io.github.xiaotong6666.uihelper.chrome.AdaptiveNavigationShell
import io.github.xiaotong6666.uihelper.chrome.AppChromeSpec
import io.github.xiaotong6666.uihelper.chrome.NavigationShellItem
import io.github.xiaotong6666.uihelper.chrome.NavigationShellTopBarMode
import io.github.xiaotong6666.uihelper.chrome.PageHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.IconButton as MaterialIconButton
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton

private enum class Tab { Home, Tuner, More }

private sealed interface MainRoute {
    data object Root : MainRoute
    data class Feature(val page: FeaturePage) : MainRoute
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            Theme(darkTheme = isSystemInDarkTheme()) {
                MainApp()
            }
        }
    }

    @Composable
    private fun MainApp() {
        val backStack = remember { mutableStateListOf<MainRoute>(MainRoute.Root) }
        var selectedTabIndex by rememberSaveable { mutableIntStateOf(Tab.Home.ordinal) }
        val activeTab = Tab.entries[selectedTabIndex.coerceIn(0, Tab.entries.lastIndex)]
        val isHomeVisible = backStack.lastOrNull() == MainRoute.Root && activeTab == Tab.Home
        val homeState = rememberHomePageState(this@MainActivity, active = isHomeVisible)
        val coroutineScope = rememberCoroutineScope()
        val transitionEffects = remember {
            NavDisplayTransitionEffects(
                enableCornerClip = true,
                dimAmount = 0.5f,
                blockInputDuringTransition = true,
                popDirectionFollowsSwipeEdge = false,
            )
        }
        val openFeaturePage: (FeaturePage) -> Unit = { page ->
            if (backStack.lastOrNull() != MainRoute.Feature(page)) {
                backStack.add(MainRoute.Feature(page))
            }
        }
        val onClearRam = {
            coroutineScope.launch(Dispatchers.IO) {
                executeToolsCommand("drop-caches")
            }
            Unit
        }
        val onCompactMemory = {
            coroutineScope.launch(Dispatchers.IO) {
                executeToolsCommand("compact-memory")
            }
            Unit
        }

        val isPagerBackHandlerEnabled = backStack.lastOrNull() == MainRoute.Root && activeTab != Tab.Home
        val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = navEventState,
            isBackEnabled = isPagerBackHandlerEnabled,
            onBackCompleted = {
                selectedTabIndex = Tab.Home.ordinal
            },
        )

        val mainEntryProvider = entryProvider<MainRoute> {
            entry<MainRoute.Root> {
                AdaptiveNavigationShell(
                    items = listOf(
                        NavigationShellItem(stringResource(R.string.ui_nav_home), Icons.Filled.Home),
                        NavigationShellItem(stringResource(R.string.ui_nav_tuner), Icons.Filled.Tune),
                        NavigationShellItem(stringResource(R.string.ui_nav_features), Icons.AutoMirrored.Filled.List),
                    ),
                    selectedIndex = selectedTabIndex,
                    onSelectedIndexChange = { selectedTabIndex = it },
                    topBarMode = NavigationShellTopBarMode.Collapsed,
                ) { pageIndex, contentPadding, isCurrentPage, pageModifier ->
                    PageHost(
                        enabled = isCurrentPage,
                        spec = {
                            AppChromeSpec(
                                materialActions = { MainMaterialActions(openFeaturePage) },
                                miuixActions = { MainMiuixActions(openFeaturePage) },
                            )
                        },
                    ) {
                        Box(
                            modifier = pageModifier
                                .padding(contentPadding)
                                .fillMaxSize(),
                        ) {
                            when (Tab.entries[pageIndex]) {
                                Tab.Home -> HomePage(
                                    state = homeState,
                                    onNavigate = openFeaturePage,
                                    onClearRam = onClearRam,
                                    onCompactMemory = onCompactMemory,
                                )

                                Tab.Tuner -> TunerPage(
                                    onAppsTools = { openFeaturePage(FeaturePage.AppTools) },
                                    onAutoClick = { openFeaturePage(FeaturePage.AutoClick) },
                                )

                                Tab.More -> FeatureCatalog(onNavigate = openFeaturePage)
                            }
                        }
                    }
                }
            }
            entry<MainRoute.Feature> { route ->
                FeaturePageScreen(
                    page = route.page,
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeLastOrNull()
                        }
                    },
                )
            }
        }
        val rootEntryDecorator = rememberSaveableStateHolderNavEntryDecorator<MainRoute>()
        val mainEntries = remember(backStack.toList(), mainEntryProvider) {
            backStack.map(mainEntryProvider)
        }
        val decoratedMainEntries = rememberDecoratedNavEntries(
            entries = mainEntries,
            entryDecorators = listOf(rootEntryDecorator),
        )

        NavDisplay(
            entries = decoratedMainEntries,
            onBack = {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
            },
            transitionEffects = transitionEffects,
        )
    }
}

@Composable
private fun MainMaterialActions(onOpenFeature: (FeaturePage) -> Unit) {
    val fpsLabel = stringResource(R.string.main_action_label_fps)
    val powerLabel = stringResource(R.string.main_action_label_power)
    val settingsLabel = stringResource(R.string.main_action_label_settings)

    MainActionButton(
        icon = Icons.AutoMirrored.Filled.List,
        label = fpsLabel,
        onClick = { onOpenFeature(FeaturePage.FpsSessions) },
    )
    MainActionButton(
        icon = Icons.Filled.Bolt,
        label = powerLabel,
        onClick = { onOpenFeature(FeaturePage.PowerStat) },
    )
    MainActionButton(
        icon = Icons.Filled.Tune,
        label = settingsLabel,
        onClick = { onOpenFeature(FeaturePage.OtherSettings) },
    )
}

@Composable
private fun MainMiuixActions(onOpenFeature: (FeaturePage) -> Unit) {
    val fpsLabel = stringResource(R.string.main_action_label_fps)
    val powerLabel = stringResource(R.string.main_action_label_power)
    val settingsLabel = stringResource(R.string.main_action_label_settings)

    MiuixMainActionButton(
        icon = Icons.AutoMirrored.Filled.List,
        label = fpsLabel,
        onClick = { onOpenFeature(FeaturePage.FpsSessions) },
    )
    MiuixMainActionButton(
        icon = Icons.Filled.Bolt,
        label = powerLabel,
        onClick = { onOpenFeature(FeaturePage.PowerStat) },
    )
    MiuixMainActionButton(
        icon = Icons.Filled.Tune,
        label = settingsLabel,
        onClick = { onOpenFeature(FeaturePage.OtherSettings) },
    )
}

@Composable
private fun MainActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    MaterialIconButton(onClick = onClick) {
        MaterialIcon(imageVector = icon, contentDescription = label)
    }
}

@Composable
private fun MiuixMainActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    MiuixIconButton(onClick = onClick) {
        MiuixIcon(
            imageVector = icon,
            contentDescription = label,
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}
