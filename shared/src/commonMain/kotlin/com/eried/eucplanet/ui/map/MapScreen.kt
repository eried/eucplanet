package com.eried.eucplanet.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.location.LocationService
import com.eried.eucplanet.ui.ScreenTopBar
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.UnitFormat
import kotlin.math.roundToInt

/**
 * Live map screen — the rider's GPS position on a Leaflet map (the same tiles as
 * the Android map). v1 shows the moving dot + GPS speed + a recenter button;
 * route building / turn-by-turn can grow on top of the same WebView bridge.
 */
@Composable
internal fun MapScreen(unitSpeed: String, onBack: () -> Unit) {
    val c = MaterialTheme.appColors
    val fix by LocationService.location.collectAsState()
    var recenter by remember { mutableStateOf(0) }
    val accent6 = remember(c.primary) { c.primary.toHex6() }

    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        ScreenTopBar(c, "Map", onBack)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            MapView(Modifier.fillMaxSize(), style = "DARK", accentHex6 = accent6, lat = fix?.lat, lng = fix?.lng, recenterKey = recenter)

            // GPS speed chip (top-left).
            fix?.let { f ->
                if (f.speedKmh >= 0f) {
                    Box(Modifier.padding(12.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xCC0D0D0D)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("${UnitFormat.speed(f.speedKmh, unitSpeed).roundToInt()} ${UnitFormat.speedLabel(unitSpeed)} · GPS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (fix == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Waiting for GPS…", color = c.textSecondary, fontSize = 14.sp)
                }
            } else {
                // Recenter button (bottom-right).
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(16.dp).size(48.dp).clip(CircleShape).background(c.primary).clickable { recenter += 1 },
                    contentAlignment = Alignment.Center,
                ) { Text("◎", color = c.onPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Compose [Color] → "#RRGGBB" for the map's CSS / Leaflet. */
private fun Color.toHex6(): String {
    fun comp(f: Float) = (f * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#${comp(red)}${comp(green)}${comp(blue)}"
}
