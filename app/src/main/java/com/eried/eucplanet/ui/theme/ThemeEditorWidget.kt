package com.eried.eucplanet.ui.theme

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Theme customization widget — a floating, draggable, collapsible color editor. Mounted at the
 * app root only when `themeEditorEnabled` is on, so when it's off none of this is
 * in the tree (zero overhead). Edits preview live in-memory (the whole app
 * re-skins) and persist to the working draft on release; built-ins are never
 * mutated. Theme selection lives in Settings, not here.
 */
@Composable
fun ThemeEditorWidget(
    vm: ThemeEditorViewModel = hiltViewModel()
) {
    val settings = vm.settings.collectAsState().value ?: return
    val liveColors = vm.live.collectAsState().value
    val choices = vm.choices.collectAsState().value
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Refresh available themes / folder state when the widget shows and whenever
    // the backup folder or saved set changes (so Save-as enables + the combo
    // separator below updates).
    LaunchedEffect(settings.syncFolderUri, settings.activeThemeName) { vm.refreshChoices() }

    val persisted = remember(settings.activeThemeColorsJson) {
        settings.activeThemeColorsJson.takeIf { it.isNotEmpty() }
            ?.let { ThemeJson.colorsFromString(it, BuiltInThemes.pureBlack.colors) }
            ?: BuiltInThemes.pureBlack.colors
    }
    val base: AppThemeColors = liveColors ?: persisted

    var offset by remember { mutableStateOf(Offset(36f, 220f)) }
    var collapsed by remember { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var showSave by remember { mutableStateOf(false) }
    var pendingOverwrite by remember { mutableStateOf(false) }
    var pickMode by remember { mutableStateOf(false) }
    // Crosshair position (window coords), hoisted so the eyedropper can drag it
    // straight out of the button in one motion, and the overlay can drag it too.
    var ringPos by remember { mutableStateOf(Offset(360f, 760f)) }
    var pickerBtnCenter by remember { mutableStateOf(Offset(360f, 760f)) }
    val listState = rememberLazyListState()

    // Flat list of entries (group headers + token rows) so the LazyColumn can
    // scroll a selected/picked token to the TOP of the list.
    val entries = remember {
        buildList {
            ThemeTokens.grouped.forEach { (group, specs) ->
                add(ThemeListEntry.Header(group))
                specs.forEach { add(ThemeListEntry.TokenEntry(it)) }
            }
        }
    }

    // When a token is selected — especially when picked via the target tool — scroll
    // it to the TOP of the list so its editor is front and centre.
    LaunchedEffect(selectedKey) {
        val key = selectedKey ?: return@LaunchedEffect
        val idx = entries.indexOfFirst { it is ThemeListEntry.TokenEntry && it.spec.key == key }
        if (idx >= 0) {
            delay(40)
            runCatching { listState.animateScrollToItem(idx) }
        }
    }

    // One blink of a token, everywhere it's used. Driven by the per-row play
    // button (and once when the target tool picks a token), via the separate
    // pulse channel so it never disturbs a value being edited.
    val blink: (ThemeTokenSpec) -> Unit = { spec ->
        val c = spec.get(base)
        scope.launch {
            try {
                repeat(2) {
                    vm.pulse(spec.set(base, c.adjustLightness(1.8f))); delay(170)
                    vm.pulse(spec.set(base, c.adjustLightness(0.4f))); delay(170)
                }
            } finally {
                vm.pulse(null)
            }
        }
    }

    val titleName = settings.activeThemeName.ifEmpty { "Theme" }
    val title = if (settings.themeDirty) "$titleName (unsaved)" else titleName

    Box(
        modifier = Modifier.offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
    ) {
        if (collapsed) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag -> change.consume(); offset += drag }
                    }
                    .clickable { collapsed = false },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Palette, contentDescription = "Theme customization widget",
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)
                )
            }
            return@Box
        }

        Card(
            modifier = Modifier.widthIn(min = 240.dp, max = 280.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            // Header (drag handle) — shows the active theme name + unsaved state.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag -> change.consume(); offset += drag }
                    }
                    .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Palette, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                // Order: minimize, save, eyedropper (rightmost).
                IconButton(onClick = { collapsed = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Minimize",
                        modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = {
                    if (choices.folderAvailable) showSave = true
                    else Toast.makeText(
                        context, "Set a backup folder to save themes", Toast.LENGTH_SHORT
                    ).show()
                }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Save, contentDescription = "Save as new theme",
                        modifier = Modifier.size(18.dp))
                }
                // Eyedropper: opens the picker on PRESS (not release) and lets you
                // drag the crosshair straight out of the button in one motion.
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .onGloballyPositioned { pickerBtnCenter = it.boundsInWindow().center }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                ringPos = pickerBtnCenter
                                pickMode = true
                                drag(down.id) { change ->
                                    ringPos += change.positionChange()
                                    change.consume()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Colorize, contentDescription = "Pick a color from the screen",
                        modifier = Modifier.size(18.dp))
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                items(entries.size) { i ->
                    when (val e = entries[i]) {
                        is ThemeListEntry.Header -> Text(
                            e.group, style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, bottom = 1.dp)
                        )
                        is ThemeListEntry.TokenEntry -> {
                            val spec = e.spec
                            TokenRow(
                                label = spec.label,
                                color = spec.get(base),
                                onClick = {
                                    if (selectedKey == spec.key) selectedKey = null
                                    else { selectedKey = spec.key; blink(spec) }
                                },
                                onPlay = { blink(spec) }
                            )
                            if (selectedKey == spec.key) {
                                ColorSliders(
                                    color = spec.get(base),
                                    onChange = { c -> vm.preview(spec.set(base, c)) },
                                    onCommit = {
                                        // Editing a clean theme that already has a
                                        // draft would clobber it — confirm first.
                                        if (!settings.themeDirty &&
                                            settings.activeThemeName in choices.unsaved
                                        ) pendingOverwrite = true
                                        else vm.commit()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Target tool: full-screen crosshair over the app. Picking a token opens its
    // editor and blinks it once so you can confirm you grabbed the right one.
    if (pickMode) {
        ThemeTargetOverlay(
            base = base,
            ring = ringPos,
            onRing = { ringPos = it },
            onPreviewToken = { spec -> blink(spec) },
            onPicked = { spec -> selectedKey = spec.key; pickMode = false },
            onCancel = { pickMode = false }
        )
    }

    if (showSave) {
        var name by remember { mutableStateOf(settings.activeThemeName) }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save theme") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, label = { Text("Name") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { vm.saveAs(name.trim()) { showSave = false } }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } }
        )
    }

    // Editing a clean theme that already has an unsaved draft would clobber it —
    // confirm before replacing. Cancel discards this edit and keeps the draft.
    if (pendingOverwrite) {
        AlertDialog(
            onDismissRequest = { pendingOverwrite = false; vm.preview(null) },
            title = { Text("Unsaved version will be lost") },
            text = {
                Text(
                    "This theme already has an unsaved version of it. If you start " +
                        "modifying it again, the previous unsaved changes will be lost."
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingOverwrite = false; vm.commit() }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOverwrite = false; vm.preview(null) }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TokenRow(
    label: String,
    color: Color,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(color, RoundedCornerShape(5.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
        // Borderless tiny play button, right next to the name, that replays the
        // blink for this token everywhere it's used.
        IconButton(onClick = onPlay, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Replay blink",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            "#" + color.toHex().drop(2),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ColorSliders(color: Color, onChange: (Color) -> Unit, onCommit: () -> Unit) {
    // Dragging previews live (in-memory, instant); releasing persists the
    // working draft via onCommit (one DataStore write per gesture, not per frame).
    Column(modifier = Modifier.padding(start = 28.dp, bottom = 6.dp)) {
        ChannelSlider("R", color.red, { r -> onChange(color.copy(red = r)) }, onCommit)
        ChannelSlider("G", color.green, { g -> onChange(color.copy(green = g)) }, onCommit)
        ChannelSlider("B", color.blue, { b -> onChange(color.copy(blue = b)) }, onCommit)
    }
}

@Composable
private fun ChannelSlider(label: String, value: Float, onValue: (Float) -> Unit, onCommit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(14.dp))
        Slider(
            value = value,
            onValueChange = onValue,
            onValueChangeFinished = onCommit,
            modifier = Modifier.weight(1f)
        )
        Text(
            (value * 255).roundToInt().toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(30.dp)
        )
    }
}

/** A row in the editor list: a group header or an editable token. */
private sealed interface ThemeListEntry {
    data class Header(val group: String) : ThemeListEntry
    data class TokenEntry(val spec: ThemeTokenSpec) : ThemeListEntry
}
