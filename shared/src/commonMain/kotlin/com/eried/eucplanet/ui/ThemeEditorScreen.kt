package com.eried.eucplanet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.ThemeTokens
import com.eried.eucplanet.ui.theme.appColors
import kotlin.math.roundToInt

/** Pack a [Color] into an ARGB Int for persistence (kotlinx.serialization-friendly). */
internal fun Color.toArgbInt(): Int {
    val a = (alpha * 255f).roundToInt() and 0xFF
    val r = (red * 255f).roundToInt() and 0xFF
    val g = (green * 255f).roundToInt() and 0xFF
    val b = (blue * 255f).roundToInt() and 0xFF
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/** Inverse of [toArgbInt]. */
internal fun Int.argbToColor(): Color = Color(
    red = ((this shr 16) and 0xFF) / 255f,
    green = ((this shr 8) and 0xFF) / 255f,
    blue = (this and 0xFF) / 255f,
    alpha = ((this shr 24) and 0xFF) / 255f,
)

/**
 * Shared theme editor — a port of the Android theme-customization widget, driven
 * by the shared [ThemeTokens.specs] registry so it lists every editable color
 * token grouped by section. Tapping a token reveals R/G/B sliders; edits are
 * stored as per-token ARGB overrides in [AppSettings.customThemeColors] (applied
 * on top of the selected built-in when "Use custom theme" is on), so the whole
 * app — including this screen — recolors live as you drag.
 */
@Composable
internal fun ThemeEditorScreen(
    settings: AppSettings,
    onUpdate: (((AppSettings) -> AppSettings)) -> Unit,
    onBack: () -> Unit,
) {
    val c = MaterialTheme.appColors
    var editingKey by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        ScreenTopBar(c, "Theme editor", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {

            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use custom theme", color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Overrides applied on top of the selected built-in.", color = c.textSecondary, fontSize = 11.sp)
                }
                Switch(
                    checked = settings.customThemeEnabled,
                    onCheckedChange = { onUpdate { s -> s.copy(customThemeEnabled = it) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = c.onPrimary,
                        checkedTrackColor = c.switchOn,
                        uncheckedTrackColor = c.switchOff,
                        uncheckedBorderColor = c.outline,
                        checkedBorderColor = c.switchOn,
                    ),
                )
            }

            if (settings.customThemeColors.isNotEmpty()) {
                Text(
                    "Reset all to built-in",
                    color = c.statusDanger, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { onUpdate { s -> s.copy(customThemeColors = emptyMap()) }; editingKey = null }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                )
            }

            Spacer(Modifier.height(4.dp))

            ThemeTokens.specs.groupBy { it.group }.forEach { (group, specs) ->
                Text(
                    group.uppercase(), color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                specs.forEach { spec ->
                    val current = spec.get(c) // live effective color (override or built-in)
                    val overridden = settings.customThemeColors.containsKey(spec.key)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surfaceVariant).padding(2.dp)) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { editingKey = if (editingKey == spec.key) null else spec.key }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(current)
                                    .border(1.dp, c.outline, RoundedCornerShape(6.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(spec.label, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (overridden) Text("edited", color = c.primary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }
                        if (editingKey == spec.key) {
                            ColorSliders(current, c) { newColor ->
                                onUpdate { s ->
                                    s.copy(
                                        customThemeEnabled = true,
                                        customThemeColors = s.customThemeColors + (spec.key to newColor.toArgbInt()),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ColorSliders(color: Color, c: AppThemeColors, onChange: (Color) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(6.dp)).background(color).border(1.dp, c.outline, RoundedCornerShape(6.dp)))
        Spacer(Modifier.height(6.dp))
        ChannelSlider("R", color.red, c.statusDanger, c) { onChange(Color(it, color.green, color.blue, color.alpha)) }
        ChannelSlider("G", color.green, c.statusGood, c) { onChange(Color(color.red, it, color.blue, color.alpha)) }
        ChannelSlider("B", color.blue, c.metricVoltage, c) { onChange(Color(color.red, color.green, it, color.alpha)) }
    }
}

@Composable
private fun ChannelSlider(label: String, value: Float, accent: Color, c: AppThemeColors, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
        Slider(
            value = value, onValueChange = onChange, valueRange = 0f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = c.sliderTrack),
        )
        Text("${(value * 255f).roundToInt()}", color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.width(30.dp))
    }
}
