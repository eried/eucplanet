package com.eried.eucplanet.ui.recording

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.ExtendPlan
import com.eried.eucplanet.data.repository.TripSplitDetector
import com.eried.eucplanet.ui.common.ToolRow
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors

/**
 * Trip tools, opened from the wrench in the trip list.
 *
 * Replaces the eye that used to sit there: the whole row already opens the trip,
 * so that icon was a second way to do the same thing and the slot was free.
 *
 * Everything here PRODUCES trips rather than altering the ride: a split leaves
 * the original alone, a join leaves its sources alone. The only tool that edits
 * in place is Change wheel, which corrects a label rather than the data.
 *
 * Nothing produced here can reach the eucstats leaderboard. See
 * [com.eried.eucplanet.data.repository.TripDerive].
 */
@Composable
fun TripToolsDialog(
    trip: TripRecord,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onChangeWheel: () -> Unit,
    onSplit: () -> Unit,
    onCombine: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        text = {
            Column {
                ToolRow(
                    Icons.Default.Edit,
                    stringResource(R.string.trip_tools_rename),
                    stringResource(R.string.trip_tools_rename_desc),
                ) { onDismiss(); onRename() }
                ToolRow(
                    // Not a vehicle glyph. This tool changes a LABEL, and the
                    // repo's terminology rule is that the device is a wheel,
                    // never a motorbike. A swap arrow says what it does.
                    Icons.Default.SwapHoriz,
                    stringResource(R.string.trip_tools_change_wheel),
                    stringResource(R.string.trip_tools_change_wheel_desc),
                ) { onDismiss(); onChangeWheel() }
                ToolRow(
                    Icons.Default.ContentCut,
                    stringResource(R.string.trip_tools_split),
                    stringResource(R.string.trip_tools_split_desc),
                ) { onDismiss(); onSplit() }
                ToolRow(
                    Icons.Default.CallMerge,
                    stringResource(R.string.trip_tools_extend),
                    stringResource(R.string.trip_tools_extend_desc),
                ) { onDismiss(); onCombine() }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Give a trip a name. Blank clears it, and the trip falls back to its date.
 * The name is capped at 60 chars to match the CSV reader (and eucviewer).
 */
@Composable
fun RenameTripDialog(
    currentName: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(R.string.trip_tools_rename)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.trip_tools_rename_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(60) },
                    singleLine = true,
                    colors = themedFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            // Reset sits alone on the far left, apart from Cancel/Save: it is
            // the one action that destroys something (the rider's name), and
            // distance is what keeps it from being hit while reaching for
            // Save. No other dialog has a third action yet; this is the
            // pattern when one does.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // One tap back to the date: clears the name AND accepts. Only
                // offered while there is a name to clear - on an unnamed trip
                // it would just be a second Cancel.
                if (!currentName.isNullOrBlank()) {
                    TextButton(onClick = { onConfirm("") }, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.action_reset))
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Row {
                    TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(onClick = { onConfirm(text.trim()) }, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
    )
}

/**
 * Pick which wheel a trip was ridden on.
 *
 * [knownWheels] comes from two places: the rider's saved wheel profiles, and the
 * wheel names any existing trip already carries. The second matters because a
 * wheel that only ever arrived as an imported CSV has no profile, and that is
 * exactly when a rider is most likely to be fixing a wrong label. A free-text
 * field still covers a wheel neither source has heard of.
 *
 * No leaderboard warning here on purpose: the wheel label is for the rider's
 * own organisation (and eucviewer's wheel selector). An edited trip is never
 * resubmitted, so the change cannot touch the leaderboard either way.
 */
@Composable
fun ChangeWheelDialog(
    knownWheels: List<com.eried.eucplanet.data.repository.WheelChoice>,
    currentWheel: com.eried.eucplanet.data.repository.WheelChoice?,
    onConfirm: (com.eried.eucplanet.data.repository.WheelChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    // Every wheel is a whole identity, labelled the way eucviewer labels it,
    // so picking one here files the trip where eucviewer files it. The
    // trip's own wheel leads the list and starts selected, even when it has
    // no saved profile; a trip with no wheel starts with nothing selected,
    // because starting on someone else's wheel invites a change the rider
    // never meant.
    val options = remember(currentWheel, knownWheels) {
        val all = listOfNotNull(currentWheel) + knownWheels
        all.distinctBy { it.key }
    }
    var picked by remember(options) { mutableStateOf(currentWheel?.key.orEmpty()) }
    var custom by remember { mutableStateOf("") }
    var usingCustom by remember(options) { mutableStateOf(options.isEmpty()) }
    // A known pick carries the whole identity - name, MAC, brand, model,
    // serial - exactly as eucviewer copies it. "Another wheel" is a name and
    // nothing else, exactly as eucviewer's custom entry is.
    val chosen: com.eried.eucplanet.data.repository.WheelChoice? =
        if (usingCustom) custom.trim().takeIf { it.isNotEmpty() }
            ?.let { com.eried.eucplanet.data.repository.WheelChoice(name = it) }
        else options.firstOrNull { it.key == picked }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(R.string.trip_tools_change_wheel)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // A trip with no wheel recorded says so, instead of quietly
                // preselecting whatever wheel happened to lead the list.
                if (currentWheel == null) {
                    Text(
                        stringResource(R.string.trip_tools_wheel_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.textSecondary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                options.forEach { wheel ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { usingCustom = false; picked = wheel.key }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = !usingCustom && picked == wheel.key,
                            onClick = { usingCustom = false; picked = wheel.key },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(wheel.label, color = MaterialTheme.appColors.textPrimary)
                            // The advertised name, when the label came from
                            // brand/model and differs from it: it is how the
                            // wheel shows up when pairing, and how two of the
                            // same model are told apart.
                            wheel.name?.takeIf { it != wheel.label }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.appColors.textSecondary)
                            }
                            // Says which one the trip already has, so applying
                            // without changing anything is obviously a no-op.
                            if (wheel.key == currentWheel?.key) {
                                Text(
                                    stringResource(R.string.trip_tools_wheel_current),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.appColors.textSecondary,
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { usingCustom = true }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = usingCustom, onClick = { usingCustom = true })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.trip_tools_wheel_other),
                        color = MaterialTheme.appColors.textPrimary,
                    )
                }
                if (usingCustom) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = themedFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { chosen?.let(onConfirm) },
                enabled = chosen != null,
                shape = RoundedCornerShape(12.dp),
            ) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * The cuts the detector proposed, for the rider to accept or reject.
 *
 * Every one is opt-in. Only the rider knows whether a twenty minute stop was a
 * coffee break or a long traffic light, so the app proposes and never decides.
 */
@Composable
fun SplitTripDialog(
    cuts: List<TripSplitDetector.Cut>,
    formatElapsed: (Long) -> String,
    /** The ride's first sample, so a cut can be named by clock time and not
     *  only by how far into the ride it falls. */
    tripStartMs: Long,
    /** Whether there are backup copies to archive: Dropbox linked, or a backup
     *  folder chosen. With neither the row would act on nothing. */
    canArchive: Boolean,
    onConfirm: (List<TripSplitDetector.Cut>, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val clockFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val selected = remember { mutableStateOf(cuts.map { it.index }.toSet()) }
    var archiveSource by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(R.string.trip_tools_split)) },
        text = {
            if (cuts.isEmpty()) {
                Text(
                    stringResource(R.string.trip_tools_split_none),
                    color = MaterialTheme.appColors.textSecondary,
                )
            } else {
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Counts the TICKED cuts, not every one proposed. It read
                    // cuts.size + 1 before, so the "this makes N trips" line
                    // never moved as the rider ticked boxes, which is precisely
                    // the number they are deciding about.
                    // Nothing ticked has its own line rather than "this makes 1
                    // trips": the count is only ever singular in the one case
                    // where splitting does nothing, so saying what to do next
                    // beats agreeing with a number that means "no split".
                    Text(
                        if (selected.value.isEmpty()) {
                            stringResource(R.string.trip_tools_split_pick)
                        } else {
                            stringResource(R.string.trip_tools_split_hint, selected.value.size + 1)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.textSecondary,
                    )
                    cuts.forEach { cut ->
                        val on = cut.index in selected.value
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected.value = if (on) selected.value - cut.index
                                                     else selected.value + cut.index
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = {
                                    selected.value = if (on) selected.value - cut.index
                                                     else selected.value + cut.index
                                },
                            )
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(
                                    stringResource(
                                        when (cut.reason) {
                                            TripSplitDetector.Reason.WHEEL_CHANGE -> R.string.trip_split_reason_wheel
                                            TripSplitDetector.Reason.TIME_GAP -> R.string.trip_split_reason_gap
                                            TripSplitDetector.Reason.STOPPED -> R.string.trip_split_reason_stopped
                                        }
                                    ),
                                    color = MaterialTheme.appColors.textPrimary,
                                )
                                // Clock time first: a rider remembers "I stopped
                                // at 14:32", not "78 minutes in". Elapsed stays
                                // because it locates the cut on the graphs.
                                // Then how long the gap or stop actually lasted,
                                // which is the whole basis for deciding whether
                                // it was a break or a traffic light, and was
                                // computed all along without being shown.
                                Text(
                                    buildString {
                                        append(clockFmt.format(Date(tripStartMs + cut.atElapsedMs)))
                                        append("  ·  ")
                                        append(formatElapsed(cut.atElapsedMs))
                                        if (cut.durationMs > 0L) {
                                            append("  ·  ")
                                            append(
                                                stringResource(
                                                    R.string.trip_split_lasted,
                                                    formatElapsed(cut.durationMs),
                                                )
                                            )
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.appColors.textSecondary,
                                )
                            }
                        }
                    }
                    // A rule, because this is a different kind of thing from
                    // the cuts above it: what to do afterwards, not where to cut.
                    if (canArchive) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.appColors.divider)
                    Spacer(Modifier.height(4.dp))
                    ArchiveChoiceRow(
                        checked = archiveSource,
                        title = stringResource(R.string.recording_delete_archive),
                        desc = stringResource(R.string.recording_delete_archive_desc),
                        onCheckedChange = { archiveSource = it },
                    )
                    }
                }
            }
        },
        confirmButton = {
            // Nothing to split means nothing to apply, so the dialog offers a
            // single Close rather than a greyed Apply the rider might poke at.
            if (cuts.isNotEmpty()) {
                val chosen = cuts.filter { it.index in selected.value }
                TextButton(
                    onClick = { onConfirm(chosen, archiveSource) },
                    enabled = chosen.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.action_apply)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                Text(
                    stringResource(
                        if (cuts.isEmpty()) R.string.action_close else R.string.action_cancel
                    )
                )
            }
        }
    )
}

