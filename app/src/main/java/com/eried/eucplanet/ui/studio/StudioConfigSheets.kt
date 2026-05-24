package com.eried.eucplanet.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.OverlayElement
import com.eried.eucplanet.data.model.OverlayElementType
import com.eried.eucplanet.data.model.ViewportConfig
import com.eried.eucplanet.data.model.ViewportLayout
import com.eried.eucplanet.data.model.ViewportSourceType
import com.eried.eucplanet.ui.studio.camera.StudioCameraInfo
import sh.calvin.reorderable.ReorderableColumn
import kotlin.math.roundToInt

/** Which secondary sheet / dialog the studio is currently showing. */
sealed interface StudioSheet {
    data object None : StudioSheet
    data object AddElement : StudioSheet
    data object ManageElements : StudioSheet
    data object LayoutPicker : StudioSheet
    data object SavePreset : StudioSheet
    data object LoadPreset : StudioSheet
    data object DividerConfig : StudioSheet
    data class ElementConfig(val elementId: String) : StudioSheet
    data class ViewportConfig(val index: Int) : StudioSheet
}

/** A destructive action awaiting the rider's confirmation. */
sealed interface StudioConfirm {
    /** Wipe the working layout. */
    data object ClearLayout : StudioConfirm
    /** Replace the working layout with a saved preset. */
    data class LoadUserPreset(val name: String) : StudioConfirm
    /** Replace the working layout with a bundled starter preset. */
    data class LoadBundledPreset(val name: String) : StudioConfirm
}

/** Solid + translucent colours offered by the studio swatch picker. */
val StudioPalette: List<Long> = listOf(
    0x00000000L, // transparent — always first / leftmost
    0xFFFFFFFFL, // white
    0xFF9E9E9EL, // grey
    0xFF000000L, // black
    0xFFE53935L, // red
    0xFFFB8C00L, // orange
    0xFFFDD835L, // yellow
    0xFF43A047L, // green
    0xFF1E88E5L, // blue
    0xFF8E24AAL, // lilac
    0xFFEC407AL  // pink
)

/** Chroma-key mask colours — the common green / blue screen colours first. */
val ChromaPalette: List<Long> = listOf(
    0xFF00FF00L, 0xFF0000FFL, 0xFFFF00FFL, 0xFF00FFFFL,
    0xFFFFFFFFL, 0xFF000000L, 0xFFE53935L
)

/**
 * The layout shape to PRESENT for [this] in the picker. The studio surface
 * never rotates, but the picker panel does (so it faces a rider holding the
 * phone sideways) — which means a COLUMNS_2 split, vertical on the upright
 * surface, is physically seen as 2 rows once the phone is turned. The picker's
 * name and mini-diagram are transposed together to match what will actually be
 * recorded; the layout value the rider picks is never changed.
 */
private fun ViewportLayout.orientedForDisplay(landscape: Boolean): ViewportLayout =
    if (!landscape) this else when (this) {
        ViewportLayout.ROWS_2 -> ViewportLayout.COLUMNS_2
        ViewportLayout.COLUMNS_2 -> ViewportLayout.ROWS_2
        ViewportLayout.ROWS_3 -> ViewportLayout.COLUMNS_3
        ViewportLayout.COLUMNS_3 -> ViewportLayout.ROWS_3
        else -> this
    }

/** True while the studio chrome is rotated for a landscape grip. */
@Composable
private fun studioLandscape(): Boolean =
    LocalStudioRotation.current.let { it == 90 || it == 270 }

@Composable
fun ViewportLayout.displayName(): String =
    when (orientedForDisplay(studioLandscape())) {
        ViewportLayout.SINGLE -> stringResource(R.string.studio_layout_full_screen)
        ViewportLayout.ROWS_2 -> stringResource(R.string.studio_layout_2_rows)
        ViewportLayout.COLUMNS_2 -> stringResource(R.string.studio_layout_2_columns)
        ViewportLayout.ROWS_3 -> stringResource(R.string.studio_layout_3_rows)
        ViewportLayout.COLUMNS_3 -> stringResource(R.string.studio_layout_3_columns)
        ViewportLayout.GRID_4 -> stringResource(R.string.studio_layout_grid_4)
    }

private val OverlayElementType.labelRes: Int
    get() = when (this) {
        OverlayElementType.WHEEL_NAME -> R.string.studio_element_wheel_name
        OverlayElementType.APP_BADGE -> R.string.studio_element_app_badge
        OverlayElementType.TEXT -> R.string.studio_element_text
        OverlayElementType.DATA_VALUE -> R.string.studio_element_data_value
        OverlayElementType.DATA_GRAPH -> R.string.studio_element_data_graph
        OverlayElementType.DATA_DIAL -> R.string.studio_element_data_dial
        OverlayElementType.DATA_BAR -> R.string.studio_element_data_bar
        OverlayElementType.FLOATING_CAMERA -> R.string.studio_element_floating_camera
        OverlayElementType.IMAGE -> R.string.studio_element_image
        OverlayElementType.CLOCK -> R.string.studio_element_clock
        OverlayElementType.G_FORCE -> R.string.studio_element_g_force
        OverlayElementType.MAP -> R.string.studio_element_map
    }

@Composable
private fun OverlayElementType.label(): String = stringResource(labelRes)

private val OverlayElementType.icon
    get() = when (this) {
        OverlayElementType.WHEEL_NAME -> Icons.Default.DirectionsBike
        OverlayElementType.APP_BADGE -> Icons.Default.Badge
        OverlayElementType.TEXT -> Icons.Default.TextFields
        OverlayElementType.DATA_VALUE -> Icons.Default.Numbers
        OverlayElementType.DATA_GRAPH -> Icons.Default.ShowChart
        OverlayElementType.DATA_DIAL -> Icons.Default.Speed
        OverlayElementType.DATA_BAR -> Icons.Default.BarChart
        OverlayElementType.FLOATING_CAMERA -> Icons.Default.PhotoCamera
        OverlayElementType.IMAGE -> Icons.Default.Image
        OverlayElementType.CLOCK -> Icons.Default.Schedule
        OverlayElementType.G_FORCE -> Icons.Default.TrackChanges
        OverlayElementType.MAP -> Icons.Default.Map
    }

// --------------------------------------------------------------------------
// Camera-tools flyout (the "..." button)
// --------------------------------------------------------------------------

@Composable
fun StudioToolsFlyout(
    expanded: Boolean,
    hasElements: Boolean,
    canAddElement: Boolean = true,
    onDismiss: () -> Unit,
    onAddElement: () -> Unit,
    onManageElements: () -> Unit,
    onChangeLayout: () -> Unit,
    onNew: () -> Unit,
    onLoadPreset: () -> Unit,
    onSavePreset: () -> Unit,
    onReplayMode: () -> Unit,
    replayMode: Boolean = false,
    deviceRotation: Int = 0
) {
    // The Replay/Live mode label is snapshotted while the menu is open, so it
    // does not visibly flip during the menu's close animation after a tap.
    var modeIsReplay by remember { mutableStateOf(replayMode) }
    LaunchedEffect(expanded, replayMode) {
        if (expanded) modeIsReplay = replayMode
    }
    // Two columns: saved Presets on the left, the live studio actions on the
    // right. Rotated so it faces a rider holding the phone sideways.
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp)
    ) {
        androidx.compose.foundation.layout.Box(Modifier.rotateLayout(deviceRotation)) {
        Row(modifier = Modifier.padding(horizontal = 4.dp)) {
            Column(Modifier.width(154.dp)) {
                FlyoutSection(stringResource(R.string.studio_flyout_section_preset))
                // Camera panes are hidden in replay (the background is the
                // trip's transparent checkerboard), so configuring them is moot.
                FlyoutItem(
                    Icons.Default.Dashboard, stringResource(R.string.studio_flyout_panes),
                    enabled = !replayMode
                ) {
                    onDismiss(); onChangeLayout()
                }
                FlyoutItem(Icons.Default.NoteAdd, stringResource(R.string.studio_flyout_new)) { onDismiss(); onNew() }
                FlyoutItem(Icons.Default.Image, stringResource(R.string.studio_flyout_load)) { onDismiss(); onLoadPreset() }
                FlyoutItem(Icons.Default.Save, stringResource(R.string.studio_flyout_save), enabled = hasElements) {
                    onDismiss(); onSavePreset()
                }
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.width(154.dp)) {
                FlyoutSection(stringResource(R.string.studio_flyout_section_mode))
                // Toggles the studio mode: reads "Replay" while live, and
                // "Live" while replaying — the same button returns to the
                // camera and closes the replay panel.
                if (modeIsReplay) {
                    FlyoutItem(Icons.Default.Videocam, stringResource(R.string.studio_flyout_live)) {
                        onDismiss(); onReplayMode()
                    }
                } else {
                    FlyoutItem(Icons.Default.History, stringResource(R.string.studio_replay_title)) {
                        onDismiss(); onReplayMode()
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                FlyoutSection(stringResource(R.string.studio_flyout_section_elements))
                // Manage elements stays enabled even with no elements --
                // the sheet now also hosts the Add button, so it's a valid
                // entry point for building a layout from scratch.
                FlyoutItem(Icons.Default.Layers, stringResource(R.string.studio_flyout_manage)) {
                    onDismiss(); onManageElements()
                }
                FlyoutItem(
                    Icons.Default.Widgets, stringResource(R.string.studio_flyout_add),
                    enabled = canAddElement
                ) { onDismiss(); onAddElement() }
            }
        }
        }
    }
}

@Composable
private fun FlyoutSection(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
    )
}

