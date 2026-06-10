package com.eried.eucplanet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.appColors

/**
 * Shared per-metric detail screen — a port of the Android MetricDetailScreen:
 * the selected metric's recent history as a filled line graph, with MIN / AVG /
 * MAX / NOW summary stats. Opened by tapping a dashboard metric tile.
 */
@Composable
internal fun MetricDetailScreen(
    metricKey: String,
    history: List<WheelData>,
    current: WheelData,
    unitDistance: String,
    unitTemp: String,
    onBack: () -> Unit,
) {
    val c = MaterialTheme.appColors
    val metrics = metricsFor(unitDistance, unitTemp)
    val metric = metrics.firstOrNull { it.key == metricKey } ?: metrics.first()
    val color = metric.color(c)
    val series = history.map { metric.value(it) }
    val lo = series.minOrNull() ?: 0f
    val hi = series.maxOrNull() ?: 0f
    val avg = if (series.isNotEmpty()) series.sum() / series.size else 0f

    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        ScreenTopBar(c, metric.label, onBack)

        // Big current value.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(metric.text(current), color = color, fontSize = 52.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(6.dp))
            Text(metric.unit, color = c.textSecondary, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
        }

        // History graph.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).padding(14.dp),
        ) {
            Text("Last ${series.size} samples", color = c.textSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            MetricGraph(series, color, c, Modifier.fillMaxWidth().height(180.dp))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("max ${hi.fmt(metric.unit)}", color = c.textDisabled, fontSize = 10.sp)
                Text("min ${lo.fmt(metric.unit)}", color = c.textDisabled, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBox(c, "MIN", lo.fmt(metric.unit), c.textPrimary)
            StatBox(c, "AVG", avg.fmt(metric.unit), c.metricVoltage)
            StatBox(c, "MAX", hi.fmt(metric.unit), c.gaugeWarn)
            StatBox(c, "NOW", metric.value(current).fmt(metric.unit), color)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "  Full metric history + composite (motor/controller/battery) detail lands as the recorder is ported.",
            color = c.textDisabled, fontSize = 10.sp,
        )
    }
}

@Composable
private fun MetricGraph(series: List<Float>, color: Color, c: AppThemeColors, modifier: Modifier) {
    Canvas(modifier) {
        if (series.size < 2) return@Canvas
        val lo = series.minOrNull() ?: 0f
        val hi = series.maxOrNull() ?: 0f
        val span = (hi - lo).takeIf { it > 0.0001f } ?: 1f
        val dx = size.width / (series.size - 1)
        fun yOf(v: Float) = size.height - ((v - lo) / span) * size.height

        // baseline + midline guides
        drawLine(c.outline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
        drawLine(c.outline.copy(alpha = 0.4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1f)

        val line = Path()
        val area = Path()
        area.moveTo(0f, size.height)
        series.forEachIndexed { i, v ->
            val x = dx * i
            val y = yOf(v)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            area.lineTo(x, y)
        }
        area.lineTo(size.width, size.height)
        area.close()
        drawPath(area, color.copy(alpha = 0.18f))
        drawPath(line, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}

@Composable
private fun StatBox(c: AppThemeColors, label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = c.cornerStatLabel, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** One-decimal for fractional units, integer for %/temperature-style. */
private fun Float.fmt(unit: String): String =
    if (unit == "%" || unit.startsWith("°") || unit == "K") f0() else f1()