/**
 * The backup choice, shared by every action that takes a trip off the list:
 * extend, split, delete, and delete all.
 *
 * The phone's copy always goes - that is what these actions are. The question
 * is what happens to the copies in the backup folder and on Dropbox: ticked,
 * they move to an archive folder there, which is also what stops the sync
 * handing the trip back; unticked, they are left exactly as they are.
 *
 * On by default, since a rider taking a ride off the list rarely means "and
 * put it back tomorrow". Shown only when there is a backup to act on.
 */
@Composable
internal fun ArchiveChoiceRow(
    checked: Boolean,
    title: String,
    desc: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(title, color = MaterialTheme.appColors.textPrimary)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textSecondary,
            )
        }
    }
}

/**
 * How far either end of an Extend reaches, in trips.
 *
 * A rider with hundreds of trips got every one of them in both dropdowns, and
 * an extend is always the legs on either side of the same ride: a leg eight
 * trips away is not part of it.
 */
private const val EXTEND_REACH = 8

/**
 * Choose how far to extend a merge, as a continuous span of rides.
 *
 * Combining is a RANGE, not an arbitrary pick. A ride is a stretch of time, and
 * merging 8pm with 10pm while skipping the 9pm in between would produce a trip
 * whose own middle is missing: its duration and averages would describe a
 * journey that never happened. So the rider picks one other end and everything
 * between is swept in, which is also what "combine until the 10pm trip" means
 * when someone says it out loud.
 *
 * The anchor is fixed and marked; the radio picks the far end. Direction does
 * not matter, an earlier trip extends the span backwards just as well.
 */
