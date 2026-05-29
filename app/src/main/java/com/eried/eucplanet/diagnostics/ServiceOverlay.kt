package com.eried.eucplanet.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import com.eried.eucplanet.data.model.ActionCatalog
import com.eried.eucplanet.data.model.ActionSpec
import com.eried.eucplanet.data.model.ActionSurface
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.FullMetricHistory
import com.eried.eucplanet.data.repository.MetricSample
import com.eried.eucplanet.ui.settings.metricAccentColor
import com.eried.eucplanet.ui.settings.metricChipLabel
import com.eried.eucplanet.ui.settings.metricDescription
import com.eried.eucplanet.ui.settings.metricSupportsStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot fed to [ServiceModeOverlay]. Captured once when the overlay
 * opens so the values stay frozen while the rider inspects them. The
 * activity re-snapshots after every fire so action-status readouts
 * reflect the new state.
 */
data class ServiceOverlaySnapshot(
    val wheel: WheelData,
    val history: FullMetricHistory,
    /** True while [com.eried.eucplanet.data.repository.TripRepository.recording] is on. */
    val tripRecording: Boolean,
    /** Imperial-unit toggle state, used as the status for TOGGLE_UNITS. */
    val imperialUnits: Boolean,
    /** True when the wheel is currently in safety / legal mode. */
    val safetyActive: Boolean = false,
    /** True when the alarms-muted setting is on. */
    val alarmsMuted: Boolean = false
) {
    /** Projects into the catalog's read-only StatusContext for [com.eried.eucplanet.data.model.ActionSpec.statusReader]. */
    fun toStatusContext(): com.eried.eucplanet.data.model.StatusContext =
        com.eried.eucplanet.data.model.StatusContext(
            wheel = wheel,
            tripRecording = tripRecording,
            imperialUnits = imperialUnits,
            alarmsMuted = alarmsMuted,
            safetyActive = safetyActive
        )
}

/**
 * Global open/close state + the snapshot to render. Lives as a singleton
 * because the volume-key trigger fires from
 * [com.eried.eucplanet.MainActivity.onKeyDown] (outside the Compose tree)
 * and the overlay is rendered from `setContent`.
 */
object ServiceOverlayState {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    private val _snapshot = MutableStateFlow<ServiceOverlaySnapshot?>(null)
    val snapshot: StateFlow<ServiceOverlaySnapshot?> = _snapshot.asStateFlow()

    fun show(snapshot: ServiceOverlaySnapshot) {
        _snapshot.value = snapshot
        _open.value = true
    }

    /** Replace the snapshot without changing open state. Used after the rider fires an action. */
    fun refresh(snapshot: ServiceOverlaySnapshot) {
        _snapshot.value = snapshot
    }

    fun dismiss() {
        _open.value = false
    }
}