/** Confirmation dialog for the studio's destructive / layout-replacing actions. */
@Composable
fun StudioConfirmDialog(
    confirm: StudioConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val title: String
    val body: String
    val action: String
    when (confirm) {
        StudioConfirm.ClearLayout -> {
            title = stringResource(R.string.studio_dlg_new_title)
            body = stringResource(R.string.studio_dlg_new_body)
            action = stringResource(R.string.studio_dlg_new_confirm)
        }
        is StudioConfirm.LoadUserPreset -> {
            title = stringResource(R.string.studio_dlg_load_title)
            body = stringResource(R.string.studio_dlg_load_body, confirm.name)
            action = stringResource(R.string.studio_dlg_load_confirm)
        }
        is StudioConfirm.LoadBundledPreset -> {
            title = stringResource(R.string.studio_dlg_load_builtin_title)
            body = stringResource(R.string.studio_dlg_load_body, confirm.name)
            action = stringResource(R.string.studio_dlg_load_confirm)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.rotateLayout(LocalStudioRotation.current),
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun FlyoutItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        enabled = enabled,
        onClick = onClick
    )
}

/** Physical device rotation (0/90/180/270) for orienting studio chrome. */
val LocalStudioRotation = androidx.compose.runtime.compositionLocalOf { 0 }

/**
 * Rotates the content by -[rotation]° with correct layout — for 90/270 the
 * child is measured in a width/height-swapped frame, so it occupies exactly
 * the host's box (no overflow) and touch input still lands right.
 */
@Composable
fun Modifier.rotateLayout(rotation: Int, withFade: Boolean = true): Modifier {
    val target = ((rotation % 360) + 360) % 360
    if (!withFade) {
        // No crossfade — snap straight to the orientation. Used by the render
        // overlay, where fading its scrim out blinks the whole screen.
        val o = when (target) {
            0 -> Modifier
            180 -> Modifier.rotate(180f)
            else -> Modifier.then(SwapSizeModifier).rotate(-target.toFloat())
        }
        return this.then(o)
    }
    // Fade out, swap orientation while invisible, fade back in — a device
    // rotation eases the chrome over instead of snapping it around.
    val shown = remember { androidx.compose.runtime.mutableIntStateOf(target) }
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(target) {
        if (shown.intValue == target) {
            // A previous rotation was interrupted mid-fade (orientation flipped
            // back before the animation finished) — snap back to fully visible
            // so the chrome can never get stuck half-faded / blank.
            if (fade.value != 1f) {
                fade.animateTo(1f, androidx.compose.animation.core.tween(140))
            }
            return@LaunchedEffect
        }
        fade.animateTo(0f, androidx.compose.animation.core.tween(90))
        shown.intValue = target
        fade.animateTo(1f, androidx.compose.animation.core.tween(140))
    }
    val oriented = when (shown.intValue) {
        0 -> Modifier
        180 -> Modifier.rotate(180f)
        else -> Modifier.then(SwapSizeModifier).rotate(-shown.intValue.toFloat())
    }
    return this.then(oriented).graphicsLayer { alpha = fade.value }
}

/**
 * Measures the child in a width/height-swapped frame and reports the swapped
 * size — including for intrinsic queries, so a host like DropdownMenu that
 * sizes itself by intrinsics wraps the rotated content with no empty gap.
 */
private object SwapSizeModifier : androidx.compose.ui.layout.LayoutModifier {
    override fun androidx.compose.ui.layout.MeasureScope.measure(
        measurable: androidx.compose.ui.layout.Measurable,
        constraints: androidx.compose.ui.unit.Constraints
    ): androidx.compose.ui.layout.MeasureResult {
        val placeable = measurable.measure(
            androidx.compose.ui.unit.Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth
            )
        )
        return layout(placeable.height, placeable.width) {
            placeable.place(
                (placeable.height - placeable.width) / 2,
                (placeable.width - placeable.height) / 2
            )
        }
    }

    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.minIntrinsicWidth(
        measurable: androidx.compose.ui.layout.IntrinsicMeasurable, height: Int
    ): Int = measurable.minIntrinsicHeight(height)

    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.maxIntrinsicWidth(
        measurable: androidx.compose.ui.layout.IntrinsicMeasurable, height: Int
    ): Int = measurable.maxIntrinsicHeight(height)

    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.minIntrinsicHeight(
        measurable: androidx.compose.ui.layout.IntrinsicMeasurable, width: Int
    ): Int = measurable.minIntrinsicWidth(width)

    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.maxIntrinsicHeight(
        measurable: androidx.compose.ui.layout.IntrinsicMeasurable, width: Int
    ): Int = measurable.maxIntrinsicWidth(width)
}

/**
 * Renders [content] across the whole screen, rotated to face a rider holding
 * the phone sideways.
 */
@Composable
fun RotatedFullScreen(
    rotation: Int,
    withFade: Boolean = true,
    content: @Composable () -> Unit
) {
    // Always routed through rotateLayout so the fade-on-rotation applies even
    // for transitions to and from the upright (0 degree) orientation.
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().rotateLayout(rotation, withFade)
    ) {
        content()
    }
}

/**
 * A right-docked, full-height panel — the studio's editing surfaces (Add
 * element, element properties, viewport, presets) live here instead of bottom
 * sheets. It rotates with [LocalStudioRotation] so it stays upright for a
 * rider holding the phone sideways. Tapping the scrim dismisses it.
 */
