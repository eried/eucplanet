package com.eried.eucplanet.ui.settings

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.ble.tpms.TpmsAlarm
import com.eried.eucplanet.ble.tpms.TpmsDiscoveredSensor
import com.eried.eucplanet.ble.tpms.TpmsReading
import com.eried.eucplanet.ble.tpms.TpmsSensor
import com.eried.eucplanet.ui.theme.appColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TpmsScreen(
    onBack: () -> Unit,
    viewModel: TpmsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var unbindTarget by remember { mutableStateOf<TpmsSensor?>(null) }

    DisposableEffect(showAdd) {
        if (showAdd) viewModel.startDiscover() else viewModel.stopDiscover()
        onDispose { viewModel.stopDiscover() }
    }

    unbindTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { unbindTarget = null },
            title = { Text(stringResource(R.string.tpms_forget_title)) },
            text = {
                Text(stringResource(R.string.tpms_forget_body,
                    target.label.ifBlank { target.id6Hex }))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unbind(target.id6Hex)
                    unbindTarget = null
                }) {
                    Text(
                        stringResource(R.string.tpms_forget),
                        color = MaterialTheme.appColors.statusDanger,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { unbindTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.section_tpms)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
                containerColor = MaterialTheme.appColors.statusGood,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.tpms_add))
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PressureUnitChooser(state.pressureUnit) { viewModel.setPressureUnit(it) }

            if (state.sensors.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.tpms_empty),
                    color = MaterialTheme.appColors.hint,
                )
            } else {
                state.sensors.forEach { (sensor, reading) ->
                    SensorCard(
                        sensor = sensor,
                        reading = reading,
                        pressureUnit = state.pressureUnit,
                        onForget = { unbindTarget = sensor },
                        onRename = { newLabel -> viewModel.rename(sensor.id6Hex, newLabel) },
                    )
                }
            }
            Spacer(Modifier.height(80.dp)) // FAB clearance
        }

        if (showAdd) {
            AddSensorSheet(
                discovering = state.discovering,
                discovered = state.discovered,
                onDismiss = { showAdd = false },
                onBindDiscovered = { id ->
                    viewModel.bind(id)
                    showAdd = false
                },
                onBindManual = { id, label ->
                    viewModel.bind(id, label)
                    showAdd = false
                },
            )
        }
    }
}

