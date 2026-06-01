package io.github.xiaotong6666.feature.home.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.xiaotong6666.feature.home.CpuCoreData
import io.github.xiaotong6666.ui.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CpuBarChart(cores: List<CpuCoreData>) {
    val accent = MiuixTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxSize()) {
        val barCount = cores.size.coerceAtLeast(1)
        val spacing = 6f
        val barWidth = (size.width - (barCount - 1) * spacing) / barCount
        val maxHeight = size.height - 6f

        repeat(barCount) { index ->
            val core = cores.getOrNull(index)
            val ratio = if (core != null && core.online) (core.load / 100f).coerceIn(0f, 1f) else 0f
            val barHeight = maxHeight * ratio.coerceAtLeast(0.06f)
            val x = index * (barWidth + spacing)
            val y = size.height - barHeight
            val color = if (core != null && core.online) {
                accent.copy(alpha = (0.12f + ratio * 0.88f).coerceIn(0.12f, 1f))
            } else {
                accent.copy(alpha = 0.08f)
            }
            drawRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight.coerceAtLeast(2f)),
            )
        }
    }
}

@Composable
internal fun CpuLoadLineChart(
    history: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (history.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val margin = 2f
        val path = Path()
        for (i in history.indices) {
            val x = margin + (w - margin * 2) * i / (history.size - 1f)
            val y = h - margin - (h - margin * 2) * (history[i] / 100f).coerceIn(0f, 1f)
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                val px = margin + (w - margin * 2) * (i - 1f) / (history.size - 1f)
                val dx = (x - px) / 3f
                val py = h - margin - (h - margin * 2) * (history[i - 1] / 100f).coerceIn(0f, 1f)
                path.cubicTo(px + dx, py, x - dx, y, x, y)
            }
        }
        drawPath(path, color, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
    }
}

@Composable
internal fun CoreSparkline(
    history: List<List<Float>>,
    coreIndex: Int,
    modifier: Modifier = Modifier,
) {
    val color = MiuixTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        if (history.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val margin = 1f
        val path = Path()
        val fillPath = Path()
        for (i in history.indices) {
            val x = margin + (w - margin * 2) * i / (history.size - 1f)
            val load = history[i].getOrElse(coreIndex) { 0f }
            val y = h - margin - (h - margin * 2) * (load / 100f).coerceIn(0f, 1f)
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                val px = margin + (w - margin * 2) * (i - 1f) / (history.size - 1f)
                val dx = (x - px) / 3f
                val pl = history[i - 1].getOrElse(coreIndex) { 0f }
                val py = h - margin - (h - margin * 2) * (pl / 100f).coerceIn(0f, 1f)
                path.cubicTo(px + dx, py, x - dx, y, x, y)
                fillPath.cubicTo(px + dx, py, x - dx, y, x, y)
            }
        }
        fillPath.lineTo(margin + (w - margin * 2), h)
        fillPath.close()
        drawPath(fillPath, color.copy(alpha = 0.15f))
        drawPath(path, color, style = Stroke(width = 1.2f, cap = StrokeCap.Round))
    }
}