@Composable
fun StudioSidePanel(
    onDismiss: () -> Unit,
    dimmed: Boolean = false,
    onToggleDim: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    RotatedFullScreen(LocalStudioRotation.current) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // A dimmed panel uses a near-clear scrim and a translucent surface
            // so the overlay being edited stays visible behind it.
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(if (dimmed) 0x1A000000 else 0x99000000))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            )
            Surface(
                // Wraps its content (capped) rather than filling the height, so
                // there is empty scrim above and below to tap-dismiss.
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(340.dp)
                    .heightIn(max = maxHeight * 0.92f)
                    .alpha(if (dimmed) 0.65f else 1f)
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                androidx.compose.foundation.layout.Box {
                    Column(
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        content = content
                    )
                    // The fade toggle floats in the top-right corner; the sheet
                    // headers are left-aligned, so it never collides with them.
                    if (onToggleDim != null) {
                        IconButton(
                            onClick = onToggleDim,
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Icon(
                                Icons.Default.Opacity,
                                contentDescription =
                                    stringResource(R.string.studio_replay_cd_fade),
                                tint = if (dimmed) StudioControlAccent
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------
// Add element
// --------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
/**
 * The Add-element list, in fixed groups. The groups and their order — and the
 * order within each group (English-alphabetical by element name) — are the SAME
 * in every language; only the labels are translated. So the menu positions stay
 * constant and familiar regardless of locale.
 */
private val ADD_ELEMENT_GROUPS: List<Pair<Int, List<OverlayElementType>>> = listOf(
    R.string.studio_group_data to listOf(
        OverlayElementType.DATA_GRAPH,   // Data graph
        OverlayElementType.DATA_VALUE,   // Data value
        OverlayElementType.DATA_DIAL,    // Dial gauge
        OverlayElementType.G_FORCE,      // G-Force
        OverlayElementType.DATA_BAR,     // Linear bar
        OverlayElementType.MAP           // Map
    ),
    R.string.studio_group_text to listOf(
        OverlayElementType.APP_BADGE,    // App badge
        OverlayElementType.CLOCK,        // Clock
        OverlayElementType.TEXT,         // Free text
        OverlayElementType.WHEEL_NAME    // Wheel name
    ),
    R.string.studio_group_media to listOf(
        OverlayElementType.FLOATING_CAMERA,  // Floating camera
        OverlayElementType.IMAGE             // Image / clipart
    )
)

@Composable
fun AddElementSheet(
    onPick: (OverlayElementType) -> Unit,
    onPickImage: () -> Unit,
    dimmed: Boolean,
    onToggleDim: () -> Unit,
    onDismiss: () -> Unit
) {
    StudioSidePanel(onDismiss = onDismiss, dimmed = dimmed, onToggleDim = onToggleDim) {
        Column(
            Modifier
                .padding(bottom = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetHeader(stringResource(R.string.studio_add_element_title))
            ADD_ELEMENT_GROUPS.forEach { (titleRes, types) ->
                SectionLabel(stringResource(titleRes))
                types.forEach { type ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                if (type == OverlayElementType.IMAGE) onPickImage()
                                else onPick(type)
                            }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(type.icon, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(type.label(), fontWeight = FontWeight.SemiBold)
                            Text(
                                elementHint(type),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun elementHint(type: OverlayElementType): String = when (type) {
    OverlayElementType.WHEEL_NAME -> stringResource(R.string.studio_hint_wheel_name)
    OverlayElementType.APP_BADGE -> stringResource(R.string.studio_hint_app_badge)
    OverlayElementType.TEXT -> stringResource(R.string.studio_hint_text)
    OverlayElementType.DATA_VALUE -> stringResource(R.string.studio_hint_data_value)
    OverlayElementType.DATA_GRAPH -> stringResource(R.string.studio_hint_data_graph)
    OverlayElementType.DATA_DIAL -> stringResource(R.string.studio_hint_data_dial)
    OverlayElementType.DATA_BAR -> stringResource(R.string.studio_hint_data_bar)
    OverlayElementType.FLOATING_CAMERA -> stringResource(R.string.studio_hint_floating_camera)
    OverlayElementType.IMAGE -> stringResource(R.string.studio_hint_image)
    OverlayElementType.CLOCK -> stringResource(R.string.studio_hint_clock)
    OverlayElementType.G_FORCE -> stringResource(R.string.studio_hint_g_force)
    OverlayElementType.MAP -> stringResource(R.string.studio_hint_map)
}

// --------------------------------------------------------------------------
// Layout picker
// --------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutPickerSheet(
    current: ViewportLayout,
    onPick: (ViewportLayout) -> Unit,
    onDismiss: () -> Unit
) {
    StudioSidePanel(onDismiss = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 4.dp)
                .padding(bottom = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetHeader(stringResource(R.string.studio_layout_title))
            Spacer(Modifier.height(4.dp))
            ViewportLayout.entries.chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { layout ->
                        LayoutChoice(
                            layout = layout,
                            selected = layout == current,
                            modifier = Modifier.weight(1f),
                            onClick = { onPick(layout) }
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun LayoutChoice(
    layout: ViewportLayout,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val border = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Mini diagram of the panes — transposed in landscape to match the name
        // and what the rider will actually see recorded.
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxWidth().height(48.dp)
        ) {
            val w = maxWidth
            val h = maxHeight
            val shown = layout.orientedForDisplay(studioLandscape())
            paneRects(shown, shown.defaultDividers()).forEach { r ->
                Box(
                    Modifier
                        .offset(x = w * r.left, y = h * r.top)
                        .size(width = w * r.width, height = h * r.height)
                        .padding(1.5.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            layout.displayName(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// --------------------------------------------------------------------------
// Save / load presets
// --------------------------------------------------------------------------

@Composable
fun SavePresetDialog(
    folderAvailable: Boolean,
    onSave: (String) -> Unit,
    onOpenFolderSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.rotateLayout(LocalStudioRotation.current),
        title = { Text(stringResource(R.string.studio_save_preset_title)) },
        text = {
            Column {
                if (!folderAvailable) {
                    FolderWarning(onOpenFolderSettings)
                } else {
                    Text(
                        stringResource(R.string.studio_save_preset_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.studio_save_preset_name_label)) },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = folderAvailable && name.isNotBlank(),
                onClick = { onSave(name.trim()) }
            ) { Text(stringResource(R.string.studio_save_btn)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoadPresetSheet(
    folderAvailable: Boolean,
    presets: List<String>,
    bundledPresets: List<String>,
    bundledLandscapePresets: List<String>,
    onLoad: (String) -> Unit,
    onLoadBundled: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenFolderSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    StudioSidePanel(onDismiss = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 4.dp)
                .padding(bottom = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetHeader(stringResource(R.string.studio_load_preset_title))

            if (bundledPresets.isNotEmpty()) {
                SectionLabel(stringResource(R.string.studio_section_portrait))
                bundledPresets.forEach { name ->
                    PresetRow(
                        name = name,
                        icon = Icons.Default.Inventory2,
                        onClick = { onLoadBundled(name) },
                        onDelete = null
                    )
                }
            }
            if (bundledLandscapePresets.isNotEmpty()) {
                SectionLabel(stringResource(R.string.studio_section_landscape))
                bundledLandscapePresets.forEach { name ->
                    PresetRow(
                        name = name,
                        icon = Icons.Default.Inventory2,
                        onClick = { onLoadBundled(name) },
                        onDelete = null
                    )
                }
            }

            // "Your presets" is always shown: the section's value to the
            // rider doesn't depend on whether they happen to have any yet --
            // when empty (no folder set, or folder set but no files) we
            // surface the right next-step (folder picker / hint) right where
            // the saved presets would otherwise appear.
            SectionLabel(stringResource(R.string.studio_section_your_presets))
            if (!folderAvailable) {
                FolderWarning(onOpenFolderSettings)
            } else if (presets.isEmpty()) {
                Text(
                    stringResource(R.string.studio_your_presets_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)
                )
            } else {
                presets.forEach { name ->
                    PresetRow(
                        name = name,
                        icon = Icons.Default.Dashboard,
                        onClick = { onLoad(name) },
                        onDelete = { confirmDelete = name }
                    )
                }
            }
        }
    }
    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            modifier = Modifier.rotateLayout(LocalStudioRotation.current),
            title = { Text(stringResource(R.string.studio_dlg_delete_preset_title)) },
            text = { Text(stringResource(R.string.studio_dlg_delete_preset_body, name)) },
            confirmButton = {
                TextButton(onClick = { onDelete(name); confirmDelete = null }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

/** A tappable section header with an expand / collapse chevron. */
@Composable
private fun CollapsibleSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PresetRow(
    name: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        if (onDelete != null) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.studio_cd_delete_preset),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onDelete)
                    .padding(4.dp)
            )
        }
    }
}

@Composable
private fun FolderWarning(onOpenFolderSettings: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.FolderOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.studio_no_folder_body),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenFolderSettings) {
            // Opens that exact Settings section, so it carries its name.
            Text(stringResource(R.string.tab_cloud))
        }
    }
}

// --------------------------------------------------------------------------
// Viewport source config
// --------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ViewportConfigSheet(
    index: Int,
    config: ViewportConfig,
    cameras: List<StudioCameraInfo>,
    inUseKeys: Set<String>,
    dimmed: Boolean,
    geometryExpanded: Boolean,
    cameraStyleExpanded: Boolean,
    onToggleDim: () -> Unit,
    onGeometryExpandedChange: (Boolean) -> Unit,
    onCameraStyleExpandedChange: (Boolean) -> Unit,
    onChange: (ViewportConfig) -> Unit,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit
) {
    StudioSidePanel(onDismiss = onDismiss, dimmed = dimmed, onToggleDim = onToggleDim) {
        Column(
            Modifier
                .padding(horizontal = 4.dp)
                .padding(bottom = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetHeader(stringResource(R.string.studio_viewport_title, index + 1))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.source == ViewportSourceType.CAMERA,
                    onClick = { onChange(config.copy(source = ViewportSourceType.CAMERA)) },
                    label = { Text(stringResource(R.string.studio_viewport_camera)) },
                    leadingIcon = { Icon(Icons.Default.PhotoCamera, null) }
                )
                FilterChip(
                    selected = config.source == ViewportSourceType.SOLID ||
                        config.source == ViewportSourceType.GRADIENT,
                    onClick = {
                        if (config.source != ViewportSourceType.SOLID &&
                            config.source != ViewportSourceType.GRADIENT
                        ) onChange(config.copy(source = ViewportSourceType.SOLID))
                    },
                    // Icon-only — the fill-bucket glyph is self-explanatory
                    // and the chip stays compact.
                    label = {
                        Icon(
                            Icons.Default.FormatColorFill,
                            contentDescription = stringResource(R.string.studio_viewport_fill)
                        )
                    }
                )
                FilterChip(
                    selected = config.source == ViewportSourceType.IMAGE,
                    onClick = { onChange(config.copy(source = ViewportSourceType.IMAGE)) },
                    // Icon-only — the image glyph is self-explanatory.
                    label = {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = stringResource(R.string.studio_viewport_image)
                        )
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
            when (config.source) {
                ViewportSourceType.CAMERA -> {
                    // The camera picker is the primary choice, so it stays at the
                    // top. The spatial-transform controls (mirror, orientation,
                    // fit, zoom) are tucked into a collapsible "Geometry" section
                    // so the sheet stays short — its open / closed state is
                    // hoisted to the studio screen so it sticks for the session.
                    Text(stringResource(R.string.studio_viewport_camera_label), fontWeight = FontWeight.SemiBold)
                    CameraPicker(cameras, config.cameraKey, inUseKeys) {
                        onChange(config.copy(cameraKey = it))
                    }
                    Spacer(Modifier.height(8.dp))
                    CollapsibleSectionHeader(
                        title = stringResource(R.string.studio_cfg_geometry),
                        expanded = geometryExpanded,
                        onToggle = { onGeometryExpandedChange(!geometryExpanded) }
                    )
                    if (geometryExpanded) {
                        ToggleRow(stringResource(R.string.studio_cfg_mirror), config.cameraMirror) {
                            onChange(config.copy(cameraMirror = it))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.studio_cfg_orientation), fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0, 90, 180, 270).forEach { deg ->
                                FilterChip(
                                    selected = config.cameraOrientation == deg,
                                    onClick = { onChange(config.copy(cameraOrientation = deg)) },
                                    label = { Text(stringResource(R.string.studio_bg_direction_fmt, deg)) }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FitModePicker(config, onChange)
                        ZoomSlider(config, onChange)
                    }
                    // The colour-grading controls are tucked into a collapsible
                    // "Style" section — like "Geometry" above — so the sheet
                    // stays short; its open / closed state is hoisted for the
                    // session.
                    CollapsibleSectionHeader(
                        title = stringResource(R.string.studio_cfg_style),
                        expanded = cameraStyleExpanded,
                        onToggle = { onCameraStyleExpandedChange(!cameraStyleExpanded) }
                    )
                    if (cameraStyleExpanded) {
                        ColorGradeEditor(config, onChange)
                    }
                }
                ViewportSourceType.SOLID, ViewportSourceType.GRADIENT ->
                    BackgroundEditor(config, onChange)
                ViewportSourceType.IMAGE -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = onPickImage) {
                            Icon(Icons.Default.AddPhotoAlternate, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (config.imageData == null) stringResource(R.string.studio_viewport_choose_image) else stringResource(R.string.studio_viewport_replace_image))
                        }
                        if (config.imageData != null) {
                            OutlinedButton(
                                onClick = { onChange(config.copy(imageData = null)) }
                            ) { Text(stringResource(R.string.studio_viewport_clear_image)) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Same grouped layout as the camera source: fit / zoom under
                    // a collapsible Geometry section, the colour grade under
                    // Style — so the sheet stays short and the two sources read
                    // the same. The open / closed state is shared with camera.
                    CollapsibleSectionHeader(
                        title = stringResource(R.string.studio_cfg_geometry),
                        expanded = geometryExpanded,
                        onToggle = { onGeometryExpandedChange(!geometryExpanded) }
                    )
                    if (geometryExpanded) {
                        FitModePicker(config, onChange)
                        ZoomSlider(config, onChange)
                    }
                    CollapsibleSectionHeader(
                        title = stringResource(R.string.studio_cfg_style),
                        expanded = cameraStyleExpanded,
                        onToggle = { onCameraStyleExpandedChange(!cameraStyleExpanded) }
                    )
                    if (cameraStyleExpanded) {
                        ColorGradeEditor(config, onChange)
                    }
                }
            }
        }
    }
}

/**
 * Digital-zoom slider — a spatial transform feeding a GPU scale. Kept separate
 * from [ColorGradeEditor] so the camera viewport can group it with the other
 * geometry controls under its collapsible "Geometry" section.
 */
@Composable
private fun ZoomSlider(config: ViewportConfig, onChange: (ViewportConfig) -> Unit) {
    LabeledSlider(
        stringResource(R.string.studio_cfg_zoom),
        "%.1fx".format(config.zoom),
        config.zoom, 1f, 3f
    ) { onChange(config.copy(zoom = it)) }
}

/**
 * Filter preset + brightness / contrast / saturation for a camera or image
 * viewport. Every control feeds a GPU ColorMatrix — there is no per-frame CPU
 * pixel work. Digital zoom lives in its own [ZoomSlider] (a spatial transform).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorGradeEditor(config: ViewportConfig, onChange: (ViewportConfig) -> Unit) {
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.studio_cfg_filter), fontWeight = FontWeight.SemiBold)
    val filters = listOf(
        "NONE" to stringResource(R.string.studio_cfg_filter_none),
        "BW" to stringResource(R.string.studio_cfg_filter_bw),
        "SEPIA" to stringResource(R.string.studio_cfg_filter_sepia),
        "WARM" to stringResource(R.string.studio_cfg_filter_warm),
        "COOL" to stringResource(R.string.studio_cfg_filter_cool),
        "VIVID" to stringResource(R.string.studio_cfg_filter_vivid),
        "NOIR" to stringResource(R.string.studio_cfg_filter_noir),
        "VINTAGE" to stringResource(R.string.studio_cfg_filter_vintage),
        "MATTE" to stringResource(R.string.studio_cfg_filter_matte)
    )
    // A single side-scrolling row, like the camera picker — the sheet stays
    // short and the filters never wrap onto a second line.
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        items(filters.size) { i ->
            val (key, lbl) = filters[i]
            FilterChip(
                selected = config.colorFilter == key,
                onClick = { onChange(config.copy(colorFilter = key)) },
                label = { Text(lbl) }
            )
        }
    }
    LabeledSlider(
        stringResource(R.string.studio_cfg_brightness),
        (config.brightness * 100).roundToInt().toString(),
        config.brightness, -1f, 1f
    ) { onChange(config.copy(brightness = it)) }
    LabeledSlider(
        stringResource(R.string.studio_cfg_contrast),
        "%.2f".format(config.contrast),
        config.contrast, 0f, 2f
    ) { onChange(config.copy(contrast = it)) }
    LabeledSlider(
        stringResource(R.string.studio_cfg_saturation),
        "%.2f".format(config.saturation),
        config.saturation, 0f, 2f
    ) { onChange(config.copy(saturation = it)) }
}

/**
 * Crop / Fit / Center chips controlling how the camera frame or source image
 * fills its viewport. Shown for any source that draws visual content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FitModePicker(config: ViewportConfig, onChange: (ViewportConfig) -> Unit) {
    Text(stringResource(R.string.studio_cfg_fit), fontWeight = FontWeight.SemiBold)
    val fitCrop = stringResource(R.string.studio_cfg_fit_crop)
    val fitContain = stringResource(R.string.studio_cfg_fit_contain)
    val fitCenter = stringResource(R.string.studio_cfg_fit_center)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "CROP" to fitCrop, "FIT" to fitContain, "CENTER" to fitCenter
        ).forEach { (key, lbl) ->
            FilterChip(
                selected = config.fitMode == key,
                onClick = { onChange(config.copy(fitMode = key)) },
                label = { Text(lbl) }
            )
        }
    }
}

/**
 * A row of chips, one per detected camera. Two cameras can stream at once;
 * once two distinct ones are in use, the rest are disabled — but an
 * already-streaming camera stays pickable, since it just reuses that feed.
 */
@Composable
private fun CameraPicker(
    cameras: List<StudioCameraInfo>,
    selectedKey: String,
    inUseKeys: Set<String>,
    onPick: (String) -> Unit
) {
    if (cameras.isEmpty()) {
        Text(
            stringResource(R.string.studio_camera_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    // Count cameras used by OTHER panes only — this pane's own camera does not
    // count, so picking the 2nd of two panes is fine; the limit bites at a 3rd.
    val atLimit = inUseKeys.count { it != selectedKey } >= 2
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        items(cameras.size) { i ->
            val cam = cameras[i]
            FilterChip(
                selected = cam.key == selectedKey,
                enabled = !atLimit || cam.key in inUseKeys,
                onClick = { onPick(cam.key) },
                label = { Text(cam.label) },
                leadingIcon = { Icon(Icons.Default.PhotoCamera, null) }
            )
        }
    }
    if (atLimit) {
        Text(
            stringResource(R.string.studio_camera_limit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Line colour + thickness for the dividers between viewport panes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DividerConfigSheet(
    color: Long,
    thickness: Float,
    onChange: (Long, Float) -> Unit,
    dimmed: Boolean,
    onToggleDim: () -> Unit,
    onDismiss: () -> Unit
) {
    StudioSidePanel(onDismiss = onDismiss, dimmed = dimmed, onToggleDim = onToggleDim) {
        Column(
            Modifier
                .padding(horizontal = 4.dp)
                .padding(bottom = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetHeader(stringResource(R.string.studio_divider_title))
            Text(stringResource(R.string.studio_divider_colour_label), fontWeight = FontWeight.SemiBold)
            ColorSwatchRow(color) { onChange(it, thickness) }
            LabeledSlider(
                stringResource(R.string.studio_divider_thickness_label),
                stringResource(R.string.studio_divider_thickness_fmt, thickness.toInt()),
                thickness, 1f, 16f
            ) { onChange(color, kotlin.math.round(it)) }
        }
    }
}

// --------------------------------------------------------------------------
// Background editor — Solid / Linear / Radial (a viewport source)
// --------------------------------------------------------------------------

/** Switch a viewport's background to a gradient, carrying the solid colour. */
private fun toGradient(config: ViewportConfig, radial: Boolean): ViewportConfig {
    if (config.source == ViewportSourceType.GRADIENT) {
        return config.copy(gradientRadial = radial)
    }
    // Coming from solid — seed the gradient's first stop with the solid colour.
    val colors = config.gradientColors.ifEmpty { listOf(0xFF000000L, 0xFFFFFFFFL) }
        .toMutableList()
        .also { it[0] = config.solidColor }
    return config.copy(
        source = ViewportSourceType.GRADIENT,
        gradientRadial = radial,
        gradientColors = colors
    )
}

@Composable
private fun BackgroundEditor(config: ViewportConfig, onChange: (ViewportConfig) -> Unit) {
    val isSolid = config.source == ViewportSourceType.SOLID
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = isSolid,
            onClick = {
                if (!isSolid) onChange(
                    config.copy(
                        source = ViewportSourceType.SOLID,
                        solidColor = config.gradientColors.firstOrNull() ?: config.solidColor
                    )
                )
            },
            label = { Text(stringResource(R.string.studio_bg_solid)) }
        )
        FilterChip(
            selected = !isSolid && !config.gradientRadial,
            onClick = { onChange(toGradient(config, radial = false)) },
            label = { Text(stringResource(R.string.studio_bg_linear)) }
        )
        FilterChip(
            selected = !isSolid && config.gradientRadial,
            onClick = { onChange(toGradient(config, radial = true)) },
            label = { Text(stringResource(R.string.studio_bg_radial)) }
        )
    }
    Spacer(Modifier.height(12.dp))
    if (isSolid) {
        Text(stringResource(R.string.studio_bg_colour_label), fontWeight = FontWeight.SemiBold)
        // A pane background is the bottom layer — a transparent fill is
        // meaningless, so the swatch row is opaque-only here.
        ColorSwatchRow(config.solidColor, allowTransparent = false) {
            onChange(config.copy(solidColor = it))
        }
    } else {
        GradientEditor(config, onChange)
    }
}

@Composable
private fun GradientEditor(config: ViewportConfig, onChange: (ViewportConfig) -> Unit) {
    if (!config.gradientRadial) {
        LabeledSlider(
            stringResource(R.string.studio_bg_direction_label),
            stringResource(R.string.studio_bg_direction_fmt, config.gradientAngle.toInt()),
            config.gradientAngle, 0f, 360f
        ) { onChange(config.copy(gradientAngle = it)) }
    }
    Spacer(Modifier.height(10.dp))
    GradientPreview(config)
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.studio_bg_colour_stops), fontWeight = FontWeight.SemiBold)
    config.gradientColors.indices.forEach { i ->
        GradientStopRow(
            color = config.gradientColors[i],
            position = config.gradientStops.getOrElse(i) { 0f },
            canRemove = config.gradientColors.size > 2,
            onColor = { c ->
                onChange(
                    config.copy(
                        gradientColors = config.gradientColors.toMutableList()
                            .also { it[i] = c }
                    )
                )
            },
            onPosition = { p ->
                onChange(
                    config.copy(
                        gradientStops = config.gradientStops.toMutableList()
                            .also { it[i] = p }
                    )
                )
            },
            onRemove = {
                onChange(
                    config.copy(
                        gradientColors = config.gradientColors.toMutableList()
                            .also { it.removeAt(i) },
                        gradientStops = config.gradientStops.toMutableList()
                            .also { it.removeAt(i) }
                    )
                )
            }
        )
    }
    OutlinedButton(
        onClick = {
            // Add a stop at 100% and re-space every stop evenly, so the
            // existing ones compress to make room (0/100 -> 0/50/100 ->
            // 0/33/66/100) instead of piling up on the last position.
            val newColors = config.gradientColors + 0xFFFFFFFFL
            val n = newColors.size
            val newStops = List(n) { i -> i.toFloat() / (n - 1).coerceAtLeast(1) }
            onChange(config.copy(gradientColors = newColors, gradientStops = newStops))
        },
        // Cap at 8 stops — more than that is unwieldy to edit, and no real
        // gradient needs it.
        enabled = config.gradientColors.size < 8,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.studio_bg_add_stop))
    }
}

@Composable
private fun GradientPreview(config: ViewportConfig) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .drawBehind { drawRect(brush = studioGradientBrush(config)) }
    )
}

@Composable
private fun GradientStopRow(
    color: Long,
    position: Float,
    canRemove: Boolean,
    onColor: (Long) -> Unit,
    onPosition: (Float) -> Unit,
    onRemove: () -> Unit
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                // Gradient stops paint a pane background (linear or radial) —
                // opaque-only, a transparent stop has nothing to blend with.
                ColorSwatchRow(color, allowTransparent = false) { onColor(it) }
            }
            if (canRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.studio_cd_remove_stop),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onRemove)
                        .padding(4.dp)
                )
            }
        }
        Slider(
            value = position.coerceIn(0f, 1f),
            onValueChange = { onPosition(it.coerceIn(0f, 1f)) },
            valueRange = 0f..1f
        )
    }
}

// --------------------------------------------------------------------------
// Element config
// --------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementConfigSheet(
    element: OverlayElement,
    cameras: List<StudioCameraInfo>,
    inUseKeys: Set<String>,
    dimmed: Boolean,
    styleExpanded: Boolean,
    /**
     * True when the rider has a custom marker photo saved
     * (`AppSettings.navUserMarkerPhotoDataUrl != null`). Controls whether the
     * MAP element shows an active "Prefer customized marker" toggle or just a
     * hint pointing the rider to the Navigator to set one.
     */
    hasCustomRiderMarker: Boolean,
    onToggleDim: () -> Unit,
    onStyleExpandedChange: (Boolean) -> Unit,
    onChange: (OverlayElement) -> Unit,
    onReplaceImage: () -> Unit,
    onDismiss: () -> Unit
) {
    StudioSidePanel(onDismiss = onDismiss, dimmed = dimmed, onToggleDim = onToggleDim) {
        Column(
            Modifier
                .padding(horizontal = 4.dp)
                .padding(bottom = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetHeader(element.type.label())

            // Metric chooser comes first whenever the widget has one: the
            // metric is the widget's identity (a SPEED graph vs a BATTERY
            // graph is functionally a different widget), so the rider sees
            // and picks that before anything else.
            val isGauge = element.type == OverlayElementType.DATA_DIAL ||
                element.type == OverlayElementType.DATA_BAR
            val hasMetric = element.type == OverlayElementType.DATA_VALUE ||
                element.type == OverlayElementType.DATA_GRAPH || isGauge
            if (hasMetric) {
                Text(
                    stringResource(R.string.studio_cfg_metric_label),
                    fontWeight = FontWeight.SemiBold
                )
                MetricPicker(element.metric) { key ->
                    onChange(
                        if (isGauge) element.copy(
                            metric = key,
                            gaugeMax = StudioMetric.fromKey(key).defaultMax
                        ) else element.copy(metric = key)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (element.type == OverlayElementType.TEXT) {
                OutlinedTextField(
                    value = element.text,
                    onValueChange = { onChange(element.copy(text = it)) },
                    label = { Text(stringResource(R.string.studio_cfg_text_label)) },
                    minLines = 2,
                    // Preview the chosen alignment right in the field.
                    textStyle = LocalTextStyle.current.copy(
                        textAlign = when (element.textAlign) {
                            "CENTER" -> TextAlign.Center
                            "END" -> TextAlign.End
                            else -> TextAlign.Start
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.studio_cfg_text_variables),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            // Alignment — for free text and for the live value pill, which
            // fills a fixed width so it can be left / centre / right aligned.
            if (element.type == OverlayElementType.TEXT ||
                element.type == OverlayElementType.DATA_VALUE
            ) {
                Text(stringResource(R.string.studio_cfg_alignment), fontWeight = FontWeight.SemiBold)
                val alignLeft = stringResource(R.string.studio_cfg_align_left)
                val alignCentre = stringResource(R.string.studio_cfg_align_centre)
                val alignRight = stringResource(R.string.studio_cfg_align_right)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "START" to alignLeft, "CENTER" to alignCentre, "END" to alignRight
                    ).forEach { (key, lbl) ->
                        FilterChip(
                            selected = element.textAlign == key,
                            onClick = { onChange(element.copy(textAlign = key)) },
                            label = { Text(lbl) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (element.type == OverlayElementType.CLOCK) {
                Text(stringResource(R.string.studio_cfg_clock_style), fontWeight = FontWeight.SemiBold)
                val clockDigital = stringResource(R.string.studio_cfg_clock_digital)
                val clockAnalog = stringResource(R.string.studio_cfg_clock_analog)
                val clockText = stringResource(R.string.studio_cfg_clock_text)
                val clockStopwatch = stringResource(R.string.studio_cfg_clock_stopwatch)
                listOf(
                    "DIGITAL" to clockDigital, "ANALOG" to clockAnalog,
                    "TEXT" to clockText, "STOPWATCH" to clockStopwatch
                ).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (key, lbl) ->
                            FilterChip(
                                selected = element.clockStyle == key,
                                onClick = { onChange(element.copy(clockStyle = key)) },
                                label = { Text(lbl) }
                            )
                        }
                    }
                }
                if (element.clockStyle == "TEXT") {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = element.clockShowDate,
                            onCheckedChange = { onChange(element.copy(clockShowDate = it)) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.studio_cfg_clock_show_date))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (isGauge) {
                val metricMax = StudioMetric.fromKey(element.metric).defaultMax
                LabeledSlider(
                    stringResource(R.string.studio_cfg_max_label),
                    element.gaugeMax.toInt().toString(),
                    element.gaugeMax,
                    metricMax * 0.2f, metricMax * 2.5f
                ) { onChange(element.copy(gaugeMax = it)) }
            }

            if (element.type == OverlayElementType.DATA_VALUE ||
                element.type == OverlayElementType.DATA_BAR
            ) {
                if (element.type == OverlayElementType.DATA_VALUE) {
                    // Unit-label position: BEFORE the value (LEFT) or AFTER
                    // it (RIGHT). For "km/h 42" / "42 km/h" style overlays.
                    // Sits above the Show-label toggle so the rider settles
                    // identity (metric + unit placement) before binary
                    // visibility toggles.
                    Text(
                        stringResource(R.string.studio_cfg_unit_position),
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "RIGHT" to stringResource(R.string.studio_cfg_unit_right),
                            "LEFT" to stringResource(R.string.studio_cfg_unit_left)
                        ).forEach { (key, lbl) ->
                            FilterChip(
                                selected = element.unitPosition == key,
                                onClick = { onChange(element.copy(unitPosition = key)) },
                                label = { Text(lbl) }
                            )
                        }
                    }
                }
                ToggleRow(stringResource(R.string.studio_cfg_show_label), element.showLabel) {
                    onChange(element.copy(showLabel = it))
                }
                if (element.type == OverlayElementType.DATA_BAR) {
                    ToggleRow(
                        stringResource(R.string.studio_cfg_show_value), element.barShowValue
                    ) { onChange(element.copy(barShowValue = it)) }
                }
            }
            if (element.type == OverlayElementType.DATA_DIAL) {
                // Full ring vs upper-half "speedometer" arc.
                Text(
                    stringResource(R.string.studio_cfg_dial_style),
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "FULL" to stringResource(R.string.studio_cfg_dial_full),
                        "SEMICIRCLE" to stringResource(R.string.studio_cfg_dial_semi)
                    ).forEach { (key, lbl) ->
                        FilterChip(
                            selected = element.dialStyle == key,
                            onClick = { onChange(element.copy(dialStyle = key)) },
                            label = { Text(lbl) }
                        )
                    }
                }
            }

            if (element.type == OverlayElementType.APP_BADGE) {
                ToggleRow(stringResource(R.string.studio_cfg_badge_stacked), element.badgeStacked) {
                    onChange(element.copy(badgeStacked = it))
                }
                ToggleRow(stringResource(R.string.studio_cfg_badge_show_name), element.showLabel) {
                    onChange(element.copy(showLabel = it))
                }
                ToggleRow(stringResource(R.string.studio_cfg_badge_show_version), element.badgeShowVersion) {
                    onChange(element.copy(badgeShowVersion = it))
                }
            }

            if (element.type == OverlayElementType.DATA_GRAPH) {
                LabeledSlider(
                    stringResource(R.string.studio_cfg_time_window_label),
                    stringResource(R.string.studio_cfg_time_window_fmt, element.graphWindowSec),
                    element.graphWindowSec.toFloat(), 10f, 300f
                ) { onChange(element.copy(graphWindowSec = it.toInt())) }
            }

            if (element.type == OverlayElementType.G_FORCE) {
                // Trail length 2–20 s. The trail is rebuilt per frame so longer
                // windows are expensive, and beyond ~20 s the rider's actual
                // motion path stops being useful anyway.
                LabeledSlider(
                    stringResource(R.string.studio_cfg_trail_label),
                    stringResource(R.string.studio_cfg_time_window_fmt, element.graphWindowSec),
                    element.graphWindowSec.coerceIn(2, 20).toFloat(), 2f, 20f, steps = 17
                ) { onChange(element.copy(graphWindowSec = it.toInt())) }
                // Outer-ring g value. A smaller scale magnifies small
                // movements, making them more evident on the recording.
                // Discrete 0.25 g stops from 0.5 g to 4 g (14 intervals → 13
                // intermediate snap points) — this also kills the slider-drag
                // recomposition storm because onChange only fires once per snap.
                LabeledSlider(
                    stringResource(R.string.studio_cfg_g_scale),
                    "%.2f g".format(element.gForceScale),
                    element.gForceScale.coerceIn(0.5f, 4f), 0.5f, 4f, steps = 13
                ) { onChange(element.copy(gForceScale = it)) }
                // Smoothing slider was removed — the trail and dot now share
                // their 50 Hz data source and stay visually connected without
                // any artificial easing, the same way the live-data crosshair
                // does it. gForceSmoothing remains on the data model for
                // back-compat with saved presets but has no rendering effect.
            }

            if (element.type == OverlayElementType.MAP) {
                Text(stringResource(R.string.studio_cfg_map_style), fontWeight = FontWeight.SemiBold)
                val mapStreet = stringResource(R.string.studio_cfg_map_street)
                val mapDark = stringResource(R.string.studio_cfg_map_dark)
                val mapSatellite = stringResource(R.string.studio_cfg_map_satellite)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "STREET" to mapStreet, "DARK" to mapDark,
                        "SATELLITE" to mapSatellite
                    ).forEach { (key, lbl) ->
                        FilterChip(
                            selected = element.mapStyle == key,
                            onClick = { onChange(element.copy(mapStyle = key)) },
                            label = { Text(lbl) }
                        )
                    }
                }
                LabeledSlider(
                    stringResource(R.string.studio_cfg_map_zoom),
                    element.mapZoom.toString(),
                    element.mapZoom.toFloat(), 10f, 19f, steps = 8
                ) { onChange(element.copy(mapZoom = it.roundToInt())) }
                ToggleRow(
                    stringResource(R.string.studio_cfg_map_rotate),
                    element.mapRotateWithHeading
                ) { onChange(element.copy(mapRotateWithHeading = it)) }
                ToggleRow(
                    stringResource(R.string.studio_cfg_map_trace),
                    element.mapTrace
                ) { onChange(element.copy(mapTrace = it)) }
                // Custom-marker preference. Only meaningful when the rider
                // has set a photo in the Navigator — until then we show a
                // hint instead of a dead toggle so it's obvious where to go.
                if (hasCustomRiderMarker) {
                    ToggleRow(
                        stringResource(R.string.studio_cfg_map_use_custom_marker),
                        element.mapUseCustomMarker
                    ) { onChange(element.copy(mapUseCustomMarker = it)) }
                } else {
                    Text(
                        stringResource(R.string.studio_cfg_map_use_custom_marker_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }
                // The map's configurable colour is its border (the trace and
                // dot share it). The fill is a fixed neutral — it only shows
                // for a moment while the tiles load.
                Text(
                    stringResource(R.string.studio_cfg_border_colour),
                    fontWeight = FontWeight.SemiBold
                )
                ColorSwatchRow(element.foreground, allowTransparent = false) {
                    onChange(element.copy(foreground = it))
                }
                LabeledSlider(
                    stringResource(R.string.studio_cfg_border_width),
                    stringResource(
                        R.string.studio_divider_thickness_fmt,
                        element.mapBorderWidth.toInt()
                    ),
                    element.mapBorderWidth, 0f, 8f
                ) { onChange(element.copy(mapBorderWidth = it)) }
                Spacer(Modifier.height(8.dp))
            }

            if (element.type == OverlayElementType.FLOATING_CAMERA) {
                Text(stringResource(R.string.studio_cfg_camera_label), fontWeight = FontWeight.SemiBold)
                CameraPicker(cameras, element.cameraKey, inUseKeys) {
                    onChange(element.copy(cameraKey = it))
                }
                Spacer(Modifier.height(8.dp))
            }

            if (element.type == OverlayElementType.IMAGE) {
                OutlinedButton(onClick = onReplaceImage) {
                    Icon(Icons.Default.AddPhotoAlternate, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.studio_cfg_replace_image))
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow(stringResource(R.string.studio_cfg_chroma_key), element.chromaKeyEnabled) {
                    onChange(element.copy(chromaKeyEnabled = it))
                }
                if (element.chromaKeyEnabled) {
                    Text(stringResource(R.string.studio_cfg_mask_colour), fontWeight = FontWeight.SemiBold)
                    ColorSwatchRow(element.chromaKeyColor, ChromaPalette) {
                        onChange(element.copy(chromaKeyColor = it))
                    }
                    LabeledSlider(
                        stringResource(R.string.studio_cfg_threshold_label),
                        stringResource(R.string.studio_cfg_threshold_fmt, (element.chromaKeyTolerance * 100).toInt()),
                        element.chromaKeyTolerance, 0.02f, 0.6f
                    ) { onChange(element.copy(chromaKeyTolerance = it)) }
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { onChange(element.copy(x = 0.12f, y = 0.14f)) }) {
                Text(stringResource(R.string.studio_cfg_reset_position))
            }

            // The generic styling controls (colours, opacity, rotation, drop
            // shadow) live in a collapsible "Style" section so the sheet stays
            // short. Its open / closed state is hoisted to the studio screen so
            // it sticks for the session as the rider hops between elements.
            Spacer(Modifier.height(8.dp))
            CollapsibleSectionHeader(
                title = stringResource(R.string.studio_cfg_style),
                expanded = styleExpanded,
                onToggle = { onStyleExpandedChange(!styleExpanded) }
            )
            if (styleExpanded) {
                // Colours. A transparent "Background" swatch makes the element's
                // backdrop invisible.
                if (element.type != OverlayElementType.FLOATING_CAMERA &&
                    element.type != OverlayElementType.IMAGE &&
                    element.type != OverlayElementType.MAP
                ) {
                    Text(stringResource(R.string.studio_cfg_text_colour), fontWeight = FontWeight.SemiBold)
                    ColorSwatchRow(element.foreground, allowTransparent = false) {
                        onChange(element.copy(foreground = it))
                    }
                    Text(stringResource(R.string.studio_cfg_background), fontWeight = FontWeight.SemiBold)
                    ColorSwatchRow(element.background) {
                        onChange(element.copy(background = it))
                    }
                }

                // Opacity + rotation apply to every element.
                LabeledSlider(
                    stringResource(R.string.studio_cfg_opacity_label),
                    stringResource(R.string.studio_cfg_opacity_fmt, (element.opacity * 100).toInt()),
                    element.opacity, 0.1f, 1f, steps = 17
                ) { onChange(element.copy(opacity = it)) }
                // The rotate handle's accent — ties this slider visually to the
                // orange rotate grip on the element itself.
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        tint = Color(0xFFFFB74D),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.studio_cfg_rotation_label),
                        Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(
                            R.string.studio_cfg_rotation_fmt, element.rotationDeg.toInt()
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = element.rotationDeg.coerceIn(-180f, 180f),
                    onValueChange = {
                        onChange(element.copy(rotationDeg = it.coerceIn(-180f, 180f)))
                    },
                    valueRange = -180f..180f,
                    steps = 71
                )
                // Drop shadow applies to every element type.
                ToggleRow(stringResource(R.string.studio_cfg_shadow), element.shadow) {
                    onChange(element.copy(shadow = it))
                }
                // Shadow sub-controls — only meaningful when the shadow is on.
                if (element.shadow) {
                    Text(
                        stringResource(R.string.studio_cfg_shadow_colour),
                        fontWeight = FontWeight.SemiBold
                    )
                    ColorSwatchRow(element.shadowColor, allowTransparent = false) {
                        onChange(element.copy(shadowColor = it))
                    }
                    LabeledSlider(
                        stringResource(R.string.studio_cfg_shadow_strength),
                        "${(element.shadowStrength * 100).toInt()}%",
                        element.shadowStrength, 0f, 1f
                    ) { onChange(element.copy(shadowStrength = it)) }
                    LabeledSlider(
                        stringResource(R.string.studio_cfg_shadow_offset),
                        element.shadowDistance.roundToInt().toString(),
                        element.shadowDistance, 0f, 16f
                    ) { onChange(element.copy(shadowDistance = it)) }
                    LabeledSlider(
                        stringResource(R.string.studio_cfg_shadow_angle),
                        stringResource(
                            R.string.studio_bg_direction_fmt,
                            element.shadowAngle.roundToInt()
                        ),
                        element.shadowAngle, 0f, 360f
                    ) { onChange(element.copy(shadowAngle = it)) }
                }
            }
        }
    }
}

@Composable
private fun MetricPicker(selected: String, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(StudioMetric.entries.size) { i ->
            val metric = StudioMetric.entries[i]
            FilterChip(
                selected = metric.key == selected,
                onClick = { onPick(metric.key) },
                label = { Text(metric.displayName()) }
            )
        }
    }
}

@Composable
private fun ColorSwatchRow(
    selected: Long,
    palette: List<Long> = StudioPalette,
    allowTransparent: Boolean = true,
    onPick: (Long) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val shown = if (allowTransparent) palette
    else palette.filterNot { (it ushr 24) == 0L }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        items(shown.size) { i ->
            val c = shown[i]
            ColorSwatch(
                color = c,
                selected = c == selected,
                transparent = (c ushr 24) == 0L,
                onClick = { onPick(c) }
            )
        }
        // Trailing swatch — opens a full picker for any other colour.
        item {
            CustomColorSwatch(
                current = selected,
                isActive = selected !in shown,
                onClick = { showPicker = true }
            )
        }
    }
    if (showPicker) {
        ColorPickerDialog(
            initial = selected,
            allowAlpha = allowTransparent,
            onPick = { showPicker = false; onPick(it) },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Long,
    selected: Boolean,
    transparent: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (transparent) Color(0xFF3A3A42) else Color(color))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // The fully-transparent swatch reads as "invisible / none".
        if (transparent) {
            Icon(
                Icons.Default.FormatColorReset,
                contentDescription = stringResource(R.string.studio_cd_invisible),
                tint = Color(0xFFBBBBBB),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun CustomColorSwatch(current: Long, isActive: Boolean, onClick: () -> Unit) {
    val rainbow = Brush.sweepGradient(
        listOf(
            Color(0xFFE53935), Color(0xFFFDD835), Color(0xFF43A047),
            Color(0xFF00E5FF), Color(0xFF1E88E5), Color(0xFF8E24AA),
            Color(0xFFE53935)
        )
    )
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .then(
                if (isActive) Modifier.background(Color(current))
                else Modifier.background(rainbow)
            )
            .border(
                width = if (isActive) 3.dp else 1.dp,
                color = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Colorize,
            contentDescription = stringResource(R.string.studio_cd_custom_colour),
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ColorPickerDialog(
    initial: Long,
    allowAlpha: Boolean,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val startHsv = remember(initial) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toInt(), it) }
    }
    var hue by remember { mutableStateOf(startHsv[0]) }
    var sat by remember { mutableStateOf(startHsv[1]) }
    var value by remember { mutableStateOf(startHsv[2]) }
    var alpha by remember {
        mutableStateOf(if (allowAlpha) ((initial ushr 24) and 0xFF) / 255f else 1f)
    }
    val color = Color.hsv(hue.coerceIn(0f, 360f), sat, value, alpha)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.rotateLayout(LocalStudioRotation.current),
        title = { Text(stringResource(R.string.studio_colour_picker_title)) },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color)
                )
                Spacer(Modifier.height(12.dp))
                SatValPanel(hue = hue, sat = sat, value = value) { s, v ->
                    sat = s
                    value = v
                }
                Spacer(Modifier.height(12.dp))
                HueBar(hue = hue) { hue = it }
                if (allowAlpha) {
                    LabeledSlider(
                        stringResource(R.string.studio_colour_opacity_label),
                        stringResource(R.string.studio_colour_opacity_fmt, (alpha * 100).toInt()),
                        alpha, 0f, 1f, 19
                    ) { alpha = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(color.toArgbLong()) }) { Text(stringResource(R.string.studio_colour_select)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** Saturation (x) × brightness (y) panel with a draggable thumb. */
@Composable
private fun SatValPanel(
    hue: Float,
    sat: Float,
    value: Float,
    onChange: (Float, Float) -> Unit
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val pure = Color.hsv(hue.coerceIn(0f, 360f), 1f, 1f)
        val emit = { x: Float, y: Float ->
            onChange(
                (x / wPx).coerceIn(0f, 1f),
                (1f - y / hPx).coerceIn(0f, 1f)
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Color.White, pure)))
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                )
                .pointerInput(Unit) {
                    detectTapGestures { emit(it.x, it.y) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { emit(it.x, it.y) }) { c, _ ->
                        c.consume()
                        emit(c.position.x, c.position.y)
                    }
                }
        )
        val density = LocalDensity.current
        Box(
            Modifier
                .offset(
                    x = with(density) { (sat * wPx).toDp() } - 9.dp,
                    y = with(density) { ((1f - value) * hPx).toDp() } - 9.dp
                )
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(3.dp, Color.Black.copy(alpha = 0.6f), CircleShape)
        )
    }
}

/** Rainbow hue bar with a draggable thumb. */
@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val hueColors = remember { (0..6).map { Color.hsv(it * 60f, 1f, 1f) } }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
    ) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(hueColors))
                .pointerInput(Unit) {
                    detectTapGestures {
                        onChange((it.x / wPx * 360f).coerceIn(0f, 360f))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            onChange((it.x / wPx * 360f).coerceIn(0f, 360f))
                        }
                    ) { c, _ ->
                        c.consume()
                        onChange((c.position.x / wPx * 360f).coerceIn(0f, 360f))
                    }
                }
        )
        val density = LocalDensity.current
        Box(
            Modifier
                .offset(x = with(density) { (hue / 360f * wPx).toDp() } - 13.dp)
                .size(26.dp)
                .clip(CircleShape)
                .border(3.dp, Color.White, CircleShape)
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueLabel: String,
    value: Float,
    min: Float,
    max: Float,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    // Material3 Slider fires onValueChange on every drag tick even when the
    // (snapped) value hasn't moved, so without this filter a stepped slider
    // can still pump the whole preset graph at 60 Hz during a drag. Track the
    // last value we forwarded and skip duplicates — for continuous sliders the
    // value also bounces a few times per pixel; epsilon-compare on those.
    var lastReported by remember(min, max, steps) { mutableStateOf(value) }
    val epsilon = if (steps > 0) 0f else (max - min) * 0.001f
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(valueLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = {
                val v = it.coerceIn(min, max)
                if (kotlin.math.abs(v - lastReported) > epsilon || v != lastReported) {
                    lastReported = v
                    onChange(v)
                }
            },
            valueRange = min..max,
            steps = steps
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SheetHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 12.dp).wrapContentHeight()
    )
}

// --------------------------------------------------------------------------
// Manage elements — list, reorder (z-order), select, delete
// --------------------------------------------------------------------------

@Composable
private fun OverlayElement.summary(): String = when (type) {
    OverlayElementType.DATA_VALUE, OverlayElementType.DATA_GRAPH ->
        "${type.label()} · ${StudioMetric.fromKey(metric).displayName()}"
    else -> type.label()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageElementsSheet(
    elements: List<OverlayElement>,
    canAddElement: Boolean,
    canChangePanes: Boolean,
    snapToGrid: Boolean,
    onMove: (Int, Int) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddElement: () -> Unit,
    onChangePanes: () -> Unit,
    onSnapToGrid: (Boolean) -> Unit,
    dimmed: Boolean,
    onToggleDim: () -> Unit,
    onDismiss: () -> Unit
) {
    StudioSidePanel(onDismiss = onDismiss, dimmed = dimmed, onToggleDim = onToggleDim) {
        Column(
            Modifier
                .padding(horizontal = 4.dp)
                .padding(bottom = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetHeader(stringResource(R.string.studio_manage_title))
            // Compact action buttons under the title. Both mirror the
            // matching entries in the tools flyout (same icon, same label
            // string) so the actions read as identical regardless of where
            // the rider invokes them. Sized to their content so the row
            // doesn't look top-heavy.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            ) {
                FilledTonalButton(
                    onClick = onAddElement,
                    enabled = canAddElement
                ) {
                    Icon(Icons.Default.Widgets, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.studio_flyout_add))
                }
                FilledTonalButton(
                    onClick = onChangePanes,
                    enabled = canChangePanes
                ) {
                    Icon(Icons.Default.Dashboard, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.studio_flyout_panes))
                }
            }
            if (elements.isNotEmpty()) {
                ReorderableColumn(
                    list = elements,
                    onSettle = { from, to -> onMove(from, to) },
                    modifier = Modifier.fillMaxWidth()
                ) { _, element, _ ->
                    key(element.id) {
                        ManageElementRow(
                            element = element,
                            dragHandle = Modifier.draggableHandle(),
                            snapToGrid = snapToGrid,
                            onSelect = { onSelect(element.id) },
                            onDelete = { onDelete(element.id) }
                        )
                    }
                }
            }
            // Snap-to-grid lives at the bottom so it stays adjacent to the
            // element list — the action it affects — instead of competing
            // with Add / Panes at the top of the sheet. When ON, drag &
            // resize round to a 5 dp grid; untouched elements stay at
            // their exact saved coords (re-toggling OFF restores them).
            SnapToGridRow(snapToGrid, onSnapToGrid)
        }
    }
}

@Composable
private fun SnapToGridRow(snapToGrid: Boolean, onSnapToGrid: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        androidx.compose.material3.Switch(
            checked = snapToGrid,
            onCheckedChange = onSnapToGrid
        )
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.studio_snap_to_grid))
    }
}

@Composable
private fun ManageElementRow(
    element: OverlayElement,
    dragHandle: Modifier,
    snapToGrid: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    // Show the same coordinates the canvas is drawing: while snap-to-grid is
    // ON, both render and list reflect the snapped values; toggling OFF
    // restores the un-snapped originals so the user sees the round-trip.
    val gridStepPx = with(LocalDensity.current) { 10.dp.toPx() }
    // 1280 px / 2856 px is a reasonable "typical phone canvas" denominator for
    // labelling -- the saved values are fractional so the exact canvas size
    // doesn't change WHICH lattice value snaps where, only the granularity of
    // the percentage display. Using a representative size keeps the displayed
    // percentage consistent with what the rider sees mid-edit.
    val refW = 1280f
    val refH = 2856f
    fun snapPct(v: Float, refPx: Float): Int {
        val raw = v * 100f
        if (!snapToGrid) return raw.roundToInt()
        val stepPct = gridStepPx / refPx * 100f
        return (kotlin.math.round(raw / stepPct) * stepPct).roundToInt()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DragIndicator,
            contentDescription = stringResource(R.string.studio_cd_drag_reorder),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = dragHandle
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            element.type.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(element.summary(), fontWeight = FontWeight.Medium, maxLines = 1)
            // All positions and sizes are stored as fractions of the canvas,
            // so the layout scales between portrait / landscape and any
            // canvas size. We surface the same percentages here.
            // Height is shown only when the rider has explicitly stretched
            // the element vertically; an h of 0 means "use natural aspect".
            val hPct = if (element.height > 0f)
                "${snapPct(element.height, refH)}%" else "auto"
            Text(
                "x ${snapPct(element.x, refW)}%   " +
                    "y ${snapPct(element.y, refH)}%   " +
                    "w ${snapPct(element.width, refW)}%   " +
                    "h $hPct",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Default.Delete,
            contentDescription = stringResource(R.string.studio_cd_delete_element),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onDelete)
                .padding(6.dp)
        )
    }
}
