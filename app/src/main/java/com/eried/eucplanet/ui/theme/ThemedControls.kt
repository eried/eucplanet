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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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

/**
 * Floating field label that notches the control's top border the way Material's
 * OutlinedTextField does: the section colour (appBackground) shows above the
 * border line, the field colour (fieldBackground) below it. A solid background
 * would leave a visible patch in the dark theme, where the field and section
 * colours differ; the split keeps the label seamless in every theme.
 *
 * Place inside a Box whose other child is the control, and give that control at
 * least 8dp of top padding so the label has room (it straddles the top border).
 */
@Composable
fun BoxScope.FieldNotchLabel(
    text: String,
    color: Color = Color.Unspecified,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MaterialTheme.appColors
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            // Centre the label ON the control's top border like a native
            // OutlinedTextField label, so the border line runs through the middle of
            // the text (not its top, which reads as "sunk"). Measured against the
            // Language/Voice combos: their label centre sits ~2px above the fill top,
            // i.e. right on the frame line.
            .offset(x = 12.dp, y = (-8).dp)
            // Fill with the SECTION colour the field sits on (surfaceVariant), like
            // a native OutlinedTextField whose floating label shows the surface
            // behind it through the outline notch - NOT the field's own fill. This
            // makes the label blend into the section above the border (only the
            // notch cuts into the field), instead of a dark patch poking up. On
            // built-in themes surfaceVariant ~= the field colour so it looks the
            // same; an OLED/custom theme (black field on a #121212 section) reveals
            // that the native label is the section colour, not the field's black.
            .background(c.surfaceVariant)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            // Match the native combo label typography (Material's small label).
            style = MaterialTheme.typography.bodySmall,
            color = color.takeOrElse { c.fieldLabel },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke(this)
    }
}
