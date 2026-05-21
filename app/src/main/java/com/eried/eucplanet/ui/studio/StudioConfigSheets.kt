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
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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

val ViewportLayout.displayName: String
    get() = when (this) {
        ViewportLayout.SINGLE -> "Full screen"
        ViewportLayout.ROWS_2 -> "2 rows"
        ViewportLayout.COLUMNS_2 -> "2 columns"
        ViewportLayout.ROWS_3 -> "3 rows"
        ViewportLayout.COLUMNS_3 -> "3 columns"
        ViewportLayout.GRID_4 -> "2 × 2 grid"
    }

private val OverlayElementType.label: String
    get() = when (this) {
        OverlayElementType.WHEEL_NAME -> "Wheel name"
        OverlayElementType.APP_BADGE -> "App badge"
        OverlayElementType.TEXT -> "Free text"
        OverlayElementType.DATA_VALUE -> "Data value"
        OverlayElementType.DATA_GRAPH -> "Data graph"
        OverlayElementType.DATA_DIAL -> "Dial gauge"
        OverlayElementType.DATA_BAR -> "Linear bar"
        OverlayElementType.FLOATING_CAMERA -> "Floating camera"
        OverlayElementType.IMAGE -> "Image / clipart"
    }

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
    }

// --------------------------------------------------------------------------
// Camera-tools flyout (the "..." button)
// --------------------------------------------------------------------------