/**
 * Compact debug overlay. Two combos with detail panes below each:
 *   - Metric combo → raw / count / min / max / avg / last samples
 *   - Action combo + Fire button → current status (where known)
 *
 * Designed as a developer surface. No realtime updates; the snapshot
 * refreshes after every fire so status readouts catch up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceModeOverlay(
    snapshot: ServiceOverlaySnapshot,
    onFireAction: (key: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Debug",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            Spacer(Modifier.height(8.dp))

            MetricPicker(snapshot = snapshot)

            Spacer(Modifier.height(16.dp))

            ActionPicker(snapshot = snapshot, onFire = onFireAction)

            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetricPicker(snapshot: ServiceOverlaySnapshot) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(serviceMetricKeys.first()) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Metric") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            serviceMetricKeys.forEach { key ->
                DropdownMenuItem(
                    text = { Text(key, fontFamily = FontFamily.Monospace) },
                    onClick = {
                        selected = key
                        expanded = false
                    }
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    DetailBox(text = composeMetricDetailText(selected, snapshot))
}

/** @Composable wrapper so we can read stringResource()-backed metric metadata. */
@Composable
private fun composeMetricDetailText(key: String, snapshot: ServiceOverlaySnapshot): String {
    val label = metricChipLabel(key)
    val description = metricDescription(key)
    val accent = metricAccentColor(key)
    val accentName = accentLabelFor(accent)
    val supportsStats = metricSupportsStats(key)
    val raw = rawMetricValue(key, snapshot.wheel)
    val history = historyFor(key, snapshot.history)
    val hasHistory = !history.isNullOrEmpty()

    return buildString {
        append("key:    ").append(key).append('\n')
        append("label:  ").append(label).append('\n')
        if (description != null) {
            append("desc:   ").append(description).append('\n')
        }
        append("accent: ").append(accentName).append('\n')
        append("stats:  ").append(if (supportsStats) "supported" else "not applicable")
        append('\n')
        append("hist:   ").append(if (hasHistory) "buffer (1Hz, 5min window)" else "none")
        append('\n')
        append("raw:    ").append(raw)
        if (hasHistory) {
            val values = history!!.map { it.value }
            append('\n').append("count:  ").append(values.size)
            append('\n').append("min:    ").append("%.4f".format(values.min()))
            append('\n').append("max:    ").append("%.4f".format(values.max()))
            append('\n').append("avg:    ").append("%.4f".format(values.average()))
            append('\n').append("last:   ").append(values.takeLast(8).joinToString(", ") { "%.2f".format(it) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionPicker(
    snapshot: ServiceOverlaySnapshot,
    onFire: (String) -> Unit
) {
    val keys = remember { ActionCatalog.keysFor(ActionSurface.DASHBOARD) }
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(keys.first()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Action") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                keys.forEach { key ->
                    DropdownMenuItem(
                        text = { Text(key, fontFamily = FontFamily.Monospace) },
                        onClick = {
                            selected = key
                            expanded = false
                        }
                    )
                }
            }
        }
        FilledTonalButton(onClick = { onFire(selected) }) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Fire")
        }
    }
    Spacer(Modifier.height(8.dp))
    DetailBox(text = composeActionDetailText(selected, snapshot))
}

/** @Composable wrapper so we can read stringResource()-backed action labels. */
@Composable
private fun composeActionDetailText(key: String, snapshot: ServiceOverlaySnapshot): String {
    val spec: ActionSpec? = ActionCatalog.byKey(key)
    if (spec == null) {
        return "key: $key\n(not in ActionCatalog)"
    }
    val label = stringResource(spec.labelRes)
    val surfaces = ActionCatalog.surfacesFor(spec).joinToString(", ") { it.name }
    val iconName = spec.icon?.name ?: "(none)"
    val hasStatusReader = spec.statusReader != null
    val statusLine = actionStatusText(key, snapshot)
    return buildString {
        append("key:        ").append(spec.key).append('\n')
        append("label:      ").append(label).append('\n')
        append("icon:       ").append(iconName).append('\n')
        append("eyes-free:  ").append(spec.isEyesFreeSafe).append('\n')
        append("surfaces:   ").append(surfaces).append('\n')
        append("reader:     ").append(if (hasStatusReader) "wired" else "(stub — phase A)").append('\n')
        append("status:     ").append(statusLine)
    }
}

@Composable
private fun DetailBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .padding(10.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * Order matches knownDashboardMetrics. Duplicated here to keep the
 * diagnostics module free of a settings-module dependency.
 * TODO consolidate via a shared MetricCatalog mirror of ActionCatalog.
 */
private val serviceMetricKeys: List<String> = listOf(
    "BATTERY", "TEMPERATURE", "VOLTAGE", "CURRENT", "LOAD", "TRIP",
    "SPEED", "POWER", "ODOMETER",
    "MOTOR_POWER", "BATTERY_POWER",
    "BATTERY_1", "BATTERY_2",
    "PITCH", "ROLL",
    "G_FORCE", "LATERAL_G", "FORWARD_G",
    "TORQUE", "DYN_SPEED_LIMIT", "DYN_CURRENT_LIMIT",
    "MOTOR_TEMP", "CONTROLLER_TEMP", "BATTERY_TEMP",
    "HEADROOM", "TRIP_TIME", "TRIP_MAX_SPEED", "AVG_TRIP_SPEED",
    "WH_CONSUMED", "RANGE_ESTIMATE", "WH_PER_KM",
    "PHONE_BATTERY", "GPS_ALTITUDE", "GPS_SPEED", "GPS_HEADING",
    "GPS_ACCURACY",
    "SLOPE", "ASCENT", "DESCENT", "MOTOR_RPM", "REGEN_WH",
    "BT_RSSI"
)

/**
 * One-line status string for an action — what does the rider see right
 * now? Delegates to [com.eried.eucplanet.data.model.ActionSpec.statusReader]
 * when the catalog wires one, falling back to a "no resting state" note
 * for one-shot actions (HORN, VOICE_ANNOUNCE, MEDIA_*, RESET_TRIP).
 */
private fun actionStatusText(key: String, snapshot: ServiceOverlaySnapshot): String {
    val spec = ActionCatalog.byKey(key) ?: return "(unknown action)"
    val reader = spec.statusReader ?: return "(one-shot, no resting state)"
    val active = reader(snapshot.toStatusContext())
    return "active=$active"
}

/**
 * Reverse-lookup the AccentXxx Color constants so the metric detail box
 * can show a human-readable color name instead of raw ARGB. Falls back
 * to a hex string if a metric uses a non-palette color.
 */
private fun accentLabelFor(color: androidx.compose.ui.graphics.Color): String {
    return when (color.toArgb().toLong() and 0xFFFFFFFFL) {
        com.eried.eucplanet.ui.theme.AccentGreen.toArgb().toLong() and 0xFFFFFFFFL -> "Green"
        com.eried.eucplanet.ui.theme.AccentOrange.toArgb().toLong() and 0xFFFFFFFFL -> "Orange"
        com.eried.eucplanet.ui.theme.AccentBlue.toArgb().toLong() and 0xFFFFFFFFL -> "Blue"
        com.eried.eucplanet.ui.theme.AccentPurple.toArgb().toLong() and 0xFFFFFFFFL -> "Purple"
        com.eried.eucplanet.ui.theme.AccentPink.toArgb().toLong() and 0xFFFFFFFFL -> "Pink"
        else -> "#${(color.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()}"
    }
}

private fun rawMetricValue(key: String, wheel: WheelData): String = when (key) {
    "BATTERY" -> wheel.batteryPercent.toString()
    "TEMPERATURE" -> "%.1f".format(wheel.maxTemperature)
    "VOLTAGE" -> "%.2f".format(wheel.voltage)
    "CURRENT" -> "%.2f".format(wheel.current)
    "LOAD" -> "%.1f".format(wheel.pwm)
    "TRIP" -> "%.3f".format(wheel.tripDistance)
    "SPEED" -> "%.2f".format(wheel.speed)
    "POWER" -> "${wheel.batteryPower}"
    "ODOMETER" -> "%.3f".format(wheel.totalDistance)
    "MOTOR_POWER" -> "${wheel.motorPower}"
    "BATTERY_POWER" -> "${wheel.batteryPower}"
    "BATTERY_1" -> "%.1f".format(wheel.battery1Percent)
    "BATTERY_2" -> "%.1f".format(wheel.battery2Percent)
    "PITCH" -> "%.2f".format(wheel.pitchAngle)
    "ROLL" -> "%.2f".format(wheel.rollAngle)
    "G_FORCE" -> "%.3f".format(wheel.gForce)
    "LATERAL_G" -> "%.3f".format(wheel.accelX)
    "FORWARD_G" -> "%.3f".format(wheel.accelY)
    "TORQUE" -> "%.2f".format(wheel.torque)
    "DYN_SPEED_LIMIT" -> "%.2f".format(wheel.dynamicSpeedLimit)
    "DYN_CURRENT_LIMIT" -> "%.2f".format(wheel.dynamicCurrentLimit)
    "MOTOR_TEMP" -> wheel.temperatures.getOrNull(0)?.let { "%.1f".format(it) } ?: "—"
    "CONTROLLER_TEMP" -> wheel.temperatures.getOrNull(1)?.let { "%.1f".format(it) } ?: "—"
    "BATTERY_TEMP" -> wheel.temperatures.getOrNull(2)?.let { "%.1f".format(it) } ?: "—"
    else -> "—"
}

private fun historyFor(key: String, history: FullMetricHistory): List<MetricSample>? = when (key) {
    "BATTERY" -> history.battery
    "TEMPERATURE" -> history.temperature
    "VOLTAGE" -> history.voltage
    "CURRENT" -> history.current
    "LOAD" -> history.load
    "SPEED" -> history.speed
    else -> null
}