@Composable
private fun PressureUnitChooser(current: String, onPick: (String) -> Unit) {
    val opts = listOf("kpa" to "kPa", "bar" to "bar", "psi" to "psi")
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
        Text(
            stringResource(R.string.tpms_pressure_unit),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.appColors.hint,
        )
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            opts.forEachIndexed { idx, (key, label) ->
                SegmentedButton(
                    selected = current == key,
                    onClick = { onPick(key) },
                    shape = SegmentedButtonDefaults.itemShape(idx, opts.size),
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun SensorCard(
    sensor: TpmsSensor,
    reading: TpmsReading?,
    pressureUnit: String,
    onForget: () -> Unit,
    onRename: (String) -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var labelDraft by remember(sensor.label) { mutableStateOf(sensor.label) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.appColors.tileBackground,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (renaming) {
                        OutlinedTextField(
                            value = labelDraft,
                            onValueChange = { labelDraft = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.tpms_label_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            sensor.label.ifBlank { stringResource(R.string.tpms_unnamed) },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.clickable { renaming = true },
                        )
                        Text(
                            sensor.id6Hex,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.appColors.hint,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                if (renaming) {
                    TextButton(onClick = {
                        onRename(labelDraft.trim())
                        renaming = false
                    }) { Text(stringResource(R.string.action_save)) }
                } else {
                    IconButton(onClick = onForget) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.tpms_forget),
                            tint = MaterialTheme.appColors.statusDanger,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (reading == null) {
                Text(
                    stringResource(R.string.tpms_waiting),
                    color = MaterialTheme.appColors.hint,
                    fontSize = 13.sp,
                )
            } else {
                ReadingRow(reading, pressureUnit)
            }
        }
    }
}

@Composable
private fun ReadingRow(reading: TpmsReading, pressureUnit: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ReadingCell(
            label = stringResource(R.string.tpms_pressure),
            value = reading.pressureKPa?.let { formatPressure(it, pressureUnit) }
                ?: "—",
        )
        ReadingCell(
            label = stringResource(R.string.tpms_temperature),
            value = reading.temperatureC?.let { "%.0f °C".format(it) } ?: "—",
        )
        ReadingCell(
            label = stringResource(R.string.tpms_battery),
            value = reading.batteryPct?.let { "$it %" } ?: "—",
        )
        AlarmChip(reading.alarm)
    }
}

@Composable
private fun ReadingCell(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.appColors.hint, fontSize = 11.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun AlarmChip(alarm: TpmsAlarm) {
    val color: Color = when (alarm) {
        TpmsAlarm.OK -> MaterialTheme.appColors.statusGood
        // High and low both surface as danger -- the rider has to react to
        // either. The label distinguishes them so we don't need a separate
        // "warning" token (the palette doesn't define one).
        TpmsAlarm.HIGH, TpmsAlarm.LOW -> MaterialTheme.appColors.statusDanger
        TpmsAlarm.UNKNOWN -> MaterialTheme.appColors.hint
    }
    val text: String = when (alarm) {
        TpmsAlarm.OK -> stringResource(R.string.tpms_alarm_ok)
        TpmsAlarm.HIGH -> stringResource(R.string.tpms_alarm_high)
        TpmsAlarm.LOW -> stringResource(R.string.tpms_alarm_low)
        TpmsAlarm.UNKNOWN -> "—"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSensorSheet(
    discovering: Boolean,
    discovered: List<TpmsDiscoveredSensor>,
    onDismiss: () -> Unit,
    onBindDiscovered: (String) -> Unit,
    onBindManual: (String, String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var tab by remember { mutableStateOf(0) }
    var manualId by remember { mutableStateOf("") }
    var manualLabel by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.tpms_add),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.tpms_add_nearby)) }
                SegmentedButton(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.tpms_add_manual)) }
            }
            Spacer(Modifier.height(12.dp))

            when (tab) {
                0 -> NearbyList(discovering, discovered, onBindDiscovered)
                else -> ManualEntry(
                    id = manualId,
                    onIdChange = { manualId = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                    label = manualLabel,
                    onLabelChange = { manualLabel = it.take(40) },
                    onSubmit = {
                        if (manualId.length == 6) onBindManual(manualId, manualLabel.trim())
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NearbyList(
    discovering: Boolean,
    discovered: List<TpmsDiscoveredSensor>,
    onBind: (String) -> Unit,
) {
    if (discovered.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (discovering) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                stringResource(R.string.tpms_scanning_hint),
                color = MaterialTheme.appColors.hint,
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
        items(discovered, key = { it.id6Hex }) { d ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBind(d.id6Hex) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(rssiColor(d.rssi)),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(d.id6Hex, fontFamily = FontFamily.Monospace)
                    Text(
                        "${d.rssi} dBm",
                        color = MaterialTheme.appColors.hint,
                        fontSize = 11.sp,
                    )
                }
                Text(
                    stringResource(R.string.tpms_bind),
                    color = MaterialTheme.appColors.statusGood,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ManualEntry(
    id: String,
    onIdChange: (String) -> Unit,
    label: String,
    onLabelChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = id,
        onValueChange = onIdChange,
        label = { Text(stringResource(R.string.tpms_id_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = label,
        onValueChange = onLabelChange,
        label = { Text(stringResource(R.string.tpms_label_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onSubmit,
        enabled = id.length == 6,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.tpms_bind)) }
}

private fun rssiColor(rssi: Int): Color = when {
    rssi >= -60 -> Color(0xFF4CAF50)  // strong
    rssi >= -75 -> Color(0xFFFFC107)  // medium
    else -> Color(0xFFEF5350)          // weak
}

/** Convert kPa to the rider's chosen display unit, with a unit suffix. */
fun formatPressure(kPa: Float, unit: String): String = when (unit) {
    "bar" -> "%.2f bar".format(kPa / 100f)
    "psi" -> "%.1f psi".format(kPa * 0.145038f)
    else -> "%.0f kPa".format(kPa)
}

