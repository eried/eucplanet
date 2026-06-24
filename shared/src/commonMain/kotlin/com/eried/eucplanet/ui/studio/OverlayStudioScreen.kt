package com.eried.eucplanet.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.hudlink.OverlayElement
import com.eried.eucplanet.hudlink.OverlayElementType
import com.eried.eucplanet.hudlink.OverlayPreset
import com.eried.eucplanet.hudlink.ViewportConfig
import com.eried.eucplanet.hudlink.ViewportSourceType
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.appColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Overlay Studio — design a custom HUD layout. The rider drops widgets onto a
 * HUD-shaped canvas, positions / resizes / configures them, and Saves; the
 * preset serializes to `customOverlayJson` (stored in settings) which the phone
 * streams as the HUD's "Custom" screen. The phone is the editor; the HUD device
 * renders the result, so MAP / IMAGE / camera show as placeholders here.
 *
 * v1 keeps the viewport SINGLE + solid dark; pane/camera layouts stay an Android
 * concern (the rider can refine there). Faithful to the same wire model.
 */
@Composable
internal fun OverlayStudioScreen(
    initial: OverlayPreset,
    live: WheelData,
    wheelName: String,
    unitSpeed: String,
    unitDistance: String,
    unitTemp: String,
    onSave: (OverlayPreset) -> Unit,
    onBack: () -> Unit,
) {
    val c = MaterialTheme.appColors
    val accent = c.gaugeFill
    var els by remember { mutableStateOf(initial.elements) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(initial.elements.isEmpty()) }
    var nextId by remember { mutableStateOf(initial.elements.size + 1) }
    val selected = els.firstOrNull { it.id == selectedId }

    fun mutate(id: String, f: (OverlayElement) -> OverlayElement) { els = els.map { if (it.id == id) f(it) else it } }
    fun save() = onSave(
        initial.copy(
            elements = els,
            layout = com.eried.eucplanet.hudlink.ViewportLayout.SINGLE,
            viewports = listOf(ViewportConfig(source = ViewportSourceType.SOLID, solidColor = 0xFF0D0D0DL)),
        ),
    )

    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        // Top bar: back · title · Save
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = c.primary, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onBack() }.padding(end = 10.dp))
            Text("Overlay Studio", color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            // Record the live camera with this overlay burned in → MP4 (Photos).
            val recording by StudioRecorder.recording.collectAsState()
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (recording) c.statusDanger else c.surfaceVariant)
                    .clickable { if (recording) StudioRecorder.stop() else StudioRecorder.start() }.padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text(if (recording) "■ Stop" else "● Rec", color = if (recording) c.onPrimary else c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.primary).clickable { save() }.padding(horizontal = 16.dp, vertical = 7.dp)) {
                Text("Save", color = c.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // HUD-shaped canvas (16:9). Tap empty space to deselect.
        BoxWithConstraints(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF0D0D0D))
                .pointerInput(Unit) { detectTapGestures { selectedId = null; showAdd = false } },
        ) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()
            val density = LocalDensity.current
            els.forEach { el ->
                key(el.id) {
                    val isSel = el.id == selectedId
                    Box(
                        Modifier
                            .offset { IntOffset((el.x * wPx).roundToInt(), (el.y * hPx).roundToInt()) }
                            .width(with(density) { (el.width * wPx).toDp() })
                            .then(if (el.height > 0f) Modifier.height(with(density) { (el.height * hPx).toDp() }) else Modifier)
                            .then(if (isSel) Modifier.border(1.5.dp, accent, RoundedCornerShape(6.dp)) else Modifier)
                            .pointerInput(el.id, wPx, hPx) {
                                detectDragGestures(onDragStart = { selectedId = el.id; showAdd = false }) { ch, drag ->
                                    ch.consume()
                                    mutate(el.id) {
                                        it.copy(
                                            x = (it.x + drag.x / wPx).coerceIn(-0.05f, 0.97f),
                                            y = (it.y + drag.y / hPx).coerceIn(-0.05f, 0.95f),
                                        )
                                    }
                                }
                            }
                            .pointerInput(el.id) { detectTapGestures { selectedId = el.id; showAdd = false } },
                    ) {
                        StudioElementView(el, live, wheelName, accent, unitSpeed, unitDistance, unitTemp, Modifier.alpha(el.opacity).rotate(el.rotationDeg).fillMaxWidth())
                        if (isSel) {
                            // Resize handle (bottom-right). Width always tracks the drag;
                            // height stays 0 ("natural aspect", like Android) while the drag
                            // is mostly horizontal, and only engages free-height when the
                            // rider deliberately drags vertically.
                            Box(
                                Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(accent)
                                    .pointerInput(el.id, wPx, hPx) {
                                        detectDragGestures { ch, drag ->
                                            ch.consume()
                                            mutate(el.id) {
                                                val keepNatural = it.height <= 0f && abs(drag.y) <= abs(drag.x)
                                                it.copy(
                                                    width = (it.width + drag.x / wPx).coerceIn(0.08f, 1f),
                                                    height = if (keepNatural) 0f
                                                    else ((if (it.height <= 0f) 0.18f else it.height) + drag.y / hPx).coerceIn(0.05f, 1f),
                                                )
                                            }
                                        }
                                    },
                            ) { Text("⤡", color = c.onPrimary, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center)) }
                            // Rotation handle (bottom-left), mirroring Android's rotate grip.
                            Box(
                                Modifier.align(Alignment.BottomStart).size(20.dp).clip(CircleShape).background(c.tertiary)
                                    .pointerInput(el.id) {
                                        detectDragGestures { ch, drag ->
                                            ch.consume()
                                            mutate(el.id) {
                                                var deg = it.rotationDeg + drag.x * 0.6f
                                                if (deg > 180f) deg -= 360f; if (deg < -180f) deg += 360f
                                                it.copy(rotationDeg = deg)
                                            }
                                        }
                                    },
                            ) { Text("↻", color = c.onPrimary, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center)) }
                        }
                    }
                }
            }
            if (els.isEmpty()) {
                Text("Tap “+ Add widget” to start", color = Color(0x88FFFFFF), fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
            }
        }

        // Action row.
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("+ Add widget", c.primary, c.onPrimary, Modifier.weight(1f)) { showAdd = !showAdd; selectedId = null }
            if (selected != null) {
                PillButton("Delete", c.surfaceVariant, c.statusDanger, Modifier.weight(1f)) {
                    els = els.filter { it.id != selected.id }; selectedId = null
                }
            }
        }

        // Bottom panel: add grid, or the selected element's config.
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            if (showAdd) {
                AddGrid(c) { type ->
                    val id = "el${nextId}"; nextId += 1
                    val m = if (type == OverlayElementType.DATA_DIAL || type == OverlayElementType.DATA_BAR) StudioMetric.SPEED else StudioMetric.SPEED
                    els = els + OverlayElement(id = id, type = type, x = 0.34f, y = 0.36f, width = 0.3f, gaugeMax = m.defaultMax)
                    selectedId = id; showAdd = false
                }
            } else if (selected != null) {
                ConfigPanel(c, accent, selected) { f -> mutate(selected.id, f) }
            } else {
                Text("Tap a widget to configure it, or add a new one.", color = c.textDisabled, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val ADDABLE = listOf(
    OverlayElementType.DATA_VALUE to "Value",
    OverlayElementType.DATA_DIAL to "Dial",
    OverlayElementType.DATA_BAR to "Bar",
    OverlayElementType.DATA_GRAPH to "Graph",
    OverlayElementType.TEXT to "Text",
    OverlayElementType.WHEEL_NAME to "Wheel name",
    OverlayElementType.APP_BADGE to "App badge",
    OverlayElementType.CLOCK to "Clock",
    OverlayElementType.G_FORCE to "G-Force",
    OverlayElementType.MAP to "Map",
    OverlayElementType.IMAGE to "Image",
    OverlayElementType.FLOATING_CAMERA to "Camera",
)

@Composable
private fun AddGrid(c: AppThemeColors, onPick: (OverlayElementType) -> Unit) {
    Text("ADD WIDGET", color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
    // Simple 3-per-row grid built from Rows (no FlowRow dependency).
    ADDABLE.chunked(3).forEach { rowItems ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowItems.forEach { (type, label) ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.surface).clickable { onPick(type) }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = c.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
            }
            repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ConfigPanel(c: AppThemeColors, accent: Color, el: OverlayElement, change: ((OverlayElement) -> OverlayElement) -> Unit) {
    Text(el.type.name.replace("_", " "), color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))

    val dataBound = el.type == OverlayElementType.DATA_VALUE || el.type == OverlayElementType.DATA_GRAPH ||
        el.type == OverlayElementType.DATA_DIAL || el.type == OverlayElementType.DATA_BAR
    if (dataBound) {
        Label(c, "Metric")
        ChipRow(c, StudioMetric.entries.map { it.label }, StudioMetric.byKey(el.metric).label) { i ->
            change { it.copy(metric = StudioMetric.entries[i].key, gaugeMax = StudioMetric.entries[i].defaultMax) }
        }
    }

    when (el.type) {
        OverlayElementType.DATA_VALUE -> ToggleRow(c, "Show label", el.showLabel) { v -> change { it.copy(showLabel = v) } }
        OverlayElementType.DATA_GRAPH -> {
            ToggleRow(c, "Show label", el.showLabel) { v -> change { it.copy(showLabel = v) } }
            SliderRow(c, "Window", "${el.graphWindowSec}s", el.graphWindowSec.toFloat(), 2f..30f) { v -> change { it.copy(graphWindowSec = v.roundToInt()) } }
        }
        OverlayElementType.DATA_DIAL -> {
            SliderRow(c, "Full scale", el.gaugeMax.roundToInt().toString(), el.gaugeMax, 10f..3000f) { v -> change { it.copy(gaugeMax = v) } }
            Label(c, "Style"); ChipRow(c, listOf("Full", "Semicircle"), if (el.dialStyle == "SEMICIRCLE") "Semicircle" else "Full") { i -> change { it.copy(dialStyle = if (i == 1) "SEMICIRCLE" else "FULL") } }
            ToggleRow(c, "Colour band (green/orange/red)", el.dialShowColorBand) { v -> change { it.copy(dialShowColorBand = v) } }
        }
        OverlayElementType.DATA_BAR -> {
            SliderRow(c, "Full scale", el.gaugeMax.roundToInt().toString(), el.gaugeMax, 10f..3000f) { v -> change { it.copy(gaugeMax = v) } }
            ToggleRow(c, "Show value", el.barShowValue) { v -> change { it.copy(barShowValue = v) } }
        }
        OverlayElementType.TEXT -> {
            Label(c, "Text  (use {speed} {battery} {temp} {pwm} {wheel})")
            OutlinedTextField(el.text, { v -> change { it.copy(text = v) } }, Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(6.dp))
            Label(c, "Align"); ChipRow(c, listOf("Left", "Center", "Right"), alignLabel(el.textAlign)) { i -> change { it.copy(textAlign = listOf("START", "CENTER", "END")[i]) } }
        }
        OverlayElementType.APP_BADGE -> {
            ToggleRow(c, "Stacked", el.badgeStacked) { v -> change { it.copy(badgeStacked = v) } }
            ToggleRow(c, "Show version", el.badgeShowVersion) { v -> change { it.copy(badgeShowVersion = v) } }
        }
        OverlayElementType.CLOCK -> {
            Label(c, "Style"); ChipRow(c, listOf("Digital", "Analog", "Text", "Stopwatch"), clockLabel(el.clockStyle)) { i -> change { it.copy(clockStyle = listOf("DIGITAL", "ANALOG", "TEXT", "STOPWATCH")[i]) } }
            ToggleRow(c, "24-hour", el.clock24Hour) { v -> change { it.copy(clock24Hour = v) } }
            ToggleRow(c, "Show date", el.clockShowDate) { v -> change { it.copy(clockShowDate = v) } }
        }
        OverlayElementType.G_FORCE -> SliderRow(c, "Range (g)", oneDpStr(el.gForceScale), el.gForceScale, 0.25f..3f) { v -> change { it.copy(gForceScale = v) } }
        OverlayElementType.MAP -> {
            Label(c, "Tiles"); ChipRow(c, listOf("Street", "Dark", "Satellite"), mapLabel(el.mapStyle)) { i -> change { it.copy(mapStyle = listOf("STREET", "DARK", "SATELLITE")[i]) } }
            Note(c, "The map is drawn on the HUD device (needs its GPS / tiles).")
        }
        OverlayElementType.IMAGE -> Note(c, "Pick the image in the Android app. It embeds in the preset.")
        OverlayElementType.FLOATING_CAMERA -> Note(c, "The HUD draws its own camera into this window.")
        else -> {}
    }

    Spacer(Modifier.height(4.dp))
    SliderRow(c, "Size", "${(el.width * 100).roundToInt()}%", el.width, 0.1f..1f) { v -> change { it.copy(width = v) } }
    // Rotation matches Android's Style-section rotation slider (-180..180°, applied
    // via graphicsLayer there, Modifier.rotate on the preview here).
    SliderRow(c, "Rotation", "${el.rotationDeg.roundToInt()}°", el.rotationDeg, -180f..180f) { v -> change { it.copy(rotationDeg = v) } }
    SliderRow(c, "Opacity", "${(el.opacity * 100).roundToInt()}%", el.opacity, 0.1f..1f) { v -> change { it.copy(opacity = v) } }
    Label(c, "Text / line colour"); SwatchRow(FG_PALETTE, el.foreground) { v -> change { it.copy(foreground = v) } }
    Label(c, "Background"); SwatchRow(BG_PALETTE, el.background) { v -> change { it.copy(background = v) } }
}

private val FG_PALETTE = listOf(0xFFFFFFFFL, 0xFFB0BEC5L, 0xFFE53935L, 0xFFFB8C00L, 0xFFFFEB3BL, 0xFF43A047L, 0xFF00BCD4L, 0xFF000000L)
private val BG_PALETTE = listOf(0x00000000L, 0x66000000L, 0x99000000L, 0xFF000000L, 0xCC1E1E2EL, 0xCCE53935L, 0xCC1565C0L, 0xCC2E7D32L)

@Composable
private fun SwatchRow(palette: List<Long>, current: Long, onPick: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        palette.forEach { argb ->
            val sel = argb == current
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(argb))
                    .then(if (sel) Modifier.border(2.dp, Color.White, CircleShape) else Modifier.border(1.dp, Color(0x33FFFFFF), CircleShape))
                    .clickable { onPick(argb) },
            ) { if ((argb ushr 24) == 0L) Text("∅", color = Color(0x88FFFFFF), fontSize = 12.sp, modifier = Modifier.align(Alignment.Center)) }
        }
    }
}

@Composable
private fun ChipRow(c: AppThemeColors, labels: List<String>, selected: String, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            val sel = label == selected
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) c.primary else c.surface).clickable { onPick(i) }.padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text(label, color = if (sel) c.onPrimary else c.textSecondary, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

@Composable
private fun PillButton(label: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(bg).clickable { onClick() }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Label(c: AppThemeColors, text: String) {
    Text(text, color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
private fun Note(c: AppThemeColors, text: String) {
    Text(text, color = c.textDisabled, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun ToggleRow(c: AppThemeColors, label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(c: AppThemeColors, label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Slider(value = current.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

private fun alignLabel(a: String) = when (a) { "CENTER" -> "Center"; "END" -> "Right"; else -> "Left" }
private fun clockLabel(s: String) = when (s) { "ANALOG" -> "Analog"; "TEXT" -> "Text"; "STOPWATCH" -> "Stopwatch"; else -> "Digital" }
private fun mapLabel(s: String) = when (s) { "DARK" -> "Dark"; "SATELLITE" -> "Satellite"; else -> "Street" }
private fun oneDpStr(v: Float): String { val r = (v * 10).roundToInt(); return "${r / 10}.${r % 10}" }