@Composable
fun CombineTripsDialog(
    anchor: TripRecord,
    trips: List<TripRecord>,
    label: (TripRecord) -> String,
    wheelOf: (TripRecord) -> String?,
    /** Whether there are backup copies to archive: Dropbox linked, or a backup
     *  folder chosen. With neither the row would act on nothing. */
    canArchive: Boolean,
    onConfirm: (List<TripRecord>, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Chronological, because the span only means anything in time order.
    val ordered = remember(trips) { trips.sortedBy { it.startTime } }
    val anchorIdx = remember(ordered) { ordered.indexOfFirst { it.id == anchor.id } }

    // Two ends rather than one. A ride can spill over on both sides, a leg
    // before and a leg after, and one radio could only ever reach in a single
    // direction. Both default to the trip itself, so opening the dialog selects
    // nothing until the rider actually reaches out.
    var fromIdx by remember(ordered) { mutableStateOf(anchorIdx) }
    var toIdx by remember(ordered) { mutableStateOf(anchorIdx) }
    var archiveSources by remember { mutableStateOf(true) }

    // Which trips this extend may reach, and what a chosen span would really
    // merge, both from ExtendPlan so the rules can be tested without Compose.
    val reach = remember(ordered, anchorIdx) { ExtendPlan.reach(ordered, anchor) }
    val backIdx = reach.back
    val fwdIdx = reach.forward
    val nothingReachable = reach.isDeadEnd

    val merge = remember(fromIdx, toIdx, ordered) {
        ExtendPlan.merge(ordered, anchor, fromIdx, toIdx)
    }
    val range = merge.trips
    val skipped = merge.skipped
    val mixedWheels = remember(range) {
        range.mapNotNull { wheelOf(it)?.takeIf { w -> w.isNotBlank() } }.distinct().size > 1
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(R.string.trip_tools_extend)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (ordered.size < 2 || nothingReachable) {
                    Text(
                        stringResource(
                            // Different dead ends: nothing else recorded, or
                            // everything nearby is already part of this trip.
                            if (ordered.size < 2) R.string.trip_tools_combine_none
                            else R.string.trip_tools_extend_all_inside
                        ),
                        color = MaterialTheme.appColors.textSecondary,
                    )
                } else {
                    Text(
                        stringResource(R.string.trip_tools_extend_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.textSecondary,
                    )
                    Spacer(Modifier.height(12.dp))

                    // Later end first: the trips after this one are the ones a
                    // rider reaches for, since an extend usually follows a ride
                    // that got split a moment ago.
                    TripPicker(
                        title = stringResource(R.string.trip_tools_extend_to),
                        options = listOf(anchorIdx) + fwdIdx,
                        selectedIdx = toIdx,
                        optionLabel = { i ->
                            if (i == anchorIdx) stringResource(R.string.trip_tools_extend_none)
                            else label(ordered[i])
                        },
                        onSelect = { toIdx = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    // Earlier end: the anchor plus the nearest trips before it.
                    TripPicker(
                        title = stringResource(R.string.trip_tools_extend_from),
                        options = backIdx + listOf(anchorIdx),
                        selectedIdx = fromIdx,
                        optionLabel = { i ->
                            if (i == anchorIdx) stringResource(R.string.trip_tools_extend_none)
                            else label(ordered[i])
                        },
                        onSelect = { fromIdx = it },
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (range.size >= 2)
                            stringResource(R.string.trip_tools_combine_summary, range.size)
                        else stringResource(R.string.trip_tools_extend_pick_one),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.textPrimary,
                    )
                    if (skipped > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.trip_tools_extend_skipped, skipped),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.appColors.textSecondary,
                        )
                    }
                    if (mixedWheels) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.trip_tools_combine_mixed_wheels),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.appColors.statusWarn,
                        )
                    }
                    // Only once the rider has reached out to something: with
                    // nothing selected there are no sources to archive.
                    if (canArchive && range.size >= 2) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.appColors.divider)
                        Spacer(Modifier.height(4.dp))
                        ArchiveChoiceRow(
                            checked = archiveSources,
                            title = stringResource(R.string.recording_delete_archive),
                            desc = stringResource(R.string.recording_delete_archive_desc),
                            onCheckedChange = { archiveSources = it },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (ordered.size >= 2 && !nothingReachable) {
                TextButton(
                    onClick = { onConfirm(range, archiveSources) },
                    enabled = range.size >= 2,
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.action_apply)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                Text(
                    stringResource(
                        // A dead end has nothing to cancel, so it offers Close.
                        if (ordered.size < 2 || nothingReachable) R.string.action_close
                        else R.string.action_cancel
                    )
                )
            }
        }
    )
}

/**
 * One labelled dropdown of trips. Kept local so both ends look identical.
 *
 * With only the anchor to offer, the end is dead: the first trip ever recorded
 * has nothing before it and the newest has nothing after. Opening a menu whose
 * single entry is the trip you are already on says nothing, so the row greys
 * out and stops responding instead.
 */
@Composable
private fun TripPicker(
    title: String,
    options: List<Int>,
    selectedIdx: Int,
    optionLabel: @Composable (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val enabled = options.size > 1
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.appColors.textSecondary,
        )
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { open = true }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    optionLabel(selectedIdx),
                    modifier = Modifier.weight(1f),
                    color = if (enabled) MaterialTheme.appColors.textPrimary
                        else MaterialTheme.appColors.textSecondary,
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.appColors.textSecondary
                        else MaterialTheme.appColors.textSecondary.copy(alpha = 0.4f),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { i ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(i)) },
                        onClick = { onSelect(i); open = false },
                    )
                }
            }
        }
    }
}
