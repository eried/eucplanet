package com.eried.eucplanet.ui.navigator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.nav.NavFormat
import com.eried.eucplanet.nav.RouteSolver
import com.eried.eucplanet.ui.navigator.RouteBuilderViewModel.SolveEffort
import com.eried.eucplanet.ui.navigator.RouteBuilderViewModel.SolveState
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors
import com.eried.eucplanet.ui.theme.themedSwitchColors
import kotlin.math.roundToInt

/**
 * Optimize stop order: puts the stops in the shortest riding order. The
 * rider picks how (Quick straight lines or Best road distances), how the
 * route ends, and whether it starts from them; the result shows before and
 * after, and nothing changes until Apply.
 */
@Composable
fun SolveRouteDialog(viewModel: RouteBuilderViewModel, imperial: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.solve.collectAsState()
    val travelMode by viewModel.travelMode.collectAsState()
    val originBlock = remember { viewModel.solveOriginBlock() }
    // Two stops only have an order to pick from the rider's position, so
    // the link stays on for them.
    val originRequired = remember { viewModel.solvableStops() == 2 }
    var effort by rememberSaveable { mutableStateOf(SolveEffort.BEST) }
    var end by rememberSaveable { mutableStateOf(RouteSolver.End.ANYWHERE) }
    var linkOrigin by rememberSaveable { mutableStateOf(originBlock == null) }
    val c = MaterialTheme.appColors

    val close = { viewModel.cancelSolve(); onClose() }
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = c.textButton)

    AlertDialog(
        onDismissRequest = close,
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = c.dialog,
        titleContentColor = c.textPrimary,
        textContentColor = c.textPrimary,
        title = { Text(stringResource(R.string.nav_solve_menu)) },
        text = {
            when (val s = state) {
                SolveState.Idle -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Straight-line routes have no roads to measure, so Best
                    // would solve the same table as Quick.
                    if (travelMode != TravelMode.STRAIGHT) {
                        ChoiceRow(
                            label = stringResource(R.string.nav_solve_effort),
                            options = listOf(
                                SolveEffort.QUICK to stringResource(R.string.nav_solve_quick),
                                SolveEffort.BEST to stringResource(R.string.nav_solve_best),
                            ),
                            selected = effort,
                            onSelect = { effort = it },
                        )
                        Text(
                            stringResource(if (effort == SolveEffort.BEST) R.string.nav_solve_best_desc else R.string.nav_solve_quick_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                        )
                    }
                    ChoiceRow(
                        label = stringResource(R.string.nav_solve_ends),
                        options = listOf(
                            RouteSolver.End.ANYWHERE to stringResource(R.string.nav_solve_end_anywhere),
                            RouteSolver.End.LOOP to stringResource(R.string.nav_solve_end_loop),
                            RouteSolver.End.KEEP_LAST to stringResource(R.string.nav_solve_end_keep_last),
                        ),
                        selected = end,
                        onSelect = { end = it },
                    )
                    Text(
                        stringResource(when (end) {
                            RouteSolver.End.ANYWHERE -> R.string.nav_solve_end_anywhere_desc
                            RouteSolver.End.LOOP -> R.string.nav_solve_end_loop_desc
                            RouteSolver.End.KEEP_LAST -> R.string.nav_solve_end_keep_last_desc
                        }),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.nav_solve_link_origin), style = MaterialTheme.typography.bodyMedium)
                            if (originBlock != null) {
                                Text(originBlock, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = (linkOrigin || originRequired) && originBlock == null,
                            onCheckedChange = { linkOrigin = it },
                            enabled = originBlock == null && !originRequired,
                            colors = themedSwitchColors(),
                        )
                    }
                }
                SolveState.Working -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.nav_solve_working), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = c.primary,
                        trackColor = c.surfaceVariant,
                    )
                }
                is SolveState.Done -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (improves(s)) {
                        val pct = ((1.0 - s.afterM / s.beforeM) * 100).roundToInt()
                        Text(
                            stringResource(
                                R.string.nav_solve_result,
                                NavFormat.distance(context, s.beforeM, imperial),
                                NavFormat.distance(context, s.afterM, imperial),
                                pct,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(stringResource(R.string.nav_solve_no_better), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (s.straight) {
                        Text(
                            stringResource(R.string.nav_solve_straight_fallback),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                SolveState.Idle -> TextButton(
                    onClick = { viewModel.solveRoute(effort, end, (linkOrigin || originRequired) && originBlock == null) },
                    shape = RoundedCornerShape(12.dp),
                    colors = buttonColors,
                ) { Text(stringResource(R.string.nav_solve_run)) }
                SolveState.Working -> {}
                is SolveState.Done -> if (improves(s)) TextButton(
                    onClick = { viewModel.applySolved(); onClose() },
                    shape = RoundedCornerShape(12.dp),
                    colors = buttonColors,
                ) { Text(stringResource(R.string.action_apply)) }
            }
        },
        dismissButton = {
            TextButton(onClick = close, shape = RoundedCornerShape(12.dp), colors = buttonColors) {
                Text(stringResource(if (state is SolveState.Done && !improves(state as SolveState.Done)) R.string.action_close else R.string.action_cancel))
            }
        },
    )
}

/** Shorter by at least 1%; anything less is not worth reshuffling the list for. */
private fun improves(s: SolveState.Done): Boolean = s.beforeM > 0 && s.afterM < s.beforeM * 0.99

/** A read-only dropdown with its label inside the box, like the selectors in Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.first { it.first == selected }.second,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            colors = themedFieldColors(),
            shape = RoundedCornerShape(12.dp),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.appColors.menuBackground,
        ) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}
