package com.eried.eucplanet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.ble.CompositeWheelAdapter
import com.eried.eucplanet.data.model.CustomBleCommand
import com.eried.eucplanet.data.DiagnosticsLog
import com.eried.eucplanet.diagnostics.DiagnosticCommand
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.appColors

/**
 * Shared Service Mode — a port of the Android Wheel Diagnostics surface. Pick a
 * wheel family and browse/fire its per-adapter diagnostic commands (the same
 * `WheelAdapter.getDiagnosticCommands()` catalogue used on Android), and watch
 * raw telemetry frames stream into the Inspect log via [DiagnosticsLog]. Commands
 * only fire when a wheel is connected; otherwise the catalogue is research-only.
 */
@Composable
internal fun ServiceModeScreen(
    connected: Boolean,
    onFire: (ByteArray) -> Unit,
    onBack: () -> Unit,
    customCommands: List<CustomBleCommand> = emptyList(),
    onAddCustom: (label: String, hex: String) -> Unit = { _, _ -> },
    onDeleteCustom: (String) -> Unit = {},
) {
    val c = MaterialTheme.appColors
    val composite = remember { CompositeWheelAdapter() }
    val families = composite.allFamilies
    var familyIdx by remember { mutableStateOf(0) }
    val commands = remember(familyIdx) { families[familyIdx].getDiagnosticCommands() }
    val logLines by DiagnosticsLog.lines.collectAsState()
    var newLabel by remember { mutableStateOf("") }
    var newHex by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        ScreenTopBar(c, "Service Mode", onBack)

        // Wheel-family picker.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            families.forEachIndexed { i, f ->
                FamilyChip(c, f.familyDisplayName, i == familyIdx) { familyIdx = i }
            }
        }

        Text(
            if (connected) "Tap a command to send it to the wheel." else "Not connected — catalogue is read-only.",
            color = c.textDisabled, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )

        // Command catalogue for the selected family.
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            // User-defined custom commands (saved hex frames) — Android parity.
            Text("CUSTOM COMMANDS", color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
            customCommands.forEach { cc ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(cc.label, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(cc.framesHex.joinToString(" · ").take(48), color = c.textDisabled, fontSize = 10.sp)
                    }
                    Text("Delete", color = c.statusDanger, fontSize = 11.sp, modifier = Modifier.clickable { onDeleteCustom(cc.id) }.padding(horizontal = 8.dp, vertical = 4.dp))
                    Text(
                        "Send", color = if (connected) c.onPrimary else c.textDisabled, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (connected) c.primary else c.surfaceVariant)
                            .clickable(enabled = connected) { cc.framesHex.forEach { parseHexBytes(it)?.let(onFire) } }.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            // Add-form: label + hex (space/comma separated frames → one write each).
            ServiceTextField(c, "Label", newLabel) { newLabel = it }
            Spacer(Modifier.height(4.dp))
            ServiceTextField(c, "Hex frames (space-separated)", newHex) { newHex = it }
            Spacer(Modifier.height(4.dp))
            Text(
                "+ Save command", color = if (newLabel.isNotBlank() && newHex.isNotBlank()) c.primary else c.textDisabled,
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(enabled = newLabel.isNotBlank() && newHex.isNotBlank()) {
                    onAddCustom(newLabel.trim(), newHex.trim()); newLabel = ""; newHex = ""
                }.padding(vertical = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text("DIAGNOSTIC CATALOGUE", color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
            if (commands.isEmpty()) {
                Text("No diagnostic commands for this family.", color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            }
            commands.forEach { cmd ->
                CommandCard(c, cmd, connected) { onFire(cmd.bytes) }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        // Inspect: live raw-frame log.
        InspectLog(c, logLines)
    }
}

@Composable
private fun FamilyChip(c: AppThemeColors, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) c.primary else c.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label, color = if (selected) c.onPrimary else c.textSecondary,
            fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun CommandCard(c: AppThemeColors, cmd: DiagnosticCommand, connected: Boolean, onFire: () -> Unit) {
    val catColor = categoryColor(c, cmd.category)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface)
            .clickable(enabled = connected) { onFire() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(catColor))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cmd.label, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(8.dp))
                Text(cmd.category.name, color = catColor, fontSize = 9.sp, fontWeight = FontWeight.Medium)
            }
            Text(cmd.description, color = c.textSecondary, fontSize = 11.sp)
            Text(hexPreview(cmd.bytes), color = c.textDisabled, fontSize = 10.sp)
        }
        Text(
            "Send",
            color = if (connected) c.onPrimary else c.textDisabled,
            fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (connected) c.primary else c.surfaceVariant)
                .clickable(enabled = connected) { onFire() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun InspectLog(c: AppThemeColors, lines: List<String>) {
    Column(
        Modifier.fillMaxWidth().height(150.dp).background(c.surfaceVariant).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("INSPECT", color = c.sectionHeader, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Clear", color = c.primary, fontSize = 11.sp, modifier = Modifier.clickable { DiagnosticsLog.clear() })
        }
        Spacer(Modifier.height(4.dp))
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
            if (lines.isEmpty()) {
                Text("Raw wheel frames appear here once connected.", color = c.textDisabled, fontSize = 10.sp)
            }
            lines.takeLast(40).forEach { line ->
                Text(line, color = c.textSecondary, fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}

private fun categoryColor(c: AppThemeColors, cat: DiagnosticCommand.Category): Color = when (cat) {
    DiagnosticCommand.Category.HORN -> c.metricAccel
    DiagnosticCommand.Category.LIGHT -> c.statusWarn
    DiagnosticCommand.Category.MODE -> c.metricVoltage
    DiagnosticCommand.Category.QUERY -> c.metricBattery
    DiagnosticCommand.Category.RAW -> c.metricPosition
    DiagnosticCommand.Category.OTHER -> c.textSecondary
}

private fun hexPreview(bytes: ByteArray): String {
    if (bytes.isEmpty()) return "(no bytes)"
    val hex = bytes.take(10).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    return if (bytes.size > 10) "$hex … (${bytes.size}B)" else hex
}

/** Lenient hex parse: ignores spaces/punctuation, needs an even nibble count. */
internal fun parseHexBytes(s: String): ByteArray? {
    val clean = s.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

@Composable
private fun ServiceTextField(c: AppThemeColors, placeholder: String, value: String, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surfaceVariant).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(placeholder, color = c.textDisabled, fontSize = 13.sp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = c.textPrimary, fontSize = 13.sp),
            cursorBrush = SolidColor(c.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
