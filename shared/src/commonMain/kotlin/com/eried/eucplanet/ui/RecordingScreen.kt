package com.eried.eucplanet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.model.TripSummary
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.appColors

/**
 * Shared Recording (trip history) screen — a port of the Android RecordingScreen:
 * a scrolling list of trip cards with distance / duration / avg / max and GPS +
 * sync status, plus an empty state. Trips come from the [com.eried.eucplanet.data.TripRecorder].
 */
@Composable
internal fun RecordingScreen(trips: List<TripSummary>, onBack: () -> Unit) {
    val c = MaterialTheme.appColors
    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        ScreenTopBar(c, "Recordings", onBack)
        if (trips.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No trips recorded", color = c.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text("Tap REC on the dashboard to start a ride.", color = c.textDisabled, fontSize = 12.sp)
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                trips.forEach { t ->
                    TripCard(c, t)
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Trip CSV export (DarknessBot-compatible) lands with the storage actuals.",
                    color = c.textDisabled, fontSize = 10.sp,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun TripCard(c: AppThemeColors, t: TripSummary) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)
            .clickable { }.padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.date, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (t.gpsLock) "GPS" else "no GPS", color = if (t.gpsLock) c.statusGood else c.textDisabled, fontSize = 10.sp)
            Spacer(Modifier.width(10.dp))
            Text(if (t.synced) "☁ synced" else "☁ local", color = if (t.synced) c.primary else c.textDisabled, fontSize = 10.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TripStat(c, "DIST", "${t.distanceKm.f1()} km", c.metricBattery)
            TripStat(c, "TIME", "${t.durationMin} min", c.textPrimary)
            TripStat(c, "AVG", "${t.avgKmh.f0()} km/h", c.metricVoltage)
            TripStat(c, "MAX", "${t.maxKmh.f1()} km/h", c.gaugeWarn)
        }
    }
}

@Composable
private fun TripStat(c: AppThemeColors, label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = c.cornerStatLabel, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
