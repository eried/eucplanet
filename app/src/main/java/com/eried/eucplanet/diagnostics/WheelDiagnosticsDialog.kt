package com.eried.eucplanet.diagnostics

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen Service-Mode dialog. Three tabs:
 *  - Log: live BLE-traffic view, comment box, share button.
 *  - Commands: per-wheel test command grid (e.g. P6 light variants).
 *  - Raw: free-form hex sender for one-off probes.
 *
 * The header bar carries the recording dot, a Stop-recording button (which
 * disables the logger and clears the buffer), and a Close arrow that just
 * dismisses the dialog (logger keeps recording in the background, version
 * text in the dashboard keeps blinking).
 */
@Composable
fun WheelDiagnosticsDialog(
    onDismiss: () -> Unit,
    vm: WheelDiagnosticsViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { vm.captureSessionInfo() }

    var stopConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(8.dp)
            ) {
                Header(
                    onClose = onDismiss,
                    onStop = { stopConfirm = true },
                    onShare = {
                        vm.buildShareIntent()?.let {
                            context.startActivity(Intent.createChooser(it, "Share log"))
                        }
                    }
                )

                var tab by remember { mutableStateOf(0) }
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    Tab(selected = tab == 0, onClick = { tab = 0 },
                        text = { Text("Commands") })
                    Tab(selected = tab == 1, onClick = { tab = 1 },
                        text = { Text("Raw") })
                }

                // ~70% of vertical space goes to the active tab; ~30% to the
                // always-visible log so the user sees the wheel's RX response
                // to whatever they tap regardless of which tab they're on.
                Box(modifier = Modifier.weight(2f, fill = true)) {
                    when (tab) {
                        0 -> CommandsTab(vm)
                        1 -> RawTab(vm)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                LogPanel(modifier = Modifier.weight(1f, fill = true))

                Spacer(Modifier.height(6.dp))

                CommentRow(vm)
            }
        }
    }

    if (stopConfirm) {
        AlertDialog(
            onDismissRequest = { stopConfirm = false },
            title = { Text("Exit Service Mode") },
            text = {
                Text("This clears the in-memory log and exits Service Mode.\n\nShare the log first if you need it.")
            },
            confirmButton = {
                TextButton(onClick = {
                    stopConfirm = false
                    vm.stopDiagnostics()
                    onDismiss()
                }) { Text("Exit") }
            },
            dismissButton = {
                TextButton(onClick = { stopConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun Header(onClose: () -> Unit, onStop: () -> Unit, onShare: () -> Unit) {
    val entries = DiagnosticsLogger.entries.collectAsState().value
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close")
        }
        Box(
            modifier = Modifier.size(10.dp).background(Color.Red, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Service Mode", style = MaterialTheme.typography.titleMedium)
            Text(
                "${entries.size} entries · recording",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = "Share")
        }
        IconButton(onClick = onStop) {
            Icon(Icons.Default.Stop, contentDescription = "Stop")
        }
    }
}

@Composable
private fun LogPanel(modifier: Modifier = Modifier) {
    val entries by DiagnosticsLogger.entries.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        items(entries) { e -> LogRow(e) }
        if (entries.isEmpty()) {
            item {
                Text(
                    "No traffic yet. Connect to a wheel or fire a test command.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CommentRow(vm: WheelDiagnosticsViewModel) {
    var comment by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = comment,
            onValueChange = { comment = it },
            modifier = Modifier.weight(1f),
            label = { Text("Add comment to log") },
            placeholder = { Text("e.g. wheel display says 77F") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            singleLine = false,
            maxLines = 2
        )
        IconButton(onClick = {
            vm.addComment(comment)
            comment = ""
        }) { Icon(Icons.Default.Send, contentDescription = "Add") }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    defaultExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                              else Icons.Default.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column { content() }
        }
    }
}

@Composable
private fun LogRow(e: DiagnosticsLogger.Entry) {
    val ts = remember(e.timestampMs) {
        TIME_FMT.format(Date(e.timestampMs))
    }
    val color = when (e.kind) {
        DiagnosticsLogger.Kind.RX -> Color(0xFF7AC6F0)
        DiagnosticsLogger.Kind.TX -> Color(0xFFF0B97A)
        DiagnosticsLogger.Kind.NOTE -> MaterialTheme.colorScheme.onSurfaceVariant
        DiagnosticsLogger.Kind.CMD -> Color(0xFFB87AF0)
        DiagnosticsLogger.Kind.COMMENT -> Color(0xFF7AF0A0)
        DiagnosticsLogger.Kind.INFO -> MaterialTheme.colorScheme.primary
    }
    Text(
        "$ts ${e.kind.name.padEnd(7)} ${e.text}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun CommandsTab(vm: WheelDiagnosticsViewModel) {
    val cmds = remember { vm.diagnosticCommands() }
    if (cmds.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No diagnostic commands defined for this wheel.\nUse the Raw tab to send arbitrary bytes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val grouped = remember(cmds) { cmds.groupBy { it.category } }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        grouped.forEach { (category, list) ->
            item {
                Text(
                    category.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
            }
            item {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = (((list.size + 2) / 3) * 64 + 16).dp)
                ) {
                    items(list) { cmd ->
                        OutlinedButton(
                            onClick = { vm.fireCommand(cmd) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(cmd.label, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    cmd.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RawTab(vm: WheelDiagnosticsViewModel) {
    var raw by remember { mutableStateOf("") }
    // Single-step undo: holds the previous text the moment a change happens,
    // so the undo button can pull the user back from an accidental edit.
    var prev by remember { mutableStateOf<String?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(WheelDiagnosticsViewModel.WrapMode.LITERAL) }

    fun setRaw(new: String) {
        if (new != raw) prev = raw
        raw = new
    }
    fun appendBytes(s: String) {
        val joined = if (raw.isBlank() || raw.endsWith(' ')) raw + s else "$raw $s"
        setRaw(joined)
    }
    fun appendChar(c: Char) = setRaw(raw + c)

    val preview = remember(raw, mode) { vm.previewHex(raw, mode) }
    val canSend = preview.ok

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp)
    ) {
        CollapsibleSection(title = "Insert preset", defaultExpanded = false) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
                val chips = listOf(
                    "60 50 00 00" to "Light off",
                    "60 50 01 01" to "Light on",
                    "60 51 18 01" to "Horn",
                    "60 2f 00" to "Auto-headlight off",
                    "60 2f 01" to "Auto-headlight on",
                    "60 4e 00" to "DRL? off",
                    "60 4e 01" to "DRL? on",
                    "60 24 00" to "25 km/h clamp off",
                    "60 24 01" to "25 km/h clamp on",
                    "60 31 01" to "Lock",
                    "60 31 00" to "Unlock",
                    "02 06" to "Info bundle",
                    "02 07" to "Realtime",
                    "20 20" to "Settings page A",
                    "20 21" to "Settings B (untried)",
                    "20 22" to "Settings C (untried)",
                    "11" to "Total stats"
                )
                chips.forEach { (bytes, desc) ->
                    AssistChip(
                        onClick = { appendBytes(bytes) },
                        label = {
                            Column {
                                Text(
                                    bytes,
                                    style = MaterialTheme.typography.labelMedium
                                        .copy(fontFamily = FontFamily.Monospace)
                                )
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        modifier = Modifier.padding(end = 6.dp).height(56.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Hex input row: text field on the left, Format / cls (with confirm)
        // stacked on the right so they don't crowd the field.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = raw,
                onValueChange = { setRaw(it) },
                modifier = Modifier.weight(1f).heightIn(min = 80.dp),
                label = { Text("Hex input") },
                placeholder = { Text("60 50 01 01") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                isError = !preview.ok && raw.isNotBlank()
            )
            Spacer(Modifier.width(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { setRaw(vm.formatHex(raw)) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) { Text("fmt") }
                OutlinedButton(
                    onClick = {
                        if (raw.isNotEmpty()) clearConfirm = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) { Text("cls") }
            }
        }

        Spacer(Modifier.height(6.dp))

        CollapsibleSection(title = "Hex pad", defaultExpanded = false) {
            val hexChars = listOf('0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F')
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    hexChars.subList(0, 8).forEach { c ->
                        OutlinedButton(
                            onClick = { appendChar(c) },
                            modifier = Modifier.weight(1f).height(40.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text(c.toString(), fontFamily = FontFamily.Monospace) }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    hexChars.subList(8, 16).forEach { c ->
                        OutlinedButton(
                            onClick = { appendChar(c) },
                            modifier = Modifier.weight(1f).height(40.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text(c.toString(), fontFamily = FontFamily.Monospace) }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    OutlinedButton(
                        onClick = { appendChar(' ') },
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("space", style = MaterialTheme.typography.labelSmall) }
                    OutlinedButton(
                        onClick = { if (raw.isNotEmpty()) setRaw(raw.dropLast(1)) },
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Icon(Icons.Default.Backspace, contentDescription = "Backspace", modifier = Modifier.size(18.dp)) }
                    OutlinedButton(
                        onClick = { prev?.let { raw = it; prev = null } },
                        enabled = prev != null,
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp)) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "Wrap mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))

        val modes = listOf(
            WheelDiagnosticsViewModel.WrapMode.LITERAL to "Literal",
            WheelDiagnosticsViewModel.WrapMode.WRAP_EXTENDED to "Wrap V2",
            WheelDiagnosticsViewModel.WrapMode.WRAP_V14_SHORT to "Wrap V14"
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (m, label) ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { mode = m },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                ) { Text(label) }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Bytes-to-send row: read-only field on the left, big SEND on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = preview.display,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.weight(1f).heightIn(min = 80.dp),
                label = { Text("Bytes to send") },
                placeholder = { Text("—") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                isError = preview.error != null
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = { vm.fireRawHex(raw, mode) },
                enabled = canSend,
                modifier = Modifier.height(80.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("SEND", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("Clear hex input?") },
            text = { Text("The current bytes will be discarded. You can press Undo afterwards if it was a mistake.") },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirm = false
                    setRaw("")
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}


private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
