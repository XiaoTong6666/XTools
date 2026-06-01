package io.github.xiaotong6666.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.xiaotong6666.uihelper.mode.AdaptiveTheme
import io.github.xiaotong6666.uihelper.mode.UiMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun Theme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) = AdaptiveTheme(
    uiMode = UiMode.Miuix,
    darkTheme = darkTheme,
    content = content,
)

val ColorScheme.surfaceHigh: Color
    @Composable
    get() = MiuixTheme.colorScheme.surfaceContainerHigh

val ColorScheme.cardBg: Color
    @Composable
    get() = if (MiuixTheme.colorSchemeMode == ColorSchemeMode.Dark) {
        Colors.cardBgDark
    } else {
        Colors.cardBgLight
    }

val usageRingTrackColor: Color
    @Composable
    get() = MiuixTheme.colorScheme.secondaryContainer

val homeInlineContainerColor: Color
    @Composable
    get() = if (MiuixTheme.colorSchemeMode == ColorSchemeMode.Dark) {
        MiuixTheme.colorScheme.surfaceContainerHighest
    } else {
        MiuixTheme.colorScheme.secondaryContainer
    }
