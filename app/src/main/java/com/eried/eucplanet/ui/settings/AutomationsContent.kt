package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.eried.eucplanet.R
import com.eried.eucplanet.service.encodeVolumeCurve
import com.eried.eucplanet.service.parseVolumeCurve
import com.eried.eucplanet.service.pchipInterpolate
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.SunCalculator
import com.eried.eucplanet.util.Units
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AutomationsContent(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settingsState by viewModel.settings.collectAsState()
    val location by viewModel.currentLocation.collectAsState()
    val autoLightsSuspended by viewModel.autoLightsSuspended.collectAsState()
    val settings = settingsState ?: return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Lights Section ---
        BringIntoViewSection(expanded = settings.autoLightsEnabled) {
        Text(stringResource(R.string.auto_lights_title), style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.auto_lights_desc),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f))
            Switch(checked = settings.autoLightsEnabled,
                onCheckedChange = { viewModel.updateAutoLightsEnabled(it) })
        }

        if (settings.autoLightsEnabled) {
            if (autoLightsSuspended) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.appColors.statusWarn.copy(alpha = 0.15f))) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.appColors.statusWarn
                        )
                        Text(
                            stringResource(R.string.auto_lights_suspended),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.appColors.statusWarn
                        )
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.auto_lights_on_before_sunset), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.auto_minutes_fmt, settings.autoLightsOnMinutesBefore),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = settings.autoLightsOnMinutesBefore.toFloat(),
                        onValueChange = { viewModel.updateAutoLightsOnMinutes(it.roundToInt()) },
                        valueRange = 0f..120f,
                        steps = 11
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.auto_lights_off_after_sunrise), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.auto_minutes_fmt, settings.autoLightsOffMinutesAfter),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = settings.autoLightsOffMinutesAfter.toFloat(),
                        onValueChange = { viewModel.updateAutoLightsOffMinutes(it.roundToInt()) },
                        valueRange = 0f..120f,
                        steps = 11
                    )
                }
            }

            // Show computed sunrise/sunset from live GPS
            val loc = location
            if (loc != null) {
                val sunResult = remember(loc.latitude, loc.longitude) {
                    SunCalculator.calculateState(loc.latitude, loc.longitude)
                }
                when (sunResult) {
                    is SunCalculator.SunResult.Normal -> SunScheduleGraph(
                        sunriseMillis = sunResult.sunriseMillis,
                        sunsetMillis = sunResult.sunsetMillis,
                        lightsOnMinutesBefore = settings.autoLightsOnMinutesBefore,
                        lightsOffMinutesAfter = settings.autoLightsOffMinutesAfter,
                        latitude = loc.latitude,
                        longitude = loc.longitude
                    )
                    SunCalculator.SunResult.MidnightSun -> PolarStateCard(
                        title = stringResource(R.string.auto_midnight_sun_title),
                        description = stringResource(R.string.auto_midnight_sun_body),
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accent = MaterialTheme.appColors.gaugeWarn
                    )
                    SunCalculator.SunResult.PolarNight -> PolarStateCard(
                        title = stringResource(R.string.auto_polar_night_title),
                        description = stringResource(R.string.auto_polar_night_body),
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accent = MaterialTheme.appColors.metricVoltage
                    )
                }
            } else {
                HintText(stringResource(R.string.auto_waiting_gps), small = true)
            }
        }
        }   // end Lights BringIntoViewSection

        Spacer(Modifier.height(8.dp))

        // --- Volume Section ---
        BringIntoViewSection(expanded = settings.autoVolumeEnabled) {
        Text(stringResource(R.string.auto_volume_title), style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.auto_volume_desc),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f))
            Switch(checked = settings.autoVolumeEnabled,
                onCheckedChange = { viewModel.updateAutoVolumeEnabled(it) })
        }

        if (settings.autoVolumeEnabled) {
            var points by remember(settings.autoVolumeCurve) {
                mutableStateOf(parseVolumeCurve(settings.autoVolumeCurve))
            }

            // Ensure exactly 4 points at speeds 0/25/50/75. 0 km/h is locked at 1× (baseline).
            val normalizedPoints = remember(points) {
                val p = points.toMutableList()
                listOf(
                    0f to 1f,
                    25f to (p.getOrNull(1)?.second ?: 1.0f),
                    50f to (p.getOrNull(2)?.second ?: 1.5f),
                    75f to (p.getOrNull(3)?.second ?: 2.0f),
                )
            }

            SplineCurveEditor(
                points = normalizedPoints,
                speedUnit = Units.effectiveSpeedUnit(settings),
                onPointsChanged = { newPoints ->
                    points = newPoints
                    viewModel.updateAutoVolumeCurve(encodeVolumeCurve(newPoints))
                }
            )
        }
        }   // end Volume BringIntoViewSection

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PolarStateCard(
    title: String,
    description: String,
    latitude: Double,
    longitude: Double,
    accent: Color
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = accent)
            Text(
                "%.4f, %.4f".format(latitude, longitude),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// --- Sunrise/Sunset schedule graph ---

@Composable
private fun SunScheduleGraph(
    sunriseMillis: Long,
    sunsetMillis: Long,
    lightsOnMinutesBefore: Int,
    lightsOffMinutesAfter: Int,
    latitude: Double = 0.0,
    longitude: Double = 0.0
) {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val sunriseStr = fmt.format(Date(sunriseMillis))
    val sunsetStr = fmt.format(Date(sunsetMillis))
    val lightsOnMillis = sunsetMillis - lightsOnMinutesBefore * 60_000L
    val lightsOffMillis = sunriseMillis + lightsOffMinutesAfter * 60_000L
    val lightsOnStr = fmt.format(Date(lightsOnMillis))
    val lightsOffStr = fmt.format(Date(lightsOffMillis))

    // Night-mode deep blue: a data-viz fill for the night portion of the
    // day/night timeline bar, not app chrome — kept as a literal so it
    // doesn't follow the theme's surface tokens. See report.
    val nightColor = Color(0xFF1A237E)
    val dayColor = MaterialTheme.appColors.gaugeWarn.copy(alpha = 0.25f)
    val lightsMarkerColor = MaterialTheme.appColors.statusGood
    val lightsOnColor = lightsMarkerColor.copy(alpha = 0.35f)
    val sunriseColor = MaterialTheme.appColors.statusWarn
    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val nowMillis = remember { System.currentTimeMillis() }

    // Day boundaries: midnight to midnight
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val dayStartMillis = cal.timeInMillis
    val dayEndMillis = dayStartMillis + 24 * 3600_000L

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.auto_sunrise_sunset), style = MaterialTheme.typography.titleMedium,
                color = sunriseColor)
            if (latitude != 0.0 || longitude != 0.0) {
                Text(
                    "%.4f, %.4f".format(latitude, longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Spacer(Modifier.height(12.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
            ) {
                val w = size.width
                val h = size.height
                val barH = 22f
                val barY = h * 0.48f

                fun timeToX(millis: Long): Float {
                    return ((millis - dayStartMillis).toFloat() / (dayEndMillis - dayStartMillis)) * w
                }

                val sunriseX = timeToX(sunriseMillis)
                val sunsetX = timeToX(sunsetMillis)
                val lightsOnX = timeToX(lightsOnMillis)
                val lightsOffX = timeToX(lightsOffMillis)

                // Night background (full bar)
                drawRoundRect(
                    color = nightColor,
                    topLeft = Offset(0f, barY),
                    size = Size(w, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f)
                )

                // Day portion (sunrise to sunset)
                drawRect(
                    color = dayColor,
                    topLeft = Offset(sunriseX, barY),
                    size = Size(sunsetX - sunriseX, barH)
                )

                // Lights ON zones
                drawRect(
                    color = lightsOnColor,
                    topLeft = Offset(lightsOnX, barY),
                    size = Size(w - lightsOnX, barH)
                )
                drawRect(
                    color = lightsOnColor,
                    topLeft = Offset(0f, barY),
                    size = Size(lightsOffX, barH)
                )

                // Sunrise / sunset markers (solid vertical lines)
                drawLine(sunriseColor, Offset(sunriseX, barY - 4f), Offset(sunriseX, barY + barH + 4f), strokeWidth = 2.5f)
                drawLine(sunriseColor, Offset(sunsetX, barY - 4f), Offset(sunsetX, barY + barH + 4f), strokeWidth = 2.5f)

                // Lights ON/OFF markers (dashed)
                val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                drawLine(lightsMarkerColor, Offset(lightsOnX, barY - 2f), Offset(lightsOnX, barY + barH + 2f),
                    strokeWidth = 1.5f, pathEffect = dash)
                drawLine(lightsMarkerColor, Offset(lightsOffX, barY - 2f), Offset(lightsOffX, barY + barH + 2f),
                    strokeWidth = 1.5f, pathEffect = dash)

                // Now indicator
                val nowX = timeToX(nowMillis)
                if (nowX in 0f..w) {
                    drawLine(Color.White, Offset(nowX, barY - 6f), Offset(nowX, barY + barH + 6f), strokeWidth = 2f)
                }

                // Sun icons further above the bar (gives room for labels)
                val sunIconY = barY - 36f
                drawCircle(sunriseColor, radius = 9f, center = Offset(sunriseX, sunIconY))
                drawCircle(sunriseColor, radius = 9f, center = Offset(sunsetX, sunIconY))

                // Lights ON/OFF labels at top
                val greenStyle = TextStyle(fontSize = 13.sp, color = lightsMarkerColor, fontWeight = FontWeight.Medium)
                val onLabel = textMeasurer.measure("ON $lightsOnStr", greenStyle)
                drawText(onLabel, topLeft = Offset(
                    (lightsOnX - onLabel.size.width / 2f).coerceIn(0f, w - onLabel.size.width),
                    6f))
                val offLabel = textMeasurer.measure("OFF $lightsOffStr", greenStyle)
                drawText(offLabel, topLeft = Offset(
                    (lightsOffX - offLabel.size.width / 2f).coerceIn(0f, w - offLabel.size.width),
                    6f))

                // Sunrise / sunset times further below the bar
                val labelStyle = TextStyle(fontSize = 13.sp, color = labelColor, fontWeight = FontWeight.Medium)
                val riseLabel = textMeasurer.measure(sunriseStr, labelStyle)
                drawText(riseLabel, topLeft = Offset(
                    (sunriseX - riseLabel.size.width / 2f).coerceIn(0f, w - riseLabel.size.width),
                    barY + barH + 22f))
                val setLabel = textMeasurer.measure(sunsetStr, labelStyle)
                drawText(setLabel, topLeft = Offset(
                    (sunsetX - setLabel.size.width / 2f).coerceIn(0f, w - setLabel.size.width),
                    barY + barH + 22f))

                // Hour markers (smaller, below time labels)
                for (hour in listOf(0, 6, 12, 18, 24)) {
                    val x = w * hour / 24f
                    drawLine(labelColor.copy(alpha = 0.15f), Offset(x, barY), Offset(x, barY + barH), strokeWidth = 0.5f)
                    if (hour < 24) {
                        val hourLabel = textMeasurer.measure("${hour}h",
                            TextStyle(fontSize = 9.sp, color = labelColor.copy(alpha = 0.4f)))
                        drawText(hourLabel, topLeft = Offset(x + 2f, barY + barH + 4f))
                    }
                }
            }
        }
    }
}

// --- Editable PCHIP volume-multiplier graph (0 km/h locked at 0x) ---

@Composable
private fun SplineCurveEditor(
    points: List<Pair<Float, Float>>,
    speedUnit: String,
    onPointsChanged: (List<Pair<Float, Float>>) -> Unit
) {
    val maxSpeed = 75f
    val speedUnitLabel = Units.speedUnit(androidx.compose.ui.platform.LocalContext.current, speedUnit)
    val minMultiplier = 1f
    val maxMultiplier = 2f
    val multiplierRange = maxMultiplier - minMultiplier
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = MaterialTheme.appColors.metricVoltage
    val pointColor = MaterialTheme.appColors.statusWarn
    val probeColor = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()

    var dragIndex by remember { mutableStateOf(-1) }
    var probeSpeed by remember { mutableStateOf<Float?>(null) }
    // Use a ref so pointerInput doesn't restart when points change
    val pointsRef = remember { mutableStateOf(points) }
    pointsRef.value = points

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 40.dp, bottom = 28.dp, top = 12.dp, end = 12.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val pts = pointsRef.value

                            val nearest = pts
                                .mapIndexed { idx, (s, v) ->
                                    val px = s / maxSpeed * w
                                    val py = h - (v - minMultiplier) / multiplierRange * h
                                    idx to (offset - Offset(px, py)).getDistance()
                                }
                                .filter { it.second < 100f }
                                .minByOrNull { it.second }

                            // Index 0 (0 km/h) is locked at 0x and not draggable.
                            if (nearest != null && nearest.first != 0) {
                                dragIndex = nearest.first
                                probeSpeed = null
                            } else {
                                dragIndex = -1
                                val speed = (offset.x / w * maxSpeed).coerceIn(0f, maxSpeed)
                                probeSpeed = speed
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()

                            if (dragIndex > 0 && dragIndex < pointsRef.value.size) {
                                var newM = (minMultiplier + (h - change.position.y) / h * multiplierRange)
                                    .coerceIn(minMultiplier, maxMultiplier)
                                // Monotonic ascending: never below previous, never above next.
                                val prevM = pointsRef.value.getOrNull(dragIndex - 1)?.second ?: minMultiplier
                                val nextM = pointsRef.value.getOrNull(dragIndex + 1)?.second ?: maxMultiplier
                                newM = newM.coerceIn(prevM, nextM)
                                val (oldS, _) = pointsRef.value[dragIndex]
                                val mutable = pointsRef.value.toMutableList()
                                mutable[dragIndex] = oldS to newM
                                onPointsChanged(mutable)
                            } else {
                                val speed = (change.position.x / w * maxSpeed).coerceIn(0f, maxSpeed)
                                probeSpeed = speed
                            }
                        },
                        onDragEnd = {
                            dragIndex = -1
                            probeSpeed = null
                        },
                        onDragCancel = {
                            dragIndex = -1
                            probeSpeed = null
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

            // Grid lines, X axis at handle positions (0, 25, 50, 75 km/h internally; converted for label)
            for (i in 0..3) {
                val x = w * i / 3f
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f, pathEffect = dash)
                val speedKmh = maxSpeed * i / 3
                val displaySpeed = Units.speed(speedKmh, speedUnit)
                val label = "${displaySpeed.roundToInt()}"
                val measured = textMeasurer.measure(label, TextStyle(fontSize = 9.sp, color = labelColor))
                drawText(measured, topLeft = Offset(x - measured.size.width / 2f, h + 4f))
            }
            // Y-axis ticks at 1×, 1.5×, 2× (3 lines for clean labels)
            for (i in 0..2) {
                val mult = minMultiplier + i * 0.5f
                val y = h - (mult - minMultiplier) / multiplierRange * h
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = dash)
                val label = if (mult == mult.toInt().toFloat()) "${mult.toInt()}x" else "${mult}x"
                val measured = textMeasurer.measure(label, TextStyle(fontSize = 9.sp, color = labelColor))
                drawText(measured, topLeft = Offset(-measured.size.width - 4f, y - measured.size.height / 2f))
            }

            // Axis label, centered between the "25" and "50" ticks so it doesn't overlap "75"
            val speedLabel = textMeasurer.measure(speedUnitLabel, TextStyle(fontSize = 10.sp, color = labelColor))
            drawText(speedLabel, topLeft = Offset((w - speedLabel.size.width) / 2f, h + 4f))

            // PCHIP curve through control points (smooth, never overshoots between monotonic points)
            if (points.size >= 2) {
                val curvePath = androidx.compose.ui.graphics.Path()
                val steps = 100
                for (i in 0..steps) {
                    val speed = i.toFloat() / steps * maxSpeed
                    val mult = pchipInterpolate(points, speed)
                    val x = speed / maxSpeed * w
                    val y = h - (mult - minMultiplier) / multiplierRange * h
                    if (i == 0) curvePath.moveTo(x, y) else curvePath.lineTo(x, y)
                }
                val fillPath = androidx.compose.ui.graphics.Path()
                fillPath.addPath(curvePath)
                fillPath.lineTo(w, h)
                fillPath.lineTo(0f, h)
                fillPath.close()
                drawPath(fillPath, color = lineColor.copy(alpha = 0.1f))
                drawPath(curvePath, color = lineColor, style = Stroke(width = 3f))
            }

            // Probe line + multiplier readout
            val currentProbe = probeSpeed
            if (currentProbe != null && points.size >= 2) {
                val probeMult = pchipInterpolate(points, currentProbe)
                val px = currentProbe / maxSpeed * w
                val py = h - (probeMult - minMultiplier) / multiplierRange * h

                drawLine(
                    color = probeColor.copy(alpha = 0.5f),
                    start = Offset(px, 0f),
                    end = Offset(px, h),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                )
                drawCircle(color = lineColor, radius = 6f, center = Offset(px, py))

                val displayProbe = Units.speed(currentProbe, speedUnit).roundToInt()
                val probeLabel = "$displayProbe $speedUnitLabel \u2192 ${"%.2f".format(probeMult)}x"
                val probeMeasured = textMeasurer.measure(
                    probeLabel,
                    TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = probeColor)
                )
                // Position label above the dot, shift left if near right edge
                val labelX = (px - probeMeasured.size.width / 2f)
                    .coerceIn(0f, w - probeMeasured.size.width)
                val labelY = (py - probeMeasured.size.height - 12f)
                    .coerceAtLeast(0f)
                drawText(probeMeasured, topLeft = Offset(labelX, labelY))
            }

            // Control point handles. Index 0 (0 km/h locked at 0x) has no visible handle , 
            // the curve simply starts at the origin.
            for ((idx, p) in points.withIndex()) {
                if (idx == 0) continue
                val (s, m) = p
                val cx = s / maxSpeed * w
                val cy = h - (m - minMultiplier) / multiplierRange * h
                drawCircle(color = pointColor, radius = 22f, center = Offset(cx, cy))
                drawCircle(color = Color.Black, radius = 22f, center = Offset(cx, cy),
                    style = Stroke(width = 3f))
                drawCircle(color = Color.White, radius = 6f, center = Offset(cx, cy))
            }
        }
    }
}

