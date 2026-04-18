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
import androidx.compose.runtime.collectAsState
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
import com.eried.eucplanet.ui.common.HintText
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
                HintText(stringResource(R.string.recording_no_data))
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
                val isLive by viewModel.isTripLiveRecording(trip).collectAsState(initial = false)
                val liveLocation by viewModel.liveLocation.collectAsState()
                if (gpsPoints.size >= 2 || (isLive && liveLocation != null)) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.recording_route), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    RouteMapView(
                        points = gpsPoints,
                        isLive = isLive,
                        liveLat = liveLocation?.latitude,
                        liveLon = liveLocation?.longitude,
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
    isLive: Boolean = false,
    liveLat: Double? = null,
    liveLon: Double? = null,
    modifier: Modifier = Modifier
) {
    val coordsJson = remember(points) {
        points.joinToString(",") { "[${it.latitude},${it.longitude}]" }
    }
    // Rebuild the WebView only when the historical trace changes or when we first
    // enter/leave live mode — live marker updates are applied via JS.
    val html = remember(coordsJson, isLive) {
        buildMapHtml(coordsJson, isLive)
    }

    var webView by remember { mutableStateOf<WebView?>(null) }

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
                setBackgroundColor(android.graphics.Color.parseColor("#0b0f19"))
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                webView = this
            }
        },
        update = { wv ->
            webView = wv
        },
        modifier = modifier.clip(RoundedCornerShape(12.dp))
    )

    // Push live GPS updates into the map via a JS hook defined in the HTML.
    LaunchedEffect(isLive, liveLat, liveLon, webView) {
        val wv = webView ?: return@LaunchedEffect
        if (!isLive) return@LaunchedEffect
        val lat = liveLat ?: return@LaunchedEffect
        val lon = liveLon ?: return@LaunchedEffect
        wv.evaluateJavascript(
            "if (window.updateLivePoint) updateLivePoint($lat,$lon);",
            null
        )
    }
}

private fun buildMapHtml(coordsJson: String, isLive: Boolean): String = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
  html,body,#map{margin:0;padding:0;width:100%;height:100%;background:#0b0f19;}
  .half-marker{
    width:18px;height:18px;border-radius:50%;border:2px solid #000;
    background: linear-gradient(to right,#66BB6A 50%,#EF5350 50%);
    box-sizing:border-box;
  }
  .live-marker{
    width:14px;height:14px;border-radius:50%;border:2px solid #fff;
    background:#FFC107;
    box-shadow:0 0 6px rgba(255,193,7,0.9);
  }
</style>
</head><body>
<div id="map"></div>
<script>
  var coords=[$coordsJson];
  var map=L.map('map',{zoomControl:false,attributionControl:false});
  L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',{
    maxZoom:19, subdomains:'abcd'
  }).addTo(map);

  var hasRoute = coords.length >= 2;
  var start=null, end=null, overlap=null, line=null;

  function render(){
    if (hasRoute){
      line = L.polyline(coords,{color:'#4FC3F7',weight:4}).addTo(map);
      map.fitBounds(line.getBounds().pad(0.2));
      placeEndpoints();
      map.on('zoomend moveend', placeEndpoints);
    } else if (coords.length === 1) {
      map.setView(coords[0], 17);
    } else {
      map.setView([0,0], 2);
    }
  }

  function placeEndpoints(){
    if (!hasRoute) return;
    if (start){ map.removeLayer(start); start=null; }
    if (end){ map.removeLayer(end); end=null; }
    if (overlap){ map.removeLayer(overlap); overlap=null; }

    var a = coords[0], b = coords[coords.length-1];
    var pa = map.latLngToContainerPoint(a);
    var pb = map.latLngToContainerPoint(b);
    var dist = pa.distanceTo(pb);
    var r = 7; // circleMarker radius in px
    // Overlap is more than 50% of a marker's width: dist < 2*r*(1-0.5) = r.
    if (dist < r){
      overlap = L.marker(a,{icon:L.divIcon({className:'half-marker',iconSize:[18,18],iconAnchor:[9,9]})}).addTo(map);
    } else {
      start = L.circleMarker(a,{radius:r,color:'#000',weight:2,fillColor:'#66BB6A',fillOpacity:1}).addTo(map);
      end   = L.circleMarker(b,{radius:r,color:'#000',weight:2,fillColor:'#EF5350',fillOpacity:1}).addTo(map);
    }
  }

  // Live marker API (called from Kotlin via evaluateJavascript).
  var live=null, livePath=null;
  window.updateLivePoint = function(lat, lon){
    var p = [lat, lon];
    if (!live){
      live = L.marker(p,{icon:L.divIcon({className:'live-marker',iconSize:[14,14],iconAnchor:[7,7]})}).addTo(map);
      if (!hasRoute) map.setView(p, 17);
    } else {
      live.setLatLng(p);
    }
  };

  render();
  ${if (isLive) "/* live mode: waiting for updateLivePoint() */" else ""}
</script></body></html>
""".trimIndent()

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
