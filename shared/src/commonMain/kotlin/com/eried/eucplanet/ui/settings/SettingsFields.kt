package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.theme.appColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


/**
 * A numeric field that carries its own default underneath it.
 *
 * Drops into an existing Row in place of a bare [NumberUpDown] without moving
 * anything: same modifier, same width, the chip simply appears below. That
 * matters for the rows that hold two fields side by side, like the empty and
 * full cell voltages, where turning each into a full settings row would break
 * the pairing that makes them readable.
 *
 * [default] is meant to come from `SETTINGS_DEFAULTS` rather than being typed
 * in, so the chip cannot drift from the shipped value.
 */
@Composable
fun NumberFieldWithDefault(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    default: Int,
    modifier: Modifier = Modifier,
    step: Int = 1,
    suffix: String = "",
    label: String? = null,
    enabled: Boolean = true,
    format: (Int) -> String = { it.toString() },
    parse: (String) -> Int? = { it.toIntOrNull() },
    allowSign: Boolean = false,
    numberAlign: TextAlign = TextAlign.End,
) {
    Column(modifier = modifier) {
        NumberUpDown(
            value = value,
            onValueChange = onValueChange,
            range = range,
            modifier = Modifier.fillMaxWidth(),
            step = step,
            suffix = suffix,
            label = label,
            enabled = enabled,
            format = format,
            parse = parse,
            allowSign = allowSign,
            numberAlign = numberAlign,
        )
        val unit = if (suffix.isEmpty()) "" else " $suffix"
        RestoreChip(
            text = "${format(default)}$unit",
            enabled = enabled && value != default,
        ) { onValueChange(default) }
    }
}

/** Always-visible "undo -> <default>" affordance. Active (accent, clickable) when
 *  the value is off its default; greyed and inert once it matches. */
@Composable
fun RestoreChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.appColors.primary else MaterialTheme.appColors.textDisabled
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Restore,
            contentDescription = if (enabled) stringResource(Res.string.adv_restore_default) else null,
            modifier = Modifier.size(15.dp),
            tint = color,
        )
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.sp, color = color)
    }
}
