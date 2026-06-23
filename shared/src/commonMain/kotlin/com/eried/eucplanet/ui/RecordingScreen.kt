package com.eried.eucplanet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.model.TripBackup
import com.eried.eucplanet.data.model.TripSummary
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.UnitFormat

/**
 * Shared Recording (trip history) screen — a port of the Android RecordingScreen:
 * a scrolling list of trip cards with distance / duration / avg / max and GPS +
 * sync status, plus an empty state. Trips come from the [com.eried.eucplanet.data.TripRecorder].
 */
@Composable
internal fun RecordingScreen(
    trips: List<TripSummary>,
    unitSpeed: String,
    unitDistance: String,
    backupEnabled: Boolean = false,
    onOpen: (TripSummary) -> Unit,
    onSyncAll: () -> Unit = {},
    onRetry: (TripSummary) -> Unit = {},
    onBack: () -> Unit,
) {
    val c = MaterialTheme.appColors
    val pending = trips.count { it.backup == TripBackup.Off || it.backup == TripBackup.Failed }
    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        ScreenTopBar(c, "Recordings", onBack)
        if (backupEnabled && pending > 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$pending trip${if (pending == 1) "" else "s"} not backed up", color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    "Back up now", color = c.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.primary).clickable { onSyncAll() }.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        } else if (!backupEnabled) {
            Text(
                "Register in Settings · Online · EUC Stats to back up trips online.",
                color = c.textDisabled, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
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
                    TripCard(c, t, unitSpeed, unitDistance, onRetry = { onRetry(t) }) { onOpen(t) }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap a trip for detail. Recorded trips export a DarknessBot-compatible CSV to the app's Documents folder.",
                    color = c.textDisabled, fontSize = 10.sp,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun TripCard(c: AppThemeColors, t: TripSummary, unitSpeed: String, unitDistance: String, onRetry: () -> Unit, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)
            .clickable { onClick() }.padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.date, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (t.gpsLock) "GPS" else "no GPS", color = if (t.gpsLock) c.statusGood else c.textDisabled, fontSize = 10.sp)
            Spacer(Modifier.width(10.dp))
            // Backup badge; tap to retry when it failed.
            val backupMod = if (t.backup == TripBackup.Failed) Modifier.clip(RoundedCornerShape(6.dp)).clickable { onRetry() }.padding(horizontal = 4.dp, vertical = 1.dp) else Modifier
            Text(backupLabel(t.backup), color = backupColor(c, t.backup), fontSize = 10.sp, fontWeight = if (t.backup == TripBackup.Failed) FontWeight.SemiBold else FontWeight.Normal, modifier = backupMod)
            if (t.csvPath != null) {
                Spacer(Modifier.width(10.dp))
                Text("CSV", color = c.statusGood, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TripStat(c, "DIST", "${UnitFormat.distance(t.distanceKm, unitDistance).f1()} ${UnitFormat.distanceLabel(unitDistance)}", c.metricBattery)
            TripStat(c, "TIME", "${t.durationMin} min", c.textPrimary)
            TripStat(c, "AVG", "${UnitFormat.speed(t.avgKmh, unitSpeed).f0()} ${UnitFormat.speedLabel(unitSpeed)}", c.metricVoltage)
            TripStat(c, "MAX", "${UnitFormat.speed(t.maxKmh, unitSpeed).f1()} ${UnitFormat.speedLabel(unitSpeed)}", c.gaugeWarn)
        }
    }
}

private fun backupLabel(b: TripBackup): String = when (b) {
    TripBackup.Off -> "☁ local"
    TripBackup.Pending -> "☁ backing up…"
    TripBackup.Uploaded -> "☁ synced"
    TripBackup.Failed -> "☁ failed · retry"
}

private fun backupColor(c: AppThemeColors, b: TripBackup): Color = when (b) {
    TripBackup.Off -> c.textDisabled
    TripBackup.Pending -> c.primary
    TripBackup.Uploaded -> c.statusGood
    TripBackup.Failed -> c.statusDanger
}

@Composable
private fun TripStat(c: AppThemeColors, label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = c.cornerStatLabel, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Shared trip-detail screen — a port of the Android TripDetailScreen: big trip
 * stats + speed/voltage history graphs (for in-app recorded rides) + GPS/sync/CSV.
 */
@Composable
internal fun TripDetailScreen(trip: TripSummary, unitSpeed: String, unitDistance: String, onViewOnline: () -> Unit = {}, onBack: () -> Unit) {
    val c = MaterialTheme.appColors
    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        ScreenTopBar(c, trip.date, onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BigStat(c, "DISTANCE", "${UnitFormat.distance(trip.distanceKm, unitDistance).f1()} ${UnitFormat.distanceLabel(unitDistance)}", c.metricBattery)
                BigStat(c, "DURATION", "${trip.durationMin} min", c.textPrimary)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BigStat(c, "AVG SPEED", "${UnitFormat.speed(trip.avgKmh, unitSpeed).f0()} ${UnitFormat.speedLabel(unitSpeed)}", c.metricVoltage)
                BigStat(c, "MAX SPEED", "${UnitFormat.speed(trip.maxKmh, unitSpeed).f1()} ${UnitFormat.speedLabel(unitSpeed)}", c.gaugeWarn)
            }
            Spacer(Modifier.height(20.dp))
            if (trip.samples.size >= 2) {
                Text("SPEED (${UnitFormat.speedLabel(unitSpeed)})", color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                TripGraph(trip.samples.map { UnitFormat.speed(it.speed, unitSpeed) }, c.metricBattery, c, Modifier.fillMaxWidth().height(130.dp))
                Spacer(Modifier.height(16.dp))
                Text("VOLTAGE (V)", color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                TripGraph(trip.samples.map { it.voltage }, c.metricVoltage, c, Modifier.fillMaxWidth().height(110.dp))
            } else {
                Text("Per-sample graphs are available for rides recorded in-app (tap REC on the dashboard).", color = c.textDisabled, fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (trip.gpsLock) "GPS lock" else "no GPS", color = if (trip.gpsLock) c.statusGood else c.textDisabled, fontSize = 11.sp)
                Spacer(Modifier.width(12.dp))
                Text(backupLabel(trip.backup), color = backupColor(c, trip.backup), fontSize = 11.sp)
            }
            if (trip.csvPath != null) {
                Spacer(Modifier.height(8.dp))
                Text("CSV: ${trip.csvPath}", color = c.textDisabled, fontSize = 10.sp)
            }
            // View this ride in the embedded EUC Viewer (map + charts), like Android.
            if (trip.samples.size >= 2) {
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.primary)
                        .clickable { onViewOnline() }.padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("View online (map + charts)", color = c.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BigStat(c: AppThemeColors, label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = c.cornerStatLabel, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TripGraph(series: List<Float>, color: Color, c: AppThemeColors, modifier: Modifier) {
    Canvas(modifier) {
        if (series.size < 2) return@Canvas
        val lo = series.minOrNull() ?: 0f
        val hi = series.maxOrNull() ?: 0f
        val span = (hi - lo).takeIf { it > 0.0001f } ?: 1f
        val dx = size.width / (series.size - 1)
        fun yOf(v: Float) = size.height - ((v - lo) / span) * size.height
        drawLine(c.outline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
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
