package com.eried.eucplanet.ui.lockdown

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.BuildConfig
import com.eried.eucplanet.R
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ui.dashboard.SpeedGauge
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.Units

/**
 * The Legal Mode Lockdown screen.
 *
 * A separate screen rather than a branch inside DashboardScreen, which is one
 * composable of over four thousand lines. Threading lockdown conditionals
 * through that would be unreviewable, and the normal dashboard has to keep
 * behaving exactly as it does today when the lock is off.
 *
 * Everything here is hard-coded on purpose. The six metrics, their 2 x 3 grid
 * and the 2 x 2 button grid below it read nothing from the rider's dashboard
 * settings, so arming never rewrites those settings to produce the simple
 * layout, and unlocking hands the rider their own layout back untouched.
 */
@Composable
fun LegalLockdownScreen(
    onNavigateToScan: () -> Unit,
    viewModel: LegalLockdownViewModel = hiltViewModel()
) {
    val wheelData by viewModel.wheelData.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val lightBusy by viewModel.lightBusy.collectAsState()
    val legalTiltback by viewModel.legalTiltbackKmh.collectAsState()
    val legalAlarm by viewModel.legalAlarmKmh.collectAsState()
    val speedUnit by viewModel.speedUnit.collectAsState()
    val distanceUnit by viewModel.distanceUnit.collectAsState()
    val tempUnit by viewModel.tempUnit.collectAsState()
    val deviceName by viewModel.connectedDeviceName.collectAsState()
    val brand by viewModel.connectedBrand.collectAsState()
    val model by viewModel.modelName.collectAsState()

    val connected = connectionState == ConnectionState.CONNECTED
    val colors = MaterialTheme.appColors

    var showUnlock by remember { mutableStateOf(false) }
    var showVehicle by remember { mutableStateOf(false) }

    // A blocked legal-mode press from a Flic, a volume key, the watch or the
    // HUD lands here, so the rider is shown the way out instead of the press
    // appearing to do nothing.
    val promptUnlock by LockdownPromptBus.showUnlock.collectAsState()
    LaunchedEffect(promptUnlock) {
        if (promptUnlock) {
            showUnlock = true
            LockdownPromptBus.consume()
        }
    }

    // Locked means locked. Back must not drop out of the mode.
    BackHandler(enabled = true) { }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.appBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The activity is edge to edge, so without this the Bluetooth
                // icon sits under the status bar and the version line under the
                // gesture pill. Same treatment the Overlay Studio uses.
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Chrome: the Bluetooth icon and nothing else. No settings gear, no
            // camera, GPS, PND or navigator icons, no warnings panel, no Flic
            // indicator, no charging button.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateToScan) {
                    Icon(
                        imageVector = if (connected) Icons.Default.BluetoothConnected
                        else Icons.Default.Bluetooth,
                        contentDescription = stringResource(R.string.lockdown_connect),
                        tint = if (connected) colors.connectionActive else colors.connectionIdle
                    )
                }
            }

            SpeedGauge(
                speed = wheelData.speed,
                // The legal tiltback is the ceiling in this mode. A zero would
                // divide by zero inside the gauge, so fall back to a sane dial.
                maxSpeed = if (legalTiltback > 1f) legalTiltback else 25f,
                speedUnit = speedUnit,
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .aspectRatio(1f)
                    .align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(8.dp))

            // Six metrics, 2 columns x 3 rows, matching the app's own default
            // dashboardMetricsColumns of 2. The same six the catalog defaults
            // to: BATTERY, TEMPERATURE, VOLTAGE, CURRENT, LOAD (PWM), TRIP.
            val metrics = listOf(
                stringResource(R.string.metric_chip_battery) to "${wheelData.batteryPercent}%",
                stringResource(R.string.metric_chip_temperature) to "%.0f%s".format(
                    Units.temperature(wheelData.maxTemperature, tempUnit),
                    Units.tempUnit(tempUnit)
                ),
                stringResource(R.string.metric_chip_voltage) to "%.1fV".format(wheelData.voltage),
                stringResource(R.string.metric_chip_current) to "%.1fA".format(wheelData.current),
                stringResource(R.string.metric_chip_load) to "%.0f%%".format(wheelData.pwm),
                stringResource(R.string.metric_chip_trip) to "%.2f %s".format(
                    Units.distance(wheelData.tripDistance, distanceUnit),
                    Units.distanceUnit(distanceUnit)
                )
            )
            for (row in metrics.chunked(2)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for ((label, value) in row) {
                        LockdownMetricPill(label, value, Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Four buttons, 2 columns x 2 rows, so they line up under the six
            // pills. The app's dashboardActionsColumns default is 3; this mode
            // fixes it at 2 rather than reading the setting.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LockdownButton(
                    icon = Icons.Default.Campaign,
                    label = stringResource(R.string.action_horn),
                    enabled = connected,
                    onClick = { viewModel.onHornPress() },
                    modifier = Modifier.weight(1f)
                )
                LockdownButton(
                    icon = Icons.Default.FlashlightOn,
                    label = stringResource(R.string.action_light),
                    enabled = connected && !lightBusy,
                    active = wheelData.lightOn,
                    onClick = { viewModel.onLightToggle() },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LockdownButton(
                    icon = Icons.Default.Speed,
                    label = stringResource(R.string.lockdown_action_speed_limit),
                    enabled = true,
                    onClick = { showUnlock = true },
                    modifier = Modifier.weight(1f)
                )
                LockdownButton(
                    icon = Icons.Default.TwoWheeler,
                    label = stringResource(R.string.lockdown_action_vehicle),
                    enabled = true,
                    onClick = { showVehicle = true },
                    modifier = Modifier.weight(1f)
                )
            }

            // Static. Tapping it does not open About in this mode.
            Text(
                text = "EUC Planet ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }

    if (showUnlock) {
        LockdownUnlockDialog(
            legalTiltbackKmh = legalTiltback,
            legalAlarmKmh = legalAlarm,
            speedUnit = speedUnit,
            onDismiss = { showUnlock = false },
            onTryUnlock = { viewModel.tryUnlock(it) }
        )
    }

    if (showVehicle) {
        LockdownVehicleDialog(
            connected = connected,
            deviceName = deviceName,
            brand = brand,
            model = model,
            legalTiltbackKmh = legalTiltback,
            legalAlarmKmh = legalAlarm,
            odometerKm = wheelData.totalDistance,
            speedUnit = speedUnit,
            distanceUnit = distanceUnit,
            onDismiss = { showVehicle = false }
        )
    }
}

/**
 * Inert by construction: no clickable and no combinedClickable, so there is no
 * ripple and nothing behind it. The pills are a readout in this mode, not a way
 * into the metric detail screens.
 */
@Composable
private fun LockdownMetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.appColors
    val fill = if (colors.tileBackground != Color.Unspecified) colors.tileBackground
    else colors.surfaceVariant
    Box(
        modifier = modifier
            .height(58.dp)
            .background(fill, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
        }
    }
}

@Composable
private fun LockdownButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false
) {
    val colors = MaterialTheme.appColors
    val fill = if (active) colors.gaugeWarn else colors.surface
    // Explicit on-colors that contrast the fill, per the control guidelines.
    val ink = if (active) colors.onPrimary else colors.textPrimary
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = fill,
            contentColor = ink,
            disabledContainerColor = colors.surfaceVariant,
            disabledContentColor = colors.textDisabled
        ),
        modifier = modifier.height(72.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.height(2.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}
