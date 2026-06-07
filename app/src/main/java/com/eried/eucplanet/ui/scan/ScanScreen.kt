package com.eried.eucplanet.ui.scan

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.eried.eucplanet.BuildConfig
import com.eried.eucplanet.R
import com.eried.eucplanet.ble.BleDevice
import com.eried.eucplanet.ble.virtual.VirtualWheelRegistry
import com.eried.eucplanet.ui.theme.themedSwitchColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onDeviceSelected: () -> Unit,
    onBack: () -> Unit,
    viewModel: ScanViewModel = hiltViewModel()
) {
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val missingPermissions by viewModel.missingPermissions.collectAsState()
    val bluetoothOff by viewModel.bluetoothOff.collectAsState()
    val showAllDevices by viewModel.showAllDevices.collectAsState()
    var simulateExpanded by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    var askedOnce by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = viewModel.refreshPermissions()
        if (granted) {
            permanentlyDenied = false
            viewModel.startScan()
        } else if (askedOnce) {
            // Tried at least twice and it's still denied, the OS won't show
            // the dialog again on its own; redirect to app settings.
            permanentlyDenied = true
        }
        askedOnce = true
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Whether or not they turned it on, re-evaluate: startScan() rechecks
        // Bluetooth state and either scans or re-shows the prompt.
        viewModel.startScan()
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    DisposableEffect(Unit) {
        viewModel.startScan()
        onDispose { viewModel.stopScan() }
    }

    // Re-check permissions when returning from system settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (viewModel.refreshPermissions()) {
                    permanentlyDenied = false
                    viewModel.startScan()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (missingPermissions.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.scan_permission_needed),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            stringResource(
                                if (permanentlyDenied) R.string.scan_permission_body_settings
                                else R.string.scan_permission_body
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = {
                            if (permanentlyDenied) openAppSettings()
                            else permissionLauncher.launch(missingPermissions.toTypedArray())
                        }) {
                            Text(stringResource(
                                if (permanentlyDenied) R.string.action_open_settings
                                else R.string.action_grant_permission
                            ))
                        }
                    }
                }
            } else if (bluetoothOff) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.scan_bluetooth_off_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            stringResource(R.string.scan_bluetooth_off_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = {
                            enableBluetoothLauncher.launch(
                                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            )
                        }) {
                            Text(stringResource(R.string.scan_enable_bluetooth))
                        }
                    }
                }
            } else if (isScanning || selecting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text(stringResource(R.string.scan_scanning), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                Button(onClick = { viewModel.startScan() }) {
                    Text(stringResource(R.string.scan_start))
                }
            }

            if (missingPermissions.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setShowAllDevices(!showAllDevices) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.scan_show_all_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(R.string.scan_show_all_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showAllDevices,
                        onCheckedChange = { viewModel.setShowAllDevices(it) },
                        colors = themedSwitchColors(),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Hoisted out of the LazyColumn so the @Composable collectAsState
            // call sits in a real composable scope. Used to gate the
            // virtual-wheel picker below.
            val diagEnabled by com.eried.eucplanet.diagnostics.DiagnosticsLogger.enabled.collectAsState()
            // Virtual wheel picker is a Service-Mode-only affordance. Previously it
            // also showed in debug builds, but that leaks "fake wheel" entries into
            // the riders' scan list during dogfooding, gate purely on service mode.
            val showVirtualPicker = diagEnabled

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selecting = true
                                viewModel.selectDevice(device)
                                onDeviceSelected()
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${device.rssi} dBm",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Virtual-wheel picker. Sits below the real BLE list, riders looking
                // for their wheel see real devices first; the simulator section is for
                // testing and only available in debug builds or when Service Mode is on.
                if (showVirtualPicker) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { simulateExpanded = !simulateExpanded }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.BugReport,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(17.dp))
                                    Text(
                                        stringResource(R.string.scan_simulate_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        if (simulateExpanded) Icons.Default.KeyboardArrowUp
                                        else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null
                                    )
                                }
                                if (simulateExpanded) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            stringResource(R.string.scan_simulate_subtitle),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        VirtualWheelRegistry.all().forEach { virtual ->
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selecting = true
                                                        viewModel.selectDevice(
                                                            BleDevice(
                                                                name = virtual.displayName,
                                                                address = VirtualWheelRegistry.pseudoAddress(virtual.id),
                                                                rssi = 0
                                                            )
                                                        )
                                                        onDeviceSelected()
                                                    },
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surface
                                                )
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(16.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Album,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = virtual.displayName,
                                                            style = MaterialTheme.typography.titleLarge
                                                        )
                                                        Text(
                                                            text = VirtualWheelRegistry.pseudoAddress(virtual.id),
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

