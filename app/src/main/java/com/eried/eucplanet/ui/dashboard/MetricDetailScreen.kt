package com.eried.eucplanet.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.util.GraphBounds
import com.eried.eucplanet.util.GraphScale
import com.eried.eucplanet.R
import com.eried.eucplanet.data.repository.MetricSample
import com.eried.eucplanet.ui.theme.AccentBlue
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentOrange
import com.eried.eucplanet.ui.theme.AccentRed

enum class MetricType(val titleRes: Int, val unit: String, val color: Color) {
    BATTERY(R.string.metric_battery, "%", AccentGreen),
    TEMPERATURE(R.string.metric_temperature, "\u00B0C", AccentOrange),
    VOLTAGE(R.string.metric_voltage, "V", AccentBlue),
    CURRENT(R.string.metric_current, "A", AccentBlue),
    LOAD(R.string.metric_load, "%", AccentOrange),
    SPEED(R.string.metric_speed, "km/h", AccentGreen)
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
        MetricType.TEMPERATURE -> com.eried.eucplanet.util.Units.temperature(v, imperial)
        MetricType.SPEED -> com.eried.eucplanet.util.Units.speed(v, imperial)
        else -> v
    }

    val samples: List<MetricSample> = if (metricType == MetricType.TEMPERATURE || metricType == MetricType.SPEED) {
        rawSamples.map { MetricSample(it.timestampMs, convert(it.value)) }
    } else rawSamples

    val unitLabel = when (metricType) {
        MetricType.TEMPERATURE -> com.eried.eucplanet.util.Units.tempUnit(imperial)
        MetricType.SPEED -> com.eried.eucplanet.util.Units.speedUnit(imperial)
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
                title = { Text(stringResource(R.string.metric_detail_title, stringResource(metricType.titleRes))) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                    StatSummary(stringResource(R.string.metric_min), "%.1f".format(min), unitLabel)
                    StatSummary(stringResource(R.string.metric_avg), "%.1f".format(avg), unitLabel)
                    StatSummary(stringResource(R.string.metric_max), "%.1f".format(max), unitLabel)
                    StatSummary(stringResource(R.string.metric_time), formatDuration(duration), "")
                }

                Spacer(Modifier.height(16.dp))

                val boundsFor: (Float, Float) -> GraphBounds = when (metricType) {
                    MetricType.BATTERY -> { _, _ -> GraphScale.fixed(0f, 100f) }
                    MetricType.TEMPERATURE -> { min, max -> GraphScale.absolute(min, max, 5f) }
                    MetricType.LOAD -> { min, max -> GraphScale.absolute(min, max, 5f) }
                    MetricType.CURRENT -> { min, max -> GraphScale.absolute(min, max, 1f) }
                    MetricType.VOLTAGE -> { min, max -> GraphScale.pad(min, max, GraphScale.SPAN_VOLTAGE) }
                    MetricType.SPEED -> { min, max ->
                        GraphScale.pad(min, max, if (imperial) GraphScale.SPAN_SPEED_MPH else GraphScale.SPAN_SPEED_KMH)
                    }
                }

                MetricGraph(
                    samples = windowSamples,
                    color = metricType.color,
                    boundsFor = boundsFor,
                    unitLabel = unitLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                )
            } else {
                // No data yet — show empty placeholder graph
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.metric_waiting),
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
    val noDataLabel = stringResource(R.string.metric_no_data)

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
            val noData = textMeasurer.measure(noDataLabel, TextStyle(fontSize = 14.sp, color = labelColor))
            drawText(noData, topLeft = Offset(w / 2f - noData.size.width / 2f, h / 2f - noData.size.height - 8f))
        }
    }
}

@Composable
private fun MetricGraph(
    samples: List<MetricSample>,
    color: Color,
    boundsFor: (dataMin: Float, dataMax: Float) -> GraphBounds,
    unitLabel: String,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tooltipBg = MaterialTheme.colorScheme.surfaceVariant
    val tooltipFg = MaterialTheme.colorScheme.onSurface

    var touchX by remember { mutableStateOf<Float?>(null) }
    var frozenSamples by remember { mutableStateOf<List<MetricSample>?>(null) }
    val latestSamples = rememberUpdatedState(samples)

    val displaySamples = frozenSamples ?: samples

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 44.dp, bottom = 28.dp, top = 12.dp, end = 12.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Snapshot current samples so the graph stops sliding while held
                        frozenSamples = latestSamples.value
                        touchX = down.position.x
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                touchX = null
                                frozenSamples = null
                                break
                            }
                            touchX = change.position.x
                            change.consume()
                        }
                    }
                }
        ) {
            if (displaySamples.size < 2) return@Canvas
            val w = size.width
            val h = size.height

            val values = displaySamples.map { it.value }
            val bounds = boundsFor(values.min(), values.max())
            val graphMin = bounds.min
            val graphRange = bounds.range

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
            val startTime = displaySamples.first().timestampMs
            val endTime = displaySamples.last().timestampMs
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
            val timeRange = (endTime - startTime).coerceAtLeast(1)
            val path = androidx.compose.ui.graphics.Path()
            displaySamples.forEachIndexed { idx, sample ->
                val x = ((sample.timestampMs - startTime).toFloat() / timeRange) * w
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

            // Touch crosshair — vertical line follows finger, dot interpolates on the curve
            val tx = touchX
            if (tx != null) {
                val cursorX = tx.coerceIn(0f, w)
                val targetMs = startTime + (cursorX / w * timeRange).toLong()

                // Find bracketing samples for interpolation
                var leftIdx = 0
                for (i in displaySamples.indices) {
                    if (displaySamples[i].timestampMs <= targetMs) leftIdx = i else break
                }
                val rightIdx = (leftIdx + 1).coerceAtMost(displaySamples.size - 1)
                val left = displaySamples[leftIdx]
                val right = displaySamples[rightIdx]
                val span = (right.timestampMs - left.timestampMs).coerceAtLeast(1)
                val frac = ((targetMs - left.timestampMs).toFloat() / span).coerceIn(0f, 1f)
                val interpValue = left.value + (right.value - left.value) * frac
                val cursorY = h - ((interpValue - graphMin) / graphRange) * h

                drawLine(color.copy(alpha = 0.5f), Offset(cursorX, 0f), Offset(cursorX, h), strokeWidth = 1.5f)
                drawCircle(color, radius = 5f, center = Offset(cursorX, cursorY))
                drawCircle(androidx.compose.ui.graphics.Color.White, radius = 2.5f, center = Offset(cursorX, cursorY))

                val valText = "%.1f %s".format(interpValue, unitLabel)
                val timeText = formatDuration(((targetMs - startTime) / 1000).coerceAtLeast(0))
                val labelText = "$valText · $timeText"
                val measured = textMeasurer.measure(labelText, TextStyle(fontSize = 11.sp, color = tooltipFg, fontWeight = FontWeight.Medium))
                val padX = 6f
                val padY = 3f
                val boxW = measured.size.width + padX * 2
                val boxH = measured.size.height + padY * 2
                val boxX = (cursorX - boxW / 2f).coerceIn(0f, w - boxW)
                val boxY = (cursorY - boxH - 10f).coerceAtLeast(0f)
                drawRoundRect(
                    color = tooltipBg,
                    topLeft = Offset(boxX, boxY),
                    size = Size(boxW, boxH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                )
                drawText(measured, topLeft = Offset(boxX + padX, boxY + padY))
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m${s}s" else "${s}s"
}
