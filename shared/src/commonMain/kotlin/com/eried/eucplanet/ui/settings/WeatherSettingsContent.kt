package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.theme.FieldNotchLabel
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors
import com.eried.eucplanet.ui.theme.themedSwitchColors
import org.jetbrains.compose.resources.stringResource

/**
 * The Weather block of the Navigation and weather section: the dashboard's
 * weather icon, where the forecast comes from, how far ahead it looks and the
 * rider's likes. Shared because the iPhone has the weather but not the
 * navigator yet, so it shows this block on its own.
 *
 * [scrollToWeather] is set when the rider came from the weather icon's
 * settings shortcut; the heading then reports where it is through
 * [onWeatherTop] so the screen scrolls there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherSettingsContent(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    scrollToWeather: Boolean = false,
    onWeatherTop: (Float) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
            // --- Weather / ridability ---------------------------------------
            // The dashboard weather icon and its forecast flyout. Ships off;
            // enabling adds the icon above the map button.
            // The house section heading, same as "Charging monitor" and friends
            // elsewhere in Settings. Weather is its own topic that happens to
            // live under Navigation, not a footnote to routing.
            Box(
                modifier = if (scrollToWeather) {
                    Modifier.onGloballyPositioned { onWeatherTop(it.positionInWindow().y) }
                } else Modifier,
            ) {
                SectionHeader(stringResource(Res.string.weather_section))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(Res.string.weather_enable),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = settings.weather.enabled,
                    onCheckedChange = { viewModel.updateWeatherEnabled(it) },
                    colors = themedSwitchColors(),
                )
            }
            HintText(stringResource(Res.string.weather_enable_desc), small = true)
            if (settings.weather.enabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(Res.string.weather_open_expanded),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = settings.weather.openExpanded,
                        onCheckedChange = { viewModel.updateWeatherOpenExpanded(it) },
                        colors = themedSwitchColors(),
                    )
                }
                // The source line sits ABOVE its selector, so the block reads as
                // one thought: here is where the numbers come from, here is the
                // picker, here is how far ahead to look.
                HintText(stringResource(Res.string.weather_source_credit), small = true)
                val sources = com.eried.eucplanet.weather.WeatherSource.entries
                var sourceExpanded by remember { mutableStateOf(false) }
                androidx.compose.material3.ExposedDropdownMenuBox(
                    expanded = sourceExpanded,
                    onExpandedChange = { sourceExpanded = it },
                ) {
                    OutlinedTextField(
                        value = com.eried.eucplanet.weather.WeatherSource.byId(settings.weather.source).label,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(Res.string.weather_source_label)) },
                        trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
                        colors = themedFieldColors(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    )
                    ExposedDropdownMenu(
                        expanded = sourceExpanded,
                        onDismissRequest = { sourceExpanded = false },
                        containerColor = MaterialTheme.appColors.menuBackground
                    ) {
                        sources.forEach { src ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(src.label) },
                                onClick = {
                                    viewModel.updateWeatherSource(src.id)
                                    sourceExpanded = false
                                }
                            )
                        }
                    }
                }
                // Hours, not four presets: "the rest of my afternoon" fell between
                // 6 and 24 every time. The dashboard menu still offers the old
                // presets as a temporary view. Half width, like the paired
                // numeric fields further up this page, rather than a lone box
                // spanning the screen for a two-digit number.
                Row(modifier = Modifier.fillMaxWidth()) {
                    NumberFieldWithDefault(
                        value = settings.weather.windowHours,
                        default = SETTINGS_DEFAULTS.weather.windowHours,
                        onValueChange = { viewModel.updateWeatherWindow(it) },
                        range = 2..168,
                        suffix = stringResource(Res.string.weather_window_hours_suffix),
                        label = stringResource(Res.string.weather_window_label),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.weight(1f))
                }

                // Riding preferences: a full-width triple segmented selector per
                // condition. The comfort thresholds behind them live in Advanced
                // settings under Weather score. Rain, snow and wind ship disliked.
                // In-section subtitle, same style as the nav section's Charger
                // community block: this is part of WEATHER, not a sibling section.
                Text(
                    stringResource(Res.string.weather_pref_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.appColors.primary,
                )
                HintText(stringResource(Res.string.weather_pref_desc), small = true)
                val prefOptions = listOf(
                    "DISLIKE" to stringResource(Res.string.weather_pref_dislike),
                    "NEUTRAL" to stringResource(Res.string.weather_pref_neutral),
                    "LIKE" to stringResource(Res.string.weather_pref_like),
                )
                listOf(
                    Triple("hot", Res.string.weather_cond_hot, settings.weather.prefHot),
                    Triple("cold", Res.string.weather_cond_cold, settings.weather.prefCold),
                    Triple("rain", Res.string.weather_cond_rain, settings.weather.prefRain),
                    Triple("snow", Res.string.weather_cond_snow, settings.weather.prefSnow),
                    Triple("wind", Res.string.weather_cond_wind, settings.weather.prefWind),
                    Triple("golden", Res.string.weather_cond_golden, settings.weather.prefGolden),
                    Triple("night", Res.string.weather_cond_night, settings.weather.prefNight),
                ).forEach { (key, labelRes, current) ->
                    // The house triple selector: full-width segmented row with the
                    // condition name notched into its frame, like the alarm
                    // editor's vibrate-target selector.
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 9.dp)) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            prefOptions.forEachIndexed { index, (id, label) ->
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    selected = current == id,
                                    onClick = { viewModel.updateWeatherPref(key, id) },
                                    shape = SegmentedButtonDefaults.itemShape(index, prefOptions.size, baseShape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                                    colors = com.eried.eucplanet.ui.theme.themedSegmentedColors(),
                                ) { Text(label) }
                            }
                        }
                        FieldNotchLabel(stringResource(labelRes))
                    }
                }

            }
    }
}
