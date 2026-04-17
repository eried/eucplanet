package com.eried.evendarkerbot.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.evendarkerbot.data.repository.MetricSample
import com.eried.evendarkerbot.ui.theme.AccentBlue
import com.eried.evendarkerbot.ui.theme.AccentGreen
import com.eried.evendarkerbot.ui.theme.AccentOrange
import com.eried.evendarkerbot.ui.theme.AccentRed

enum class MetricType(val title: String, val unit: String, val color: Color) {
    BATTERY("Battery", "%", AccentGreen),
    TEMPERATURE("Temperature", "\u00B0C", AccentOrange),
    VOLTAGE("Voltage", "V", AccentBlue),
    CURRENT("Current", "A", AccentBlue),
    LOAD("Load", "%", AccentOrange),
    SPEED("Speed", "km/h", AccentGreen)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricDetailScreen(
    metricType: MetricType,
    onBack: () -> Unit,
    viewModel: MetricDetailViewModel = hiltViewModel()
) {
    val fullHistory by viewModel.fullHistory.collectAsState()
    val wheelData by viewModel.wheelData.collectAsState()
    val imperial by viewModel.imperialUnits.collectAsState()

    val rawSamples: List<MetricSample> = when (metricType) {
        MetricType.BATTERY -> fullHistory.battery
        MetricType.TEMPERATURE -> fullHistory.temperature
        MetricType.VOLTAGE -> fullHistory.voltage
        MetricType.CURRENT -> fullHistory.current
        MetricType.LOAD -> fullHistory.load
        MetricType.SPEED -> fullHistory.speed
    }

    fun convert(v: Float): Float = when (metricType) {
        MetricType.TEMPERATURE -> com.eried.evendarkerbot.util.Units.temperature(v, imperial)
        MetricType.SPEED -> com.eried.evendarkerbot.util.Units.speed(v, imperial)
        else -> v
    }

    val samples: List<MetricSample> = if (metricType == MetricType.TEMPERATURE || metricType == MetricType.SPEED) {
        rawSamples.map { MetricSample(it.timestampMs, convert(it.value)) }
    } else rawSamples

    val unitLabel = when (metricType) {
        MetricType.TEMPERATURE -> com.eried.evendarkerbot.util.Units.tempUnit(imperial)
        MetricType.SPEED -> com.eried.evendarkerbot.util.Units.speedUnit(imperial)
        else -> metricType.unit
    }

    val currentValue = convert(when (metricType) {
        MetricType.BATTERY -> wheelData.batteryPercent.toFloat()
        MetricType.TEMPERATURE -> wheelData.maxTemperature
        MetricType.VOLTAGE -> wheelData.voltage
        MetricType.CURRENT -> kotlin.math.abs(wheelData.current)
        MetricType.LOAD -> kotlin.math.abs(wheelData.pwm)
        MetricType.SPEED -> kotlin.math.abs(wheelData.speed)
    })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historical ${metricType.title} Data") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Current value large
            Text(
                "${"%.1f".format(currentValue)} ${unitLabel}",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = metricType.color
            )

            Spacer(Modifier.height(8.dp))

            if (samples.size >= 2) {
                val windowSamples = samples.takeLast(300)
                val values = windowSamples.map { it.value }
                val min = values.min()
                val max = values.max()
                val avg = values.average().toFloat()
                val duration = (windowSamples.last().timestampMs - windowSamples.first().timestampMs) / 1000

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatSummary("MIN", "%.1f".format(min), unitLabel)
                    StatSummary("AVG", "%.1f".format(avg), unitLabel)
                    StatSummary("MAX", "%.1f".format(max), unitLabel)
                    StatSummary("TIME", formatDuration(duration), "")
                }

                Spacer(Modifier.height(16.dp))

                MetricGraph(
                    samples = windowSamples,
                    color = metricType.color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                )
            } else {
                // No data yet — show empty placeholder graph
                Spacer(Modifier.height(16.dp))
                Text("Waiting for data... Connect to wheel to start collecting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                EmptyGraph(
                    color = metricType.color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatSummary(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
        Text("$value$unit", fontSize = 16.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun EmptyGraph(
    color: Color,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 44.dp, bottom = 28.dp, top = 12.dp, end = 12.dp)
        ) {
            val w = size.width
            val h = size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

            // Grid lines
            for (i in 0..4) {
                val y = h - h * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = dash)
            }
            for (i in 0..3) {
                val x = w * i / 3f
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f, pathEffect = dash)
            }

            // Flat dashed line in the middle
            drawLine(
                color = color.copy(alpha = 0.3f),
                start = Offset(0f, h / 2f),
                end = Offset(w, h / 2f),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            )

            // "No data" text
            val noData = textMeasurer.measure("No data", TextStyle(fontSize = 14.sp, color = labelColor))
            drawText(noData, topLeft = Offset(w / 2f - noData.size.width / 2f, h / 2f - noData.size.height - 8f))
        }
    }
}

@Composable
private fun MetricGraph(
    samples: List<MetricSample>,
    color: Color,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 44.dp, bottom = 28.dp, top = 12.dp, end = 12.dp)
        ) {
            if (samples.size < 2) return@Canvas
            val w = size.width
            val h = size.height

            val values = samples.map { it.value }
            val dataMin = values.min()
            val dataMax = values.max()
            val range = (dataMax - dataMin).coerceAtLeast(1f)
            val graphMin = dataMin - range * 0.05f
            val graphMax = dataMax + range * 0.05f
            val graphRange = graphMax - graphMin

            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

            // Horizontal grid lines (5 lines)
            for (i in 0..4) {
                val y = h - h * i / 4f
                val v = graphMin + graphRange * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = dash)
                val label = "%.0f".format(v)
                val measured = textMeasurer.measure(label, TextStyle(fontSize = 9.sp, color = labelColor))
                drawText(measured, topLeft = Offset(-measured.size.width - 4f, y - measured.size.height / 2f))
            }

            // Time axis labels
            val startTime = samples.first().timestampMs
            val endTime = samples.last().timestampMs
            val totalSec = ((endTime - startTime) / 1000).toInt().coerceAtLeast(1)
            val timeSteps = if (totalSec > 300) 5 else if (totalSec > 60) 4 else 3
            for (i in 0..timeSteps) {
                val x = w * i / timeSteps.toFloat()
                val sec = totalSec * i / timeSteps
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f, pathEffect = dash)
                val label = formatDuration(sec.toLong())
                val measured = textMeasurer.measure(label, TextStyle(fontSize = 9.sp, color = labelColor))
                drawText(measured, topLeft = Offset(x - measured.size.width / 2f, h + 4f))
            }

            // Data line
            val path = androidx.compose.ui.graphics.Path()
            samples.forEachIndexed { idx, sample ->
                val x = ((sample.timestampMs - startTime).toFloat() / (endTime - startTime).coerceAtLeast(1)) * w
                val y = h - ((sample.value - graphMin) / graphRange) * h
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            // Filled area
            val fillPath = androidx.compose.ui.graphics.Path()
            fillPath.addPath(path)
            fillPath.lineTo(w, h)
            fillPath.lineTo(0f, h)
            fillPath.close()
            drawPath(fillPath, color = color.copy(alpha = 0.1f))
            drawPath(path, color = color, style = Stroke(width = 2.5f))
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m${s}s" else "${s}s"
}
