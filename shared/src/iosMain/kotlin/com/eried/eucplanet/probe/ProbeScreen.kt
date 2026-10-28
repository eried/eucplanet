package com.eried.eucplanet.probe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.theme.BuiltInThemes
import com.eried.eucplanet.ui.theme.LocalAppColors
import com.eried.eucplanet.ui.theme.toColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.IosWheelLink
import com.eried.eucplanet.ble.virtual.VirtualWheelRegistry
import com.eried.eucplanet.platform.format

/**
 * Milestone 1 probe: scan, connect, live speed and battery from the shared
 * decoders. Temporary and English-only; the real Dashboard and Scan screens
 * replace it when they move to the shared module (docs/IOS_PARITY.md).
 */
@Composable
fun ProbeScreen(link: IosWheelLink, session: ProbeSession, initialTab: Int = 0) {
    val colors = BuiltInThemes.pureBlack.colors
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = colors.toColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                    var tab by remember { mutableStateOf(initialTab) }
                    TabRow(selectedTabIndex = tab) {
                        Tab(tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(Res.string.section_connection)) })
                        Tab(tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(Res.string.section_cloud_settings)) })
                    }
                    Spacer(Modifier.height(12.dp))
                    if (tab == 1) {
                        BackupPane()
                    } else {
                        val state by link.connectionState.collectAsState()
                        if (state == ConnectionState.DISCONNECTED) ScanList(link) else LiveData(link, session, state)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanList(link: IosWheelLink) {
    val found by link.scanResults.collectAsState()
    val btOn by link.bluetoothOn.collectAsState()
    DisposableEffect(btOn) {
        link.startScan()
        onDispose { link.stopScan() }
    }
    Text("EUC Planet", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Text(if (btOn) "Scanning for wheels" else "Bluetooth is off or not allowed")
    Spacer(Modifier.height(12.dp))
    LazyColumn(Modifier.fillMaxWidth()) {
        items(found, key = { it.address }) { w ->
            Row(
                Modifier.fillMaxWidth().clickable { link.connect(w.address, w.name, false) }.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(w.name ?: w.address)
                Text("${w.rssi} dBm")
            }
            HorizontalDivider()
        }
        item {
            Spacer(Modifier.height(24.dp))
            Text("Virtual wheels", fontWeight = FontWeight.Bold)
        }
        items(VirtualWheelRegistry.all(), key = { it.id }) { v ->
            Text(
                v.displayName,
                Modifier.fillMaxWidth()
                    .clickable { link.connect(VirtualWheelRegistry.pseudoAddress(v.id), null, false) }
                    .padding(vertical = 12.dp),
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun LiveData(link: IosWheelLink, session: ProbeSession, state: ConnectionState) {
    val data by session.wheelData.collectAsState()
    val name by link.connectedDeviceName.collectAsState()
    val brand by link.connectedBrand.collectAsState()
    val model by session.modelName.collectAsState()
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name ?: "", fontSize = 20.sp)
        Text(listOfNotNull(brand, model).joinToString(" "))
        Text(state.name.lowercase())
        Spacer(Modifier.height(48.dp))
        Text("%.1f".format(data.speed), fontSize = 96.sp, fontWeight = FontWeight.Bold)
        Text("km/h")
        Spacer(Modifier.height(32.dp))
        Text("${data.batteryPercent}%", fontSize = 48.sp)
        Text("battery, %.1f V".format(data.voltage))
        Spacer(Modifier.height(48.dp))
        Button(onClick = { link.disconnect() }) { Text("Disconnect") }
    }
}
