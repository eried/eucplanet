package com.eried.eucplanet.ui.theme

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Minimize
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
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

    // Refresh available themes / folder state when the widget shows and whenever
    // the backup folder or saved set changes (so Save-as enables + the combo
    // separator below updates).
    LaunchedEffect(settings.syncFolderUri, settings.activeThemeName) { vm.refreshChoices() }

    // Active theme colors come from ThemeController (in memory); the live preview
    // overrides them while a slider drags.
    val activeColors = vm.activeColors.collectAsState().value
    val dirty = vm.dirty.collectAsState().value
    val base: AppThemeColors = liveColors ?: activeColors

    var offset by remember { mutableStateOf(Offset(36f, 220f)) }
    var collapsed by remember { mutableStateOf(false) }
    // Keep the widget fully on-screen — whatever its current size (collapsed pill
    // or expanded card) — so a drag can never push it out of reach and lose it.
    val view = LocalView.current
    var widgetSize by remember { mutableStateOf(IntSize.Zero) }
    fun clampToScreen(o: Offset): Offset {
        if (view.width == 0 || view.height == 0) return o
        val maxX = (view.width - widgetSize.width).coerceAtLeast(0).toFloat()
        val maxY = (view.height - widgetSize.height).coerceAtLeast(0).toFloat()
        return Offset(o.x.coerceIn(0f, maxX), o.y.coerceIn(0f, maxY))
    }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var showSave by remember { mutableStateOf(false) }
    var pendingReplaceName by remember { mutableStateOf<String?>(null) }
    var showNoFolder by remember { mutableStateOf(false) }
    var pendingOverwrite by remember { mutableStateOf(false) }
    var pickMode by remember { mutableStateOf(false) }
    // Eyedropper gesture: fingerDown = the finger is still on the button (live
    // aim, no buttons); autoIdentify = released off the button → sample at once.
    var fingerDown by remember { mutableStateOf(false) }
    var autoIdentify by remember { mutableStateOf(false) }
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

    // activeThemeName for a custom theme carries the ".json" suffix
    // internally (disambiguates from built-in names of the same word). Strip
    // it before displaying so the rider sees "Light" not "Light.json".
    val rawName = settings.activeThemeName.ifEmpty { "Theme" }
    val titleName = if (rawName.endsWith(".json", ignoreCase = true)) rawName.dropLast(5) else rawName
    val title = if (dirty) "$titleName (unsaved)" else titleName

    // Re-clamp when the widget changes size (expand <-> collapse) or the screen
    // resizes, so collapsing a card that sat near an edge can't strand the pill.
    LaunchedEffect(widgetSize) { offset = clampToScreen(offset) }

    Box(
        modifier = Modifier
            .offset {
                val c = clampToScreen(offset)
                IntOffset(c.x.roundToInt(), c.y.roundToInt())
            }
            .onSizeChanged { widgetSize = it }
            // The floating theme widget is 90% opaque so the dashboard behind it
            // stays faintly visible -- and fully hidden while the eyedropper is
            // aiming, so the widget doesn't sit over the pixels being sampled.
            // alpha 0 (not visibility/gone) keeps it in the tree so the in-flight
            // pick gesture started on the eyedropper button still resolves.
            .alpha(if (pickMode) 0f else 0.9f)
    ) {
        if (collapsed) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume(); offset = clampToScreen(offset + drag)
                        }
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
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            // Outline so the floating editor reads as a distinct surface over the
            // dashboard behind it, whatever the theme's background is.
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            // Header (drag handle) — shows the active theme name + unsaved state.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume(); offset = clampToScreen(offset + drag)
                        }
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
                    // Long theme names truncate with "…" instead of wrapping and
                    // growing the header (the card width is fixed).
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // Order: minimize, save, eyedropper (rightmost).
                IconButton(onClick = { collapsed = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Minimize, contentDescription = "Minimize",
                        modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = {
                    if (choices.folderAvailable) showSave = true
                    else showNoFolder = true
                }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Save, contentDescription = "Save as new theme",
                        modifier = Modifier.size(18.dp))
                }
                // Eyedropper. Two flows decided by where the finger lifts:
                //  • drag off the button and release → auto-Identify at the release
                //    point (no Identify/Cancel step).
                //  • tap (press + release on the button) → the aim flow with the
                //    draggable crosshair + Identify/Cancel.
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .onGloballyPositioned { pickerBtnCenter = it.boundsInWindow().center }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                // Seed the crosshair ABOVE the touch point so, as the finger
                                // drags the tool out, the reticle (and the sampled pixel) ride
                                // above the fingertip where the rider can see what they're on.
                                ringPos = pickerBtnCenter - Offset(0f, 80.dp.toPx())
                                fingerDown = true
                                autoIdentify = false
                                pickMode = true
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        // Released inside the button → tap → aim flow.
                                        // Released outside → dragged away → auto-Identify.
                                        val inside =
                                            change.position.x in 0f..size.width.toFloat() &&
                                            change.position.y in 0f..size.height.toFloat()
                                        fingerDown = false
                                        autoIdentify = !inside
                                        change.consume()
                                        break
                                    }
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
                    .heightIn(max = com.eried.eucplanet.ui.common.dialogContentMaxHeight(300))
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
                                onPlay = { blink(spec) },
                                onHexEdit = { c -> vm.preview(spec.set(base, c)) },
                                onHexCommit = {
                                    if (!dirty &&
                                        settings.activeThemeName in choices.unsaved
                                    ) pendingOverwrite = true
                                    else vm.commit()
                                }
                            )
                            if (selectedKey == spec.key) {
                                ColorSliders(
                                    color = spec.get(base),
                                    onChange = { c -> vm.preview(spec.set(base, c)) },
                                    onCommit = {
                                        // Editing a clean theme that already has a
                                        // draft would clobber it — confirm first.
                                        if (!dirty &&
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
            fingerDown = fingerDown,
            autoIdentify = autoIdentify,
            onRing = { ringPos = it },
            onPreviewToken = { spec -> blink(spec) },
            onPicked = { spec -> selectedKey = spec.key; pickMode = false },
            onCancel = { pickMode = false }
        )
    }

    if (showSave) {
        // Default the textfield to the active theme's display name (without
        // the .json suffix) so the rider edits "Light", not "Light.json".
        val seedName = settings.activeThemeName.let {
            if (it.endsWith(".json", ignoreCase = true)) it.dropLast(5) else it
        }
        var name by remember { mutableStateOf(seedName) }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save theme") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, label = { Text("Name") },
                    colors = themedFieldColors()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val n = name.trim()
                        // One clean rule: if the typed name matches ANY existing
                        // saved theme (including the one the rider is currently
                        // editing), confirm the overwrite. Compare bare names
                        // since choices.saved carries the .json suffix.
                        val savedBare = choices.saved.map {
                            if (it.endsWith(".json", ignoreCase = true)) it.dropLast(5) else it
                        }
                        if (n in savedBare) {
                            pendingReplaceName = n
                        } else {
                            vm.saveAs(n) { showSave = false }
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } }
        )
    }

    // Typed a name that matches another existing saved theme — confirm the replace.
    pendingReplaceName?.let { n ->
        AlertDialog(
            onDismissRequest = { pendingReplaceName = null },
            title = { Text("Replace theme?") },
            text = { Text("A theme named \"$n\" already exists. Saving will replace it.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveAs(n) { showSave = false; pendingReplaceName = null }
                }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { pendingReplaceName = null }) { Text("Cancel") }
            }
        )
    }

    // Editing a clean theme that already has an unsaved draft would clobber it —
    // confirm before replacing. Cancel discards this edit and keeps the draft.
    if (pendingOverwrite) {
        AlertDialog(
            onDismissRequest = { pendingOverwrite = false; vm.preview(null) },
            title = { Text("Discard the unsaved draft?") },
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

    // Saving needs a backup folder. Use an in-widget dialog (matching the other
    // dialogs here) rather than a system Toast, so it follows the app's
    // Snackbar/dialog convention instead of the OS toast style.
    if (showNoFolder) {
        AlertDialog(
            onDismissRequest = { showNoFolder = false },
            title = { Text("No backup folder") },
            text = {
                Text(
                    "To save themes, choose a backup folder:\n" +
                        stringResource(R.string.tab_cloud) + " → " +
                        stringResource(R.string.cloud_choose_folder)
                )
            },
            confirmButton = {
                TextButton(onClick = { showNoFolder = false }) { Text("OK") }
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
    onHexEdit: (Color) -> Unit,
    onHexCommit: () -> Unit,
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
        HexInput(color, onHexEdit, onHexCommit)
    }
}

/**
 * Inline editable hex code. Shows the current RRGGBB; typing a valid 6-char
 * hex previews the change live, IME Done / focus loss commits. Invalid input
 * is held in the field without mutating the color so the rider can keep
 * typing without their work being yanked away.
 */
@Composable
private fun HexInput(
    color: Color,
    onHexEdit: (Color) -> Unit,
    onHexCommit: () -> Unit,
) {
    val external = color.toHex().drop(2) // strip AA from AARRGGBB
    var text by remember(external) { mutableStateOf(external) }
    val style = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("#", style = style)
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { raw ->
                val cleaned = raw.removePrefix("#")
                    .filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                    .take(6)
                    .uppercase()
                text = cleaned
                if (cleaned.length == 6) {
                    hexToColor(cleaned)?.let(onHexEdit)
                }
            },
            singleLine = true,
            textStyle = style,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.onSurfaceVariant),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { onHexCommit() }
            ),
            modifier = Modifier
                .width(58.dp)
                .onFocusChanged { f -> if (!f.isFocused && text.length == 6) onHexCommit() },
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
            colors = themedSliderColors(),
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
