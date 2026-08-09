package com.eried.eucplanet.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.studio.formatReplayClock
import com.eried.eucplanet.ui.studio.parseReplayClock

/** Digits-only -> MM:SS, timer style (the last two digits are the seconds, the
 *  rest the minutes). Lets the fields use a pure number keyboard. */
private fun digitsToClock(raw: String): String {
    val digits = raw.filter { it.isDigit() }.takeLast(5)
    if (digits.isEmpty()) return ""
    val secs = digits.takeLast(2).padStart(2, '0')
    val mins = digits.dropLast(2).ifEmpty { "0" }.toInt()
    return "$mins:$secs"
}

/**
 * Type an exact trim range instead of dragging the fiddly handles. Start / End /
 * Duration are number-keyboard fields (digits auto-format to MM:SS) and stay
 * linked: editing Start or End updates Duration; editing Duration moves End when
 * a Start is set (the common case), moves Start when only an End is set, or fills
 * 0:00 -> Duration when neither is. Reset restores the full trip. Apply is enabled
 * only when both ends parse, sit in [0, duration] and start < end.
 *
 * Shared by the Overlay Studio replay timeline and Trip Details. Trip Details
 * additionally needs a floor on how many samples the selection holds, since a
 * one-sample window has no line to draw; Studio passes neither parameter and
 * behaves exactly as it did before.
 */
@Composable
fun TrimTimeDialog(
    startMs: Long,
    endMs: Long,
    durationMs: Long,
    /** Minimum samples the selection must contain for Apply to enable. 0 disables the check. */
    minPoints: Int = 0,
    /** How many samples fall in a candidate range. Only called when [minPoints] > 0. */
    pointsInRange: (LongRange) -> Int = { minPoints },
    onConfirm: (Long, Long) -> Unit,
    onDismiss: () -> Unit
) {
    fun fmt(ms: Long) = formatReplayClock(ms.coerceAtLeast(0))
    fun tfv(t: String) = TextFieldValue(t, selection = TextRange(t.length))
    val start = remember { mutableStateOf(tfv(fmt(startMs))) }
    val end = remember { mutableStateOf(tfv(fmt(endMs))) }
    val dur = remember { mutableStateOf(tfv(fmt(endMs - startMs))) }

    val startParsed = parseReplayClock(start.value.text)
    val endParsed = parseReplayClock(end.value.text)
    val startOk = startParsed != null && startParsed in 0..durationMs
    val endOk = endParsed != null && endParsed in 0..durationMs
    val ordered = startOk && endOk && startParsed!! < endParsed!!
    // A selection below the caller's floor has nothing to draw, so Apply stays off.
    val enoughPoints = !ordered || minPoints <= 0 ||
        pointsInRange(startParsed!!..endParsed!!) >= minPoints
    val valid = ordered && enoughPoints

    // Editing Start or End re-derives Duration.
    val syncDuration = {
        val s = parseReplayClock(start.value.text)
        val e = parseReplayClock(end.value.text)
        if (s != null && e != null) dur.value = tfv(fmt(e - s))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        // A stray tap outside must not drop an in-progress edit.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.studio_replay_trim_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TrimTimeField(
                    label = stringResource(R.string.studio_replay_trim_start),
                    state = start,
                    isError = !startOk,
                    afterChange = syncDuration
                )
                TrimTimeField(
                    label = stringResource(R.string.studio_replay_trim_end),
                    state = end,
                    isError = !endOk,
                    afterChange = syncDuration
                )
                TrimTimeField(
                    label = stringResource(R.string.studio_replay_trim_duration),
                    state = dur,
                    supporting = "MM:SS  ·  max ${fmt(durationMs)}",
                    afterChange = {
                        val d = parseReplayClock(dur.value.text)
                        if (d != null) {
                            val s = parseReplayClock(start.value.text)
                            val e = parseReplayClock(end.value.text)
                            when {
                                s != null -> end.value = tfv(fmt((s + d).coerceAtMost(durationMs)))
                                e != null -> start.value = tfv(fmt((e - d).coerceAtLeast(0)))
                                else -> { start.value = tfv("0:00"); end.value = tfv(fmt(d.coerceAtMost(durationMs))) }
                            }
                        }
                    }
                )
            }
        },
        confirmButton = {
            // Reset on the left (clears the trim to the full trip AND commits),
            // Cancel + Apply on the right.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onConfirm(0L, durationMs) }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.studio_replay_trim_reset))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.action_cancel)) }
                TextButton(
                    onClick = { if (valid) onConfirm(startParsed!!, endParsed!!) },
                    enabled = valid,
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.action_apply)) }
            }
        }
    )
}

/** A number-keyboard MM:SS field for the trim dialog: digits auto-format to
 *  MM:SS (timer style), and focusing it selects all so the next keystroke
 *  replaces the value -- clean editing of a pre-filled time without fighting
 *  backspace. [afterChange] re-syncs the linked fields. */
@Composable
private fun TrimTimeField(
    label: String,
    state: MutableState<TextFieldValue>,
    isError: Boolean = false,
    supporting: String? = null,
    afterChange: () -> Unit
) {
    OutlinedTextField(
        value = state.value,
        onValueChange = { v ->
            val f = digitsToClock(v.text)
            state.value = TextFieldValue(f, selection = TextRange(f.length))
            afterChange()
        },
        modifier = Modifier.onFocusChanged {
            if (it.isFocused) {
                state.value = state.value.copy(selection = TextRange(0, state.value.text.length))
            }
        },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        shape = RoundedCornerShape(12.dp),
        supportingText = supporting?.let { s -> { Text(s) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}
