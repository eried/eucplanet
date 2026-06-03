package com.eried.eucplanet.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable

/**
 * Central, themed Material color objects built from the app's semantic
 * [AppThemeColors] tokens. Call sites pass these into `colors = ...` so editing a
 * token in the theme editor re-skins the matching control everywhere at once,
 * instead of every widget hardcoding `MaterialTheme.colorScheme.*`.
 *
 * Each helper defaults its tokens (via fillDerived) to the same value the control
 * rode on before wiring, so adopting one of these is a no-op visually until the
 * matching token is edited.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun themedFieldColors(): TextFieldColors {
    val c = MaterialTheme.appColors
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = c.fieldBackground,
        unfocusedContainerColor = c.fieldBackground,
        focusedTextColor = c.fieldText,
        unfocusedTextColor = c.fieldText,
        focusedLabelColor = c.fieldLabel,
        unfocusedLabelColor = c.fieldLabel,
        focusedBorderColor = c.fieldBorder,
        unfocusedBorderColor = c.fieldBorder,
        focusedPlaceholderColor = c.hint,
        unfocusedPlaceholderColor = c.hint,
    )
}

@Composable
fun themedSegmentedColors(): SegmentedButtonColors {
    val c = MaterialTheme.appColors
    return SegmentedButtonDefaults.colors(
        activeContainerColor = c.segmentSelectedBg,
        activeContentColor = c.segmentSelectedText,
        inactiveContentColor = c.segmentText,
    )
}

@Composable
fun themedSwitchColors(): SwitchColors {
    val c = MaterialTheme.appColors
    return SwitchDefaults.colors(
        checkedTrackColor = c.switchOn,
        uncheckedTrackColor = c.switchOff,
    )
}

@Composable
fun themedSliderColors(): SliderColors {
    val c = MaterialTheme.appColors
    return SliderDefaults.colors(
        activeTrackColor = c.sliderActive,
        thumbColor = c.sliderActive,
        inactiveTrackColor = c.sliderTrack,
    )
}

@Composable
fun themedTonalButtonColors(): ButtonColors {
    val c = MaterialTheme.appColors
    return ButtonDefaults.filledTonalButtonColors(
        containerColor = c.tonalButtonFill,
        contentColor = c.tonalButtonText,
    )
}

@Composable
fun themedTextButtonColors(): ButtonColors {
    val c = MaterialTheme.appColors
    return ButtonDefaults.textButtonColors(
        contentColor = c.textButton,
    )
}

@Composable
fun themedFilterChipColors(): SelectableChipColors {
    val c = MaterialTheme.appColors
    return FilterChipDefaults.filterChipColors(
        selectedContainerColor = c.chipSelected,
        containerColor = c.chipBackground,
        // Without an explicit selected label/icon color, Material defaults it to a
        // token that can match chipSelected (=primary) → invisible selected text.
        selectedLabelColor = c.onPrimary,
        selectedLeadingIconColor = c.onPrimary,
    )
}
