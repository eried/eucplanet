package com.eried.eucplanet.car

import android.annotation.SuppressLint
import android.graphics.Rect
import android.location.Location
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.eried.eucplanet.R
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AndroidAutoSettings
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WidgetMetricType
import com.eried.eucplanet.ui.theme.BuiltInThemes
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.Units
import com.eried.eucplanet.widget.WidgetMetricFormat
import kotlinx.coroutines.flow.StateFlow

/** Everything the car screen reads, as the phone's own flows. */
class CarRideState(
    val wheel: StateFlow<WheelData>,
    val connection: StateFlow<ConnectionState>,
    val location: StateFlow<Location?>,
    val route: StateFlow<List<GeoPoint>?>,
    val settings: StateFlow<AppSettings>,
    val phoneBattery: () -> Int,
)

/**
 * The car screen: the map filling it, and a stats panel on the side Android
 * Auto leaves free. Colors follow the car's day/night signal through the dark
 * and light built-in themes, so the panel reads like the phone app does.
 */
@Composable
fun CarRideContent(state: CarRideState, dark: Boolean, visibleArea: Rect?) {
    EucPlanetTheme(colors = if (dark) BuiltInThemes.dark.colors else BuiltInThemes.light.colors) {
        val c = MaterialTheme.appColors
        val settings by state.settings.collectAsState()
        val wheel by state.wheel.collectAsState()
        val connection by state.connection.collectAsState()
        val connected = connection == ConnectionState.CONNECTED

        // The last battery seen this session, for the waiting card.
        var lastBattery by remember { mutableStateOf<Int?>(null) }
        if (connected && wheel.batteryPercent > 0) lastBattery = wheel.batteryPercent

        val density = LocalDensity.current
        val inset = with(density) {
            visibleArea?.let { Pair(it.left.toDp(), it.top.toDp()) } ?: Pair(0.dp, 0.dp)
        }

        Box(Modifier.fillMaxSize().background(c.appBackground)) {
            CarMap(state, settings.navMapType, c.primary, c.surface)
            val panel = Modifier
                .padding(start = inset.first + 12.dp, top = inset.second + 12.dp)
                .width(200.dp)
                .background(c.surface.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
                .padding(14.dp)
            if (connected) StatsPanel(panel, wheel, settings, state.phoneBattery())
            else WaitingCard(panel, lastBattery)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CarMap(state: CarRideState, mapType: String, accent: Color, ring: Color) {
    val location by state.location.collectAsState()
    val route by state.route.collectAsState()
    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val html = remember(mapType, accent, ring) { carMapHtml(mapType, accent.hex(), ring.hex()) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { ready = true }
                }
                web = this
            }
        },
        // Every location fix recomposes this, and a reload would drop the map
        // back to the world view; load only when the page itself changes.
        update = {
            if (it.tag != html) {
                it.tag = html
                ready = false
                it.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
            }
        },
    )
    LaunchedEffect(ready, location) {
        val l = location ?: return@LaunchedEffect
        if (ready) web?.evaluateJavascript("setPos(${l.latitude},${l.longitude},${l.bearing})", null)
    }
    LaunchedEffect(ready, route) {
        if (!ready) return@LaunchedEffect
        val pts = route.orEmpty().joinToString(",", "[", "]") { "[${it.lat},${it.lng}]" }
        web?.evaluateJavascript("setRoute($pts)", null)
    }
}

@Composable
private fun StatsPanel(modifier: Modifier, wheel: WheelData, s: AppSettings, phoneBattery: Int) {
    val c = MaterialTheme.appColors
    val context = LocalContext.current
    val speedUnit = Units.effectiveSpeedUnit(s)
    val distUnit = Units.effectiveDistanceUnit(s)
    val tempUnit = Units.effectiveTempUnit(s)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "%.0f".format(Units.speed(wheel.speed, speedUnit)),
                color = c.textPrimary, fontSize = 56.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                Units.speedUnit(context, speedUnit),
                color = c.textSecondary, fontSize = 18.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
            )
        }
        AndroidAutoSettings.metricSlots(s.androidAuto.metrics).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { key ->
                    val type = WidgetMetricType.byKey(key)
                    Column(
                        Modifier.weight(1f)
                            .background(c.surfaceVariant, RoundedCornerShape(10.dp))
                            .padding(vertical = 6.dp, horizontal = 8.dp)
                    ) {
                        if (type != null) {
                            Text(stringResource(type.pickerLabel), color = c.textSecondary, fontSize = 12.sp, maxLines = 1)
                            Text(
                                WidgetMetricFormat.value(type, wheel, speedUnit, distUnit, tempUnit, phoneBattery) +
                                    " " + WidgetMetricFormat.unit(context, type, speedUnit, distUnit, tempUnit),
                                color = c.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WaitingCard(modifier: Modifier, lastBattery: Int?) {
    val c = MaterialTheme.appColors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.car_waiting), color = c.textPrimary,
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start,
        )
        if (lastBattery != null) {
            Text(stringResource(R.string.car_last_battery, lastBattery), color = c.textSecondary, fontSize = 15.sp)
        }
    }
}

private fun Color.hex(): String = String.format("#%06X", toArgb() and 0xFFFFFF)
