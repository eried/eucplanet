package com.eried.eucplanet.ui.recording

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.util.GraphScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.ui.theme.AccentBlue
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentOrange
import com.eried.eucplanet.ui.theme.AccentRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    trip: TripRecord,
    onBack: () -> Unit,
    viewModel: RecordingViewModel = hiltViewModel()
) {
    var dataPoints by remember { mutableStateOf<List<TripDataPoint>>(emptyList()) }

    LaunchedEffect(trip.id) {
        dataPoints = viewModel.readTripData(trip)
    }

    val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recording_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.shareTrip(trip) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        if (dataPoints.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.recording_no_data),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(8.dp))

                // Trip summary
                val duration = ((trip.endTime ?: trip.startTime) - trip.startTime) / 1000
                val minutes = duration / 60
                val seconds = duration % 60
                val maxSpeed = dataPoints.maxOfOrNull { it.speed } ?: 0f
                val avgSpeed = dataPoints.map { it.speed }.average().toFloat()
                val minBattery = dataPoints.minOfOrNull { it.battery } ?: 0
                val maxTemp = dataPoints.maxOfOrNull { it.temperature } ?: 0f

                Text(
                    dateFormat.format(Date(trip.startTime)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(12.dp))

                // Summary stats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryCard(stringResource(R.string.recording_summary_distance), "%.1f km".format(trip.distanceKm), AccentBlue, Modifier.weight(1f))
                    SummaryCard(stringResource(R.string.recording_summary_duration), "%d:%02d".format(minutes, seconds), AccentBlue, Modifier.weight(1f))
                    SummaryCard(stringResource(R.string.recording_summary_points), "${dataPoints.size}", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryCard(stringResource(R.string.recording_summary_top_speed), "%.0f km/h".format(maxSpeed), AccentOrange, Modifier.weight(1f))
                    SummaryCard(stringResource(R.string.recording_summary_avg_speed), "%.0f km/h".format(avgSpeed), AccentGreen, Modifier.weight(1f))
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryCard(stringResource(R.string.recording_summary_min_battery), "$minBattery%",
                        if (minBattery < 20) AccentRed else AccentGreen, Modifier.weight(1f))
                    SummaryCard(stringResource(R.string.recording_summary_max_temp), "%.0f\u00B0C".format(maxTemp),
                        if (maxTemp > 60) AccentRed else AccentOrange, Modifier.weight(1f))
                }

                // Route map
                val gpsPoints = remember(dataPoints) {
                    dataPoints.filter { it.latitude != 0.0 && it.longitude != 0.0 }
                }
                if (gpsPoints.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.recording_route), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    RouteMapView(
                        points = gpsPoints,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Speed chart
                ChartCard(stringResource(R.string.recording_chart_speed, "km/h"), dataPoints.map { it.speed },
                    AccentGreen, unitLabel = "km/h", minSpan = GraphScale.SPAN_SPEED_KMH)

                Spacer(Modifier.height(12.dp))

                // Battery chart
                ChartCard(stringResource(R.string.recording_chart_battery), dataPoints.map { it.battery.toFloat() },
                    AccentBlue, unitLabel = "%", minSpan = GraphScale.SPAN_BATTERY)

                Spacer(Modifier.height(12.dp))

                // Temperature chart
                ChartCard(stringResource(R.string.recording_chart_temp, "\u00B0C"), dataPoints.map { it.temperature },
                    AccentOrange, unitLabel = "\u00B0C", minSpan = GraphScale.SPAN_TEMPERATURE_C)

                Spacer(Modifier.height(12.dp))

                // Voltage chart
                ChartCard(stringResource(R.string.recording_chart_voltage), dataPoints.map { it.voltage },
                    AccentRed, unitLabel = "V", minSpan = GraphScale.SPAN_VOLTAGE)

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium)
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RouteMapView(
    points: List<TripDataPoint>,
    modifier: Modifier = Modifier
) {
    val coordsJson = remember(points) {
        points.joinToString(",") { "[${it.latitude},${it.longitude}]" }
    }
    val html = remember(coordsJson) {
        """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <style>html,body,#map{margin:0;padding:0;width:100%;height:100%;background:#1a1a2e;}</style>
        </head><body>
        <div id="map"></div>
        <script>
        var coords=[$coordsJson];
        var map=L.map('map',{zoomControl:false,attributionControl:false});
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19}).addTo(map);
        var line=L.polyline(coords,{color:'#4FC3F7',weight:4}).addTo(map);
        map.fitBounds(line.getBounds().pad(0.1));
        L.circleMarker(coords[0],{radius:7,color:'#000',weight:2,fillColor:'#66BB6A',fillOpacity:1}).addTo(map);
        L.circleMarker(coords[coords.length-1],{radius:7,color:'#000',weight:2,fillColor:'#EF5350',fillOpacity:1}).addTo(map);
        </script></body></html>
        """.trimIndent()
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                setBackgroundColor(android.graphics.Color.parseColor("#1a1a2e"))
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        modifier = modifier.clip(RoundedCornerShape(12.dp))
    )
}

@Composable
private fun ChartCard(
    title: String,
    values: List<Float>,
    color: Color,
    unitLabel: String,
    minSpan: Float
) {
    if (values.isEmpty()) return

    val dataMin = values.min()
    val dataMax = values.max()
    val bounds = GraphScale.pad(dataMin, dataMax, minSpan)
    val textMeasurer = rememberTextMeasurer()
    val tooltipBg = MaterialTheme.colorScheme.surface
    val tooltipFg = MaterialTheme.colorScheme.onSurface

    var touchX by remember { mutableStateOf<Float?>(null) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium)
                Text("%.1f – %.1f".format(dataMin, dataMax), fontSize = 11.sp,
                    color = color, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .pointerInput(values) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            touchX = down.position.x
                            down.consume()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val change = ev.changes.firstOrNull() ?: break
                                if (!change.pressed) {
                                    touchX = null
                                    break
                                }
                                touchX = change.position.x
                                change.consume()
                            }
                        }
                    }
            ) {
                if (values.size < 2) return@Canvas
                val w = size.width
                val h = size.height
                val range = bounds.range
                val stepX = w / (values.size - 1).toFloat()

                val path = Path()
                values.forEachIndexed { idx, value ->
                    val x = idx * stepX
                    val y = h - ((value - bounds.min) / range) * h
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(path, color = color, style = Stroke(width = 2f))

                val tx = touchX
                if (tx != null) {
                    val cursorX = tx.coerceIn(0f, w)
                    val floatIdx = (cursorX / stepX).coerceIn(0f, (values.size - 1).toFloat())
                    val leftIdx = floatIdx.toInt().coerceIn(0, values.size - 1)
                    val rightIdx = (leftIdx + 1).coerceAtMost(values.size - 1)
                    val frac = floatIdx - leftIdx
                    val interpValue = values[leftIdx] + (values[rightIdx] - values[leftIdx]) * frac
                    val cursorY = h - ((interpValue - bounds.min) / range) * h

                    drawLine(color.copy(alpha = 0.5f), Offset(cursorX, 0f), Offset(cursorX, h), strokeWidth = 1.5f)
                    drawCircle(color, radius = 4f, center = Offset(cursorX, cursorY))
                    drawCircle(Color.White, radius = 2f, center = Offset(cursorX, cursorY))

                    val labelText = "%.1f %s".format(interpValue, unitLabel)
                    val measured = textMeasurer.measure(
                        labelText,
                        TextStyle(fontSize = 10.sp, color = tooltipFg, fontWeight = FontWeight.Medium)
                    )
                    val padX = 5f
                    val padY = 2f
                    val boxW = measured.size.width + padX * 2
                    val boxH = measured.size.height + padY * 2
                    val boxX = (cursorX - boxW / 2f).coerceIn(0f, w - boxW)
                    val boxY = (cursorY - boxH - 6f).coerceAtLeast(0f)
                    drawRoundRect(
                        color = tooltipBg,
                        topLeft = Offset(boxX, boxY),
                        size = Size(boxW, boxH),
                        cornerRadius = CornerRadius(5f, 5f)
                    )
                    drawText(measured, topLeft = Offset(boxX + padX, boxY + padY))
                }
            }
        }
    }
}
