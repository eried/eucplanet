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
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AttachFile
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    // Session-info dump is one-shot per enable cycle so reopening the dialog
    // doesn't duplicate the phone / wheel / Wear block in the log.
    LaunchedEffect(Unit) {
        if (DiagnosticsLogger.shouldCaptureSessionInfo()) vm.captureSessionInfo()
    }

    var stopConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // Force a light + rectangular theme regardless of the user's app
        // theme so Service Mode reads as a different surface, clearly
        // "you are in research mode" the moment the dialog opens. All
        // child components inherit these via MaterialTheme.
        androidx.compose.material3.MaterialTheme(
            colorScheme = androidx.compose.material3.lightColorScheme(),
            shapes = androidx.compose.material3.Shapes(
                extraSmall = RoundedCornerShape(0.dp),
                small = RoundedCornerShape(0.dp),
                medium = RoundedCornerShape(0.dp),
                large = RoundedCornerShape(0.dp),
                extraLarge = RoundedCornerShape(0.dp)
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
                    .imePadding()
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

                // Cap the log at a small fixed slice of the screen so the
                // active tab below always has enough room to scroll all the
                // way to its bottom (e.g. the Send button under the Raw
                // tab's hex pad + wrap mode + bytes-to-send rows). Smaller
                // floor than the full screen means the log can collapse to
                // almost nothing on short screens with the keyboard up.
                LogPanel(modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 180.dp))

                Spacer(Modifier.height(4.dp))

                CommentRow(vm)

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                var tab by remember { mutableStateOf(0) }
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    Tab(selected = tab == 0, onClick = { tab = 0 },
                        text = { Text("Commands") })
                    Tab(selected = tab == 1, onClick = { tab = 1 },
                        text = { Text("Inspector") })
                    Tab(selected = tab == 2, onClick = { tab = 2 },
                        text = { Text("Raw") })
                }

                Box(modifier = Modifier.weight(1f, fill = true)) {
                    when (tab) {
                        0 -> CommandsTab(vm)
                        1 -> InspectTab(vm)
                        2 -> RawTab(vm)
                    }
                }
            }
        }
        }  // close MaterialTheme override
    }

    if (stopConfirm) {
        AlertDialog(
            onDismissRequest = { stopConfirm = false },
            title = { Text("Exit Service Mode") },
            text = {
                Text("This clears the in-memory log and exits Service Mode.\n\nShare the log first if you need it.")
            },
            confirmButton = {
                TextButton(shape = RoundedCornerShape(0.dp), onClick = {
                    stopConfirm = false
                    vm.stopDiagnostics()
                    onDismiss()
                }) { Text("Exit") }
            },
            dismissButton = {
                TextButton(shape = RoundedCornerShape(0.dp), onClick = { stopConfirm = false }) { Text("Cancel") }
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
    // Auto-scroll to the bottom whenever a new entry lands. Yield once so
    // the LazyColumn has actually laid out the new item before we ask the
    // state to scroll to it, otherwise animateScrollToItem races the layout
    // and the request gets dropped, especially right after a comment send.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            kotlinx.coroutines.yield()
            listState.scrollToItem(entries.size - 1)
        }
    }
    // Matrix-terminal aesthetic stays isolated from the rest of Service
    // Mode's forced-light theme. Per-kind hues are picked in LogRow.
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .border(1.dp, Color(0xFF00FF41).copy(alpha = 0.3f))
            .padding(6.dp)
    ) {
        items(entries) { e -> LogRow(e) }
        if (entries.isEmpty()) {
            item {
                Text(
                    "No traffic yet. Connect to a wheel or fire a test command.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = Color(0xFF00FF41).copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun CommentRow(vm: WheelDiagnosticsViewModel) {
    var comment by remember { mutableStateOf("") }
    var showAttachDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Single-line + fixed height: keeps the row from growing as the user
        // types, which would otherwise shrink the log panel mid-input and
        // throw off the auto-scroll-to-bottom heuristic.
        val send = {
            if (comment.isNotBlank()) {
                vm.addComment(comment)
                comment = ""
            }
        }
        OutlinedTextField(shape = RoundedCornerShape(0.dp), 
            value = comment,
            onValueChange = { comment = it },
            modifier = Modifier.weight(1f),
            label = { Text("Add comment to log") },
            placeholder = { Text("e.g. wheel display says 77F") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
            singleLine = true
        )
        IconButton(onClick = { showAttachDialog = true }) {
            Icon(Icons.Default.AttachFile, contentDescription = "Attach data")
        }
        IconButton(onClick = { send() }) { Icon(Icons.Default.Send, contentDescription = "Add") }
    }
    if (showAttachDialog) {
        AttachDataDialog(
            onDismiss = { showAttachDialog = false },
            onSubmit = { selected ->
                vm.attach(selected)
                showAttachDialog = false
            }
        )
    }
}

/**
 * Opt-in attach picker. Each box maps to a [WheelDiagnosticsViewModel.AttachCategory];
 * confirming dumps the selected categories into the live log as NOTE entries.
 * Nothing leaves the device until the user shares the log themselves.
 */
@Composable
private fun AttachDataDialog(
    onDismiss: () -> Unit,
    onSubmit: (Set<WheelDiagnosticsViewModel.AttachCategory>) -> Unit
) {
    val selected = remember {
        mutableStateMapOf<WheelDiagnosticsViewModel.AttachCategory, Boolean>().apply {
            WheelDiagnosticsViewModel.AttachCategory.entries.forEach { put(it, false) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach data to log") },
        text = {
            Column {
                Text(
                    "Picks below are dumped into THIS log only. Nothing is sent until you share the log yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                WheelDiagnosticsViewModel.AttachCategory.entries.forEach { cat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected[cat] = !(selected[cat] ?: false) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selected[cat] ?: false,
                            onCheckedChange = { selected[cat] = it }
                        )
                        Text(cat.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            val any = selected.values.any { it }
            TextButton(shape = RoundedCornerShape(0.dp), 
                enabled = any,
                onClick = {
                    onSubmit(selected.filterValues { it }.keys)
                }
            ) { Text("Attach") }
        },
        dismissButton = {
            TextButton(shape = RoundedCornerShape(0.dp), onClick = onDismiss) { Text("Cancel") }
        }
    )
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
        DiagnosticsLogger.Kind.RX -> Color(0xFF40C4FF)
        DiagnosticsLogger.Kind.TX -> Color(0xFFFFAB40)
        DiagnosticsLogger.Kind.CMD -> Color(0xFFE040FB)
        DiagnosticsLogger.Kind.NOTE -> Color(0xFF4DD0E1)
        DiagnosticsLogger.Kind.INFO -> Color(0xFF00FF41)
        DiagnosticsLogger.Kind.COMMENT -> Color(0xFFFFEB3B)
    }
    Text(
        "$ts ${e.kind.name.padEnd(7)} ${e.text}",
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 12.sp
        ),
        color = color,
        softWrap = false,
        maxLines = 1,
        overflow = TextOverflow.Clip
    )
}

@Composable
private fun CommandsTab(vm: WheelDiagnosticsViewModel) {
    // Wheel-family picker at the top so the catalogue is browsable
    // regardless of what's actually connected, useful when the user wants
    // to research a different family than the one they're paired with.
    val model by vm.modelName.collectAsState()
    val families = remember(model) { vm.allWheelFamilies() }
    var selectedFamily by remember(families) { mutableStateOf(families.firstOrNull()) }
    var familyMenuExpanded by remember { mutableStateOf(false) }
    val cmds = selectedFamily?.commands ?: emptyList()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.padding(top = 8.dp, start = 4.dp)) {
            OutlinedButton(shape = RoundedCornerShape(0.dp), onClick = { familyMenuExpanded = true }) {
                Text(selectedFamily?.displayName ?: "(no families)")
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = familyMenuExpanded,
                onDismissRequest = { familyMenuExpanded = false }
            ) {
                families.forEach { f ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(f.displayName)
                                if (f.commands.isEmpty()) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "(empty)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        onClick = { selectedFamily = f; familyMenuExpanded = false }
                    )
                }
            }
        }

        if (cmds.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No diagnostic commands defined for ${selectedFamily?.displayName ?: "this family"} yet.\nUse the Raw tab to send arbitrary bytes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        val grouped = remember(cmds) { cmds.groupBy { it.category } }

        // Each command button has a fixed height that fits the label + a
        // 2-line description; this keeps the grid uniform regardless of
        // description length and lets the heightIn() calculation below land
        // exactly on the row's true size (so the last row can't bleed
        // outside its slot and get clipped). Bottom contentPadding gives
        // the final category breathing room from the dialog edge.
        val commandButtonHeight = 80.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
            contentPadding = PaddingValues(bottom = ScrollTailPadding)
        ) {
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
                    val rows = (list.size + 2) / 3
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            // Each row = button height (80) + vertical spacing (6).
                            // Plus the contentPadding's 2*2dp.
                            .heightIn(max = (rows * 80 + (rows - 1).coerceAtLeast(0) * 6 + 4).dp)
                    ) {
                        items(list) { cmd ->
                            OutlinedButton(
                                shape = RoundedCornerShape(0.dp),
                                onClick = { vm.fireCommand(cmd) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(commandButtonHeight),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Column {
                                    Text(
                                        cmd.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
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
}

/**
 * Strip the family-display-name prefix from an inspect message-type string
 * so the dropdown label reads tightly. The family is already named in the
 * picker to the left, so "KingSong realtime" becomes "Realtime",
 * "InMotion V1 slow-info" becomes "Slow-info", and so on. Multi-prefix
 * families that don't share a common stem (InMotion V2: "V14 realtime",
 * "P6 realtime", "P6 detailed") fall through and render unchanged.
 */
private fun shortInspectLabel(prefix: String, familyDisplayName: String): String {
    val candidates = listOf(
        familyDisplayName,
        familyDisplayName.split(" / ").first(),
        familyDisplayName.split(" ").take(2).joinToString(" "),
        familyDisplayName.split(" ").first()
    ).filter { it.isNotBlank() }.distinct()
    for (c in candidates) {
        val trimmed = prefix.removePrefix(c).trimStart()
        if (trimmed != prefix && trimmed.isNotEmpty()) {
            return trimmed.replaceFirstChar { it.uppercase() }
        }
    }
    return prefix.replaceFirstChar { it.uppercase() }
}

/**
 * Live byte interpreter. Picks the most recent NOTE entry whose text starts
 * with the selected message type prefix and renders every byte as a small
 * tappable cell showing offset, hex, and decimal. Tapping a cell drops a
 * structured COMMENT into the log so the user can correlate the byte they
 * just clicked with whatever value the wheel's own UI is showing, useful
 * for finding unknown offsets like motor temp.
 */
@Composable
private fun InspectTab(vm: WheelDiagnosticsViewModel) {
    val entries by vm.entries.collectAsState()
    // Folderized: pick a wheel family first, then a message type within it.
    // Families with no inspect prefixes are filtered out so the picker only
    // shows actionable rows.
    val families = remember { vm.allWheelFamilies().filter { it.inspectPrefixes.isNotEmpty() } }
    var selectedFamily by remember { mutableStateOf(families.firstOrNull()) }
    val types = selectedFamily?.inspectPrefixes ?: emptyList()
    var selected by remember(selectedFamily) {
        mutableStateOf(types.firstOrNull() ?: "")
    }
    var familyMenuExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    if (selectedFamily == null) {
        Text(
            "No wheel families publish realtime traces yet.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp)
        )
        return
    }

    val latestBytes: List<Int> = remember(entries, selected) {
        val match = entries.lastOrNull {
            it.kind == DiagnosticsLogger.Kind.NOTE && it.text.startsWith("$selected len=")
        } ?: return@remember emptyList()
        match.text.substringAfter("body=", "").trim()
            .split(' ')
            .mapNotNull { runCatching { it.toInt(16) }.getOrNull() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        // Two dropdowns side-by-side: pick a wheel family, then pick a
        // message type within it. Families with multiple prefixes show all
        // their options; families with one prefix auto-pick it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(shape = RoundedCornerShape(0.dp), onClick = { familyMenuExpanded = true }) {
                    Text(selectedFamily?.displayName ?: "")
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = familyMenuExpanded,
                    onDismissRequest = { familyMenuExpanded = false }
                ) {
                    families.forEach { f ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(f.displayName) },
                            onClick = { selectedFamily = f; familyMenuExpanded = false }
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // Always show the message-type dropdown; disable it when there's
            // only one option so the UI is consistent across families. The
            // label strips the family-name prefix (e.g. "KingSong realtime"
            // displays as "Realtime", "InMotion V1 slow-info" as "Slow-info")
            // since the family is already named in the picker to the left.
            val familyName = selectedFamily?.displayName ?: ""
            val singleOption = types.size <= 1
            Box {
                OutlinedButton(shape = RoundedCornerShape(0.dp), 
                    onClick = { if (!singleOption) menuExpanded = true },
                    enabled = !singleOption
                ) {
                    Text(shortInspectLabel(selected, familyName).ifEmpty { "(message)" })
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    types.forEach { t ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(shortInspectLabel(t, familyName)) },
                            onClick = { selected = t; menuExpanded = false }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "Tap a byte to log a comment with its offset and value.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        if (latestBytes.isEmpty()) {
            Text(
                "No \"$selected\" frames in the log yet. Connect the wheel and wait for one to arrive.",
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }

        // Range filter: hide bytes outside [min..max]. Empty input on either
        // side means "no bound on that side". Hidden cells render dimly so
        // the byte offsets stay aligned in the grid (otherwise tapping the
        // wrong cell to log would be too easy).
        var minFilterText by rememberSaveable { mutableStateOf("") }
        var maxFilterText by rememberSaveable { mutableStateOf("") }
        val minFilter = minFilterText.toIntOrNull()
        val maxFilter = maxFilterText.toIntOrNull()
        fun outOfRange(v: Int): Boolean =
            (minFilter != null && v < minFilter) || (maxFilter != null && v > maxFilter)
        val hiddenCount = latestBytes.count { outOfRange(it) }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        ) {
            androidx.compose.material3.OutlinedTextField(shape = RoundedCornerShape(0.dp), 
                value = minFilterText,
                onValueChange = { v -> minFilterText = v.filter { it.isDigit() } },
                label = { Text("min", style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                modifier = Modifier.width(80.dp),
                textStyle = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.width(6.dp))
            androidx.compose.material3.OutlinedTextField(shape = RoundedCornerShape(0.dp), 
                value = maxFilterText,
                onValueChange = { v -> maxFilterText = v.filter { it.isDigit() } },
                label = { Text("max", style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                modifier = Modifier.width(80.dp),
                textStyle = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.width(8.dp))
            if (hiddenCount > 0) {
                Text(
                    "($hiddenCount hidden)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            if (minFilter != null || maxFilter != null) {
                IconButton(onClick = { minFilterText = ""; maxFilterText = "" }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear filter")
                }
            }
        }

        // 6-column grid of byte cells. weight(1f) claims the leftover Column
        // height so the bottom row isn't clipped.
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = ScrollTailPadding)
        ) {
            items(latestBytes.size) { off ->
                val v = latestBytes[off]
                ByteInspectCell(off, v, dimmed = outOfRange(v)) {
                    vm.logByteInspection(selected, off, v)
                }
            }
        }
    }
}

@Composable
private fun ByteInspectCell(
    offset: Int,
    value: Int,
    dimmed: Boolean = false,
    onTap: () -> Unit
) {
    val hex = remember(value) { "%02x".format(value) }
    val cellAlpha = if (dimmed) 0.18f else 1f
    Column(
        modifier = Modifier
            .padding(2.dp)
            .alpha(cellAlpha)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                RoundedCornerShape(0.dp)
            )
            .clickable(onClick = onTap)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "[$offset]",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            hex,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            ),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "$value",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RawTab(vm: WheelDiagnosticsViewModel) {
    var raw by remember { mutableStateOf("") }
    // Multi-step undo / redo. Each user-initiated change pushes the prior
    // text onto the undo stack and clears the redo stack; undo pops from
    // undo into redo, redo pops from redo back into undo.
    val undoStack = remember { mutableListOf<String>() }
    val redoStack = remember { mutableListOf<String>() }
    // Triggers recomposition when the stacks change so the buttons enable
    // / disable in step.
    var stackVersion by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(WheelDiagnosticsViewModel.WrapMode.LITERAL) }

    fun setRaw(new: String) {
        if (new == raw) return
        undoStack.add(raw)
        redoStack.clear()
        stackVersion++
        raw = new
    }
    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(raw)
        raw = undoStack.removeAt(undoStack.size - 1)
        stackVersion++
    }
    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(raw)
        raw = redoStack.removeAt(redoStack.size - 1)
        stackVersion++
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
        // Per-family preset library. Each family's chips draw from the
        // same DiagnosticCommand catalogue the Commands tab uses, so the
        // single source of truth holds and authoring a new family's
        // commands lights both tabs up at once. Tap a chip to seed the
        // input box (vs the Commands tab which fires immediately).
        val presetFamilies = remember { vm.allWheelFamilies().filter { it.commands.isNotEmpty() } }
        var presetFamilyIdx by rememberSaveable { mutableStateOf(0) }
        var presetMenuOpen by remember { mutableStateOf(false) }
        val activePresetFamily = presetFamilies.getOrNull(presetFamilyIdx)
        CollapsibleSection(title = "Insert preset", defaultExpanded = false) {
            if (activePresetFamily == null) {
                Text(
                    "No families publish presets yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Box(modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)) {
                    OutlinedButton(shape = RoundedCornerShape(0.dp), onClick = { presetMenuOpen = true }) {
                        Text(activePresetFamily.displayName)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = presetMenuOpen,
                        onDismissRequest = { presetMenuOpen = false }
                    ) {
                        presetFamilies.forEachIndexed { idx, fam ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(fam.displayName) },
                                onClick = { presetFamilyIdx = idx; presetMenuOpen = false }
                            )
                        }
                    }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    activePresetFamily.commands.forEach { cmd ->
                        val hex = remember(cmd.bytes) {
                            cmd.bytes.joinToString(" ") { "%02x".format(it) }
                        }
                        AssistChip(shape = RoundedCornerShape(0.dp), 
                            onClick = { appendBytes(hex) },
                            label = {
                                Column {
                                    Text(
                                        cmd.label,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Text(
                                        cmd.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            modifier = Modifier.padding(end = 6.dp).height(56.dp)
                        )
                    }
                }
            }
        }

        CollapsibleSection(title = "Hex pad", defaultExpanded = false) {
            val hexChars = listOf('0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F')
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    hexChars.subList(0, 8).forEach { c ->
                        OutlinedButton(shape = RoundedCornerShape(0.dp), 
                            onClick = { appendChar(c) },
                            modifier = Modifier.weight(1f).height(40.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text(c.toString(), fontFamily = FontFamily.Monospace) }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    hexChars.subList(8, 16).forEach { c ->
                        OutlinedButton(shape = RoundedCornerShape(0.dp), 
                            onClick = { appendChar(c) },
                            modifier = Modifier.weight(1f).height(40.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text(c.toString(), fontFamily = FontFamily.Monospace) }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    OutlinedButton(shape = RoundedCornerShape(0.dp), 
                        onClick = { appendChar(' ') },
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("space", style = MaterialTheme.typography.labelSmall) }
                    OutlinedButton(shape = RoundedCornerShape(0.dp), 
                        onClick = { if (raw.isNotEmpty()) setRaw(raw.dropLast(1)) },
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Icon(Icons.Default.Backspace, contentDescription = "Backspace", modifier = Modifier.size(18.dp)) }
                    // stackVersion read here so recomposition fires on push/pop
                    @Suppress("UNUSED_VARIABLE") val v = stackVersion
                    OutlinedButton(shape = RoundedCornerShape(0.dp), 
                        onClick = { undo() },
                        enabled = undoStack.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp)) }
                    OutlinedButton(shape = RoundedCornerShape(0.dp), 
                        onClick = { redo() },
                        enabled = redoStack.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", modifier = Modifier.size(18.dp)) }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Hex input row: text field on the left, fmt / cls stacked on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(shape = RoundedCornerShape(0.dp), 
                value = raw,
                onValueChange = { setRaw(it) },
                modifier = Modifier.weight(1f),
                label = { Text("Input") },
                placeholder = { Text("60 50 01 01") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                isError = !preview.ok && raw.isNotBlank(),
                singleLine = true
            )
            Spacer(Modifier.width(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(shape = RoundedCornerShape(0.dp), 
                    onClick = { setRaw(vm.formatHex(raw)) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) { Text("fmt") }
                OutlinedButton(shape = RoundedCornerShape(0.dp), 
                    onClick = { if (raw.isNotEmpty()) setRaw("") },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) { Text("cls") }
            }
        }

        Spacer(Modifier.height(8.dp))

        CollapsibleSection(
            title = "Wrap mode: ${mode.name.lowercase().replace('_', ' ')}",
            defaultExpanded = false
        ) {
            // Veteran and Begode commands are literal (fixed opcodes / ASCII
            // strings already including their full wire format), so LITERAL
            // covers them, no separate WRAP_VETERAN / WRAP_BEGODE entries.
            // The segmented row stays scrollable so newer protocols can
            // join without redesigning the layout.
            val modes = listOf(
                WheelDiagnosticsViewModel.WrapMode.LITERAL to "Literal",
                WheelDiagnosticsViewModel.WrapMode.WRAP_EXTENDED to "V2 ext",
                WheelDiagnosticsViewModel.WrapMode.WRAP_V14_SHORT to "V14",
                WheelDiagnosticsViewModel.WrapMode.WRAP_KINGSONG to "KS",
                WheelDiagnosticsViewModel.WrapMode.WRAP_NINEBOT_LEGACY to "9bot",
                WheelDiagnosticsViewModel.WrapMode.WRAP_INMOTION_V1 to "V1"
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(IntrinsicSize.Max)
            ) {
                modes.forEachIndexed { index, (m, label) ->
                    SegmentedButton(
                        modifier = Modifier.fillMaxHeight(),
                        selected = mode == m,
                        onClick = { mode = m },
                        // Force square corners on the segmented row to match
                        // the rest of Service Mode (the default itemShape
                        // gives rounded ends that bypassed the theme).
                        shape = RoundedCornerShape(0.dp)
                    ) { Text(label) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Bytes-to-send row: read-only field on the left, big SEND on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Read-only output field. Filled background + dimmed border so it
            // visually contrasts with the editable Input field above.
            val readOnlyContainer = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            OutlinedTextField(shape = RoundedCornerShape(0.dp), 
                value = preview.display,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.weight(1f),
                label = { Text("Bytes to send") },
                placeholder = { Text("--") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                isError = preview.error != null,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = readOnlyContainer,
                    unfocusedContainerColor = readOnlyContainer,
                    errorContainerColor = readOnlyContainer,
                    focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Spacer(Modifier.width(6.dp))
            // Match fmt/cls in the hex-input row: same OutlinedButton, same
            // compact padding so the dialog doesn't have one giant CTA when
            // the rest of the right column is small chips.
            OutlinedButton(shape = RoundedCornerShape(0.dp), 
                onClick = { vm.fireRawHex(raw, mode) },
                enabled = canSend,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("send") }
        }

        Spacer(Modifier.height(ScrollTailPadding))
    }

}


private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

// Clearance below the last row of each scrollable tab so the final element
// (Send button, last command, last byte row) stays comfortably reachable
// after scrolling, even with the IME open.
private val ScrollTailPadding = 72.dp