@Composable
fun StudioToolsFlyout(
    expanded: Boolean,
    hasElements: Boolean,
    onDismiss: () -> Unit,
    onAddElement: () -> Unit,
    onManageElements: () -> Unit,
    onChangeLayout: () -> Unit,
    onNew: () -> Unit,
    onLoadPreset: () -> Unit,
    onSavePreset: () -> Unit,
    onReplayMode: () -> Unit,
    deviceRotation: Int = 0
) {
    // Two columns: saved Presets on the left, the live studio actions on the
    // right. Rotated so it faces a rider holding the phone sideways.
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Box(Modifier.rotateLayout(deviceRotation)) {
        Row(modifier = Modifier.padding(horizontal = 4.dp)) {
            Column(Modifier.width(154.dp)) {
                FlyoutSection("Preset")
                FlyoutItem(Icons.Default.Dashboard, "Panes") {
                    onDismiss(); onChangeLayout()
                }
                FlyoutItem(Icons.Default.NoteAdd, "New") { onDismiss(); onNew() }
                FlyoutItem(Icons.Default.Image, "Load") { onDismiss(); onLoadPreset() }
                FlyoutItem(Icons.Default.Save, "Save", enabled = hasElements) {
                    onDismiss(); onSavePreset()
                }
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.width(154.dp)) {
                FlyoutSection("Mode")
                FlyoutItem(Icons.Default.History, "Replay mode") {
                    onDismiss(); onReplayMode()
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                FlyoutSection("Elements")
                FlyoutItem(Icons.Default.Layers, "Manage", enabled = hasElements) {
                    onDismiss(); onManageElements()
                }
                FlyoutItem(Icons.Default.Widgets, "Add") { onDismiss(); onAddElement() }
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
            title = "New preset?"
            body = "This clears every overlay and resets the screen to a single " +
                "camera viewport. Saved presets are not affected."
            action = "New"
        }
        is StudioConfirm.LoadUserPreset -> {
            title = "Load preset?"
            body = "Loading \"${confirm.name}\" replaces your current preset. " +
                "Save the current one first if you want to keep it."
            action = "Load"
        }
        is StudioConfirm.LoadBundledPreset -> {
            title = "Load built-in preset?"
            body = "Loading \"${confirm.name}\" replaces your current preset. " +
                "Save the current one first if you want to keep it."
            action = "Load"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.rotateLayout(LocalStudioRotation.current),
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
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
fun Modifier.rotateLayout(rotation: Int): Modifier {
    val target = ((rotation % 360) + 360) % 360
    // Fade out, swap orientation while invisible, fade back in — a device
    // rotation eases the chrome over instead of snapping it around.
    val shown = remember { androidx.compose.runtime.mutableIntStateOf(target) }
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(target) {
        if (shown.intValue == target) return@LaunchedEffect
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
fun RotatedFullScreen(rotation: Int, content: @Composable () -> Unit) {
    // Always routed through rotateLayout so the fade-on-rotation applies even
    // for transitions to and from the upright (0 degree) orientation.
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().rotateLayout(rotation)
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
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    RotatedFullScreen(LocalStudioRotation.current) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            )
            Surface(
                // Wraps its content (capped) rather than filling the height, so
                // there is empty scrim above and below to tap-dismiss.
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(340.dp)
                    .heightIn(max = maxHeight * 0.92f)
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Column(
                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    content = content
                )
            }
        }
    }
}

// --------------------------------------------------------------------------
// Add element
// --------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddElementSheet(
    onPick: (OverlayElementType) -> Unit,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit
) {
    StudioSidePanel(onDismiss = onDismiss) {
        Column(
            Modifier
                .padding(bottom = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetHeader("Add element")
            OverlayElementType.entries.forEach { type ->
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
                        Text(type.label, fontWeight = FontWeight.SemiBold)
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

private fun elementHint(type: OverlayElementType): String = when (type) {
    OverlayElementType.WHEEL_NAME -> "The connected wheel's name"
    OverlayElementType.APP_BADGE -> "EUC Planet name and icon"
    OverlayElementType.TEXT -> "Custom text you write yourself"
    OverlayElementType.DATA_VALUE -> "A live value like speed or battery"
    OverlayElementType.DATA_GRAPH -> "A rolling graph of a value"
    OverlayElementType.DATA_DIAL -> "A circular dial gauge for a value"
    OverlayElementType.DATA_BAR -> "A linear bar gauge for a value"
    OverlayElementType.FLOATING_CAMERA -> "A small picture-in-picture camera"
    OverlayElementType.IMAGE -> "Your own image, embedded in the preset"
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
            SheetHeader("Camera panes")
            Text(
                "Pick how the screen is divided. Drag the dividers afterwards to " +
                    "resize each section.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
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
        // Mini diagram of the panes.
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxWidth().height(48.dp)
        ) {
            val w = maxWidth
            val h = maxHeight
            paneRects(layout, layout.defaultDividers()).forEach { r ->
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
            layout.displayName,
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
        title = { Text("Save preset") },
        text = {
            Column {
                if (!folderAvailable) {
                    FolderWarning(onOpenFolderSettings)
                } else {
                    Text(
                        "Presets are saved as individual .json files in your " +
                            "backup folder, under overlays/.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Preset name") },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = folderAvailable && name.isNotBlank(),
                onClick = { onSave(name.trim()) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
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
            SheetHeader("Load preset")

            if (bundledPresets.isNotEmpty()) {
                SectionLabel("Portrait")
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
                SectionLabel("Landscape")
                bundledLandscapePresets.forEach { name ->
                    PresetRow(
                        name = name,
                        icon = Icons.Default.Inventory2,
                        onClick = { onLoadBundled(name) },
                        onDelete = null
                    )
                }
            }

            if (!folderAvailable) {
                FolderWarning(onOpenFolderSettings)
            } else if (presets.isNotEmpty()) {
                SectionLabel("Your presets")
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
            title = { Text("Delete preset?") },
            text = { Text("\"$name\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { onDelete(name); confirmDelete = null }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
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
                contentDescription = "Delete preset",
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
                "No backup folder is set. Saved presets need a backup folder so " +
                    "each one can be its own file.",
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
    onChange: (ViewportConfig) -> Unit,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit
) {
    StudioSidePanel(onDismiss = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 4.dp)
                .padding(bottom = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetHeader("Section ${index + 1} source")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.source == ViewportSourceType.CAMERA,
                    onClick = { onChange(config.copy(source = ViewportSourceType.CAMERA)) },
                    label = { Text("Camera") },
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
                    label = { Text("Background") },
                    leadingIcon = { Icon(Icons.Default.FormatColorFill, null) }
                )
                FilterChip(
                    selected = config.source == ViewportSourceType.IMAGE,
                    onClick = { onChange(config.copy(source = ViewportSourceType.IMAGE)) },
                    label = { Text("Image") },
                    leadingIcon = { Icon(Icons.Default.Image, null) }
                )
            }
            Spacer(Modifier.height(12.dp))
            when (config.source) {
                ViewportSourceType.CAMERA -> {
                    Text("Camera", fontWeight = FontWeight.SemiBold)
                    CameraPicker(cameras, config.cameraKey, inUseKeys) {
                        onChange(config.copy(cameraKey = it))
                    }
                }
                ViewportSourceType.SOLID, ViewportSourceType.GRADIENT ->
                    BackgroundEditor(config, onChange)
                ViewportSourceType.IMAGE -> {
                    OutlinedButton(onClick = onPickImage) {
                        Icon(Icons.Default.AddPhotoAlternate, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (config.imageData == null) "Choose image"
                            else "Replace image"
                        )
                    }
                    if (config.imageData == null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "No image chosen yet — the section stays blank until " +
                                "you pick one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
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
            "No cameras detected on this device.",
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
            "Android limits the number of camera streams, so with two cameras " +
                "in use the rest are disabled.",
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
    onDismiss: () -> Unit
) {
    StudioSidePanel(onDismiss = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 4.dp)
                .padding(bottom = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetHeader("Divider lines")
            Text("Line colour", fontWeight = FontWeight.SemiBold)
            ColorSwatchRow(color) { onChange(it, thickness) }
            LabeledSlider(
                "Thickness", "${thickness.toInt()} dp", thickness, 1f, 16f
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
            label = { Text("Solid") }
        )
        FilterChip(
            selected = !isSolid && !config.gradientRadial,
            onClick = { onChange(toGradient(config, radial = false)) },
            label = { Text("Linear") }
        )
        FilterChip(
            selected = !isSolid && config.gradientRadial,
            onClick = { onChange(toGradient(config, radial = true)) },
            label = { Text("Radial") }
        )
    }
    Spacer(Modifier.height(12.dp))
    if (isSolid) {
        Text("Colour", fontWeight = FontWeight.SemiBold)
        ColorSwatchRow(config.solidColor) { onChange(config.copy(solidColor = it)) }
    } else {
        GradientEditor(config, onChange)
    }
}

@Composable
private fun GradientEditor(config: ViewportConfig, onChange: (ViewportConfig) -> Unit) {
    if (!config.gradientRadial) {
        LabeledSlider(
            "Direction", "${config.gradientAngle.toInt()}°",
            config.gradientAngle, 0f, 360f
        ) { onChange(config.copy(gradientAngle = it)) }
    }
    Spacer(Modifier.height(10.dp))
    GradientPreview(config)
    Spacer(Modifier.height(10.dp))
    Text("Colour stops", fontWeight = FontWeight.SemiBold)
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
            onChange(
                config.copy(
                    gradientColors = config.gradientColors + 0xFFFFFFFFL,
                    gradientStops = config.gradientStops + 1f
                )
            )
        },
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Add stop")
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
                ColorSwatchRow(color) { onColor(it) }
            }
            if (canRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove stop",
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
    onChange: (OverlayElement) -> Unit,
    onReplaceImage: () -> Unit,
    onDismiss: () -> Unit
) {
    StudioSidePanel(onDismiss = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 4.dp)
                .padding(bottom = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetHeader(element.type.label)

            if (element.type == OverlayElementType.TEXT) {
                OutlinedTextField(
                    value = element.text,
                    onValueChange = { onChange(element.copy(text = it)) },
                    label = { Text("Text") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Variables: {speed} {battery} {temp} {voltage} {current} " +
                        "{power} {pwm} {trip} {odometer} {pitch} {roll} {wheel}. " +
                        "Press enter for a new line.",
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
                Text("Alignment", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "START" to "Left", "CENTER" to "Centre", "END" to "Right"
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

            val isGauge = element.type == OverlayElementType.DATA_DIAL ||
                element.type == OverlayElementType.DATA_BAR
            if (element.type == OverlayElementType.DATA_VALUE ||
                element.type == OverlayElementType.DATA_GRAPH || isGauge
            ) {
                Text("Metric", fontWeight = FontWeight.SemiBold)
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

            if (isGauge) {
                val metricMax = StudioMetric.fromKey(element.metric).defaultMax
                LabeledSlider(
                    "Maximum", element.gaugeMax.toInt().toString(),
                    element.gaugeMax,
                    metricMax * 0.2f, metricMax * 2.5f
                ) { onChange(element.copy(gaugeMax = it)) }
            }

            if (element.type == OverlayElementType.DATA_VALUE ||
                element.type == OverlayElementType.DATA_BAR
            ) {
                ToggleRow("Show label", element.showLabel) {
                    onChange(element.copy(showLabel = it))
                }
            }

            if (element.type == OverlayElementType.APP_BADGE) {
                ToggleRow("Stacked (icon on top)", element.badgeStacked) {
                    onChange(element.copy(badgeStacked = it))
                }
                ToggleRow("Show app name", element.showLabel) {
                    onChange(element.copy(showLabel = it))
                }
                ToggleRow("Show version number", element.badgeShowVersion) {
                    onChange(element.copy(badgeShowVersion = it))
                }
            }

            if (element.type == OverlayElementType.DATA_GRAPH) {
                LabeledSlider(
                    "Time window", "${element.graphWindowSec}s",
                    element.graphWindowSec.toFloat(), 10f, 300f
                ) { onChange(element.copy(graphWindowSec = it.toInt())) }
            }

            if (element.type == OverlayElementType.FLOATING_CAMERA) {
                Text("Camera", fontWeight = FontWeight.SemiBold)
                CameraPicker(cameras, element.cameraKey, inUseKeys) {
                    onChange(element.copy(cameraKey = it))
                }
                Spacer(Modifier.height(8.dp))
            }

            if (element.type == OverlayElementType.IMAGE) {
                OutlinedButton(onClick = onReplaceImage) {
                    Icon(Icons.Default.AddPhotoAlternate, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Replace image")
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("Make a colour transparent", element.chromaKeyEnabled) {
                    onChange(element.copy(chromaKeyEnabled = it))
                }
                if (element.chromaKeyEnabled) {
                    Text("Mask colour", fontWeight = FontWeight.SemiBold)
                    ColorSwatchRow(element.chromaKeyColor, ChromaPalette) {
                        onChange(element.copy(chromaKeyColor = it))
                    }
                    LabeledSlider(
                        "Threshold",
                        "${(element.chromaKeyTolerance * 100).toInt()}%",
                        element.chromaKeyTolerance, 0.02f, 0.6f
                    ) { onChange(element.copy(chromaKeyTolerance = it)) }
                }
            }

            // Colours. A transparent "Background" swatch makes the element's
            // backdrop invisible.
            if (element.type != OverlayElementType.FLOATING_CAMERA &&
                element.type != OverlayElementType.IMAGE
            ) {
                Text("Text colour", fontWeight = FontWeight.SemiBold)
                ColorSwatchRow(element.foreground, allowTransparent = false) {
                    onChange(element.copy(foreground = it))
                }
                Text("Background", fontWeight = FontWeight.SemiBold)
                ColorSwatchRow(element.background) {
                    onChange(element.copy(background = it))
                }
            }

            // Opacity + rotation apply to every element.
            LabeledSlider(
                "Opacity", "${(element.opacity * 100).toInt()}%",
                element.opacity, 0.1f, 1f, steps = 17
            ) { onChange(element.copy(opacity = it)) }
            LabeledSlider(
                "Rotation", "${element.rotationDeg.toInt()}°",
                element.rotationDeg, -180f, 180f, steps = 71
            ) { onChange(element.copy(rotationDeg = it)) }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { onChange(element.copy(x = 0.12f, y = 0.14f)) }) {
                Text("Reset position")
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
                label = { Text(metric.label) }
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
                contentDescription = "Invisible",
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
            contentDescription = "Custom colour",
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
        title = { Text("Custom colour") },
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
                        "Opacity", "${(alpha * 100).toInt()}%", alpha, 0f, 1f, 19
                    ) { alpha = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(color.toArgbLong()) }) { Text("Select") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(valueLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = { onChange(it.coerceIn(min, max)) },
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

private val OverlayElement.summary: String
    get() = when (type) {
        OverlayElementType.DATA_VALUE, OverlayElementType.DATA_GRAPH ->
            "${type.label} · ${StudioMetric.fromKey(metric).label}"
        else -> type.label
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageElementsSheet(
    elements: List<OverlayElement>,
    onMove: (Int, Int) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    StudioSidePanel(onDismiss = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 4.dp)
                .padding(bottom = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetHeader("Manage elements")
            if (elements.isEmpty()) {
                Text(
                    "No elements yet — use Add element to place one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                Text(
                    "Drag to reorder, tap a row to select it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ReorderableColumn(
                    list = elements,
                    onSettle = { from, to -> onMove(from, to) },
                    modifier = Modifier.fillMaxWidth()
                ) { _, element, _ ->
                    key(element.id) {
                        ManageElementRow(
                            element = element,
                            dragHandle = Modifier.draggableHandle(),
                            onSelect = { onSelect(element.id) },
                            onDelete = { onDelete(element.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManageElementRow(
    element: OverlayElement,
    dragHandle: Modifier,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
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
            contentDescription = "Drag to reorder",
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
            Text(element.summary, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                "x ${(element.x * 100).roundToInt()}%   " +
                    "y ${(element.y * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Default.Delete,
            contentDescription = "Delete element",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onDelete)
                .padding(6.dp)
        )
    }
}
