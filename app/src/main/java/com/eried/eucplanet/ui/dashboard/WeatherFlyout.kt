package com.eried.eucplanet.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.weather.RidabilityScore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One scored forecast hour, ready for the flyout graph. */
data class ScoredHour(val timeMs: Long, val b: RidabilityScore.Breakdown)

/**
 * The thin forecast strip that pops over the dashboard when the rider taps
 * the weather icon: the ridability score 0-10 across the chosen window, a
 * "now" edge, sparse time ticks, and a glyph strip naming which factor bites
 * when (rain, snow, wind, night). The score line is the headline; the glyphs
 * answer "why".
 */
@Composable
fun WeatherFlyout(
    hours: List<ScoredHour>,
    windowLabel: String,
    refreshing: Boolean,
    error: String?,
    updatedAgoMin: Int?,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.appColors.sheetBackground),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    windowLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.appColors.textPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        refreshing -> stringResource(R.string.weather_fetching)
                        updatedAgoMin != null -> stringResource(R.string.weather_updated_ago, updatedAgoMin)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.textSecondary,
                )
                Spacer(Modifier.weight(1f))
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.appColors.primary,
                    )
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.weather_refresh),
                            tint = MaterialTheme.appColors.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.appColors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            when {
                hours.isEmpty() && error != null -> Text(
                    stringResource(R.string.weather_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.statusWarn,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                hours.isEmpty() -> Text(
                    stringResource(R.string.weather_fetching),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.textSecondary,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                else -> ScoreGraph(hours)
            }
        }
    }
}

@Composable
private fun ScoreGraph(hours: List<ScoredHour>) {
    val good = MaterialTheme.appColors.statusGood
    val warn = MaterialTheme.appColors.statusWarn
    val danger = MaterialTheme.appColors.statusDanger
    val gridColor = MaterialTheme.appColors.divider.copy(alpha = 0.6f)

    fun colorFor(score: Float): Color = when {
        score >= 7f -> good
        score >= 4f -> warn
        else -> danger
    }

    Column {
        // Score curve. Left edge is "now"; each sample is one forecast hour.
        Box(Modifier.fillMaxWidth().height(64.dp)) {
            Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                val w = size.width
                val h = size.height
                fun x(i: Int) = if (hours.size <= 1) 0f else i * w / (hours.size - 1)
                fun y(score: Float) = h - (score / 10f) * (h - 6f) - 3f
                // Faint guides at 0 / 5 / 10.
                for (s in listOf(0f, 5f, 10f)) {
                    drawLine(gridColor, Offset(0f, y(s)), Offset(w, y(s)), strokeWidth = 1f)
                }
                // Segment-coloured line: each hop tinted by its own score, so
                // the bad stretch is visibly the bad stretch.
                for (i in 1 until hours.size) {
                    val path = Path().apply {
                        moveTo(x(i - 1), y(hours[i - 1].b.score))
                        lineTo(x(i), y(hours[i].b.score))
                    }
                    drawPath(
                        path,
                        color = colorFor((hours[i - 1].b.score + hours[i].b.score) / 2f),
                        style = Stroke(width = 5f, cap = StrokeCap.Round),
                    )
                }
                // "Now" edge marker.
                drawLine(gridColor, Offset(1.5f, 0f), Offset(1.5f, h), strokeWidth = 3f)
            }
            // Score numbers at the edges of the curve.
            Text(
                "%.0f".format(hours.first().b.score),
                fontSize = 10.sp,
                color = colorFor(hours.first().b.score),
                modifier = Modifier.align(Alignment.TopStart),
            )
            Text(
                "%.0f".format(hours.last().b.score),
                fontSize = 10.sp,
                color = colorFor(hours.last().b.score),
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }

        // Glyph strip: which factor bites, where along the timeline. Strided
        // so a rainy week doesn't render a hundred droplets.
        val density = LocalDensity.current
        var stripWidthPx by remember { mutableStateOf(0) }
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .onSizeChanged { stripWidthPx = it.width }
        ) {
            if (stripWidthPx > 0) {
                val stride = (hours.size / 14).coerceAtLeast(1)
                for (i in hours.indices step stride) {
                    val b = hours[i].b
                    val glyph: Pair<ImageVector, Color>? = when {
                        b.snow -> Icons.Default.AcUnit to MaterialTheme.appColors.statusDanger
                        b.rain -> Icons.Default.WaterDrop to MaterialTheme.appColors.statusWarn
                        b.wind -> Icons.Default.Air to MaterialTheme.appColors.textSecondary
                        b.night -> Icons.Default.Bedtime to MaterialTheme.appColors.textSecondary
                        else -> null
                    }
                    if (glyph != null) {
                        val frac = if (hours.size <= 1) 0f else i.toFloat() / (hours.size - 1)
                        val xDp = with(density) { (stripWidthPx * frac).toInt().toDp() } - 6.dp
                        Icon(
                            glyph.first,
                            contentDescription = null,
                            tint = glyph.second.copy(alpha = 0.8f),
                            modifier = Modifier.offset(x = xDp.coerceAtLeast(0.dp)).size(12.dp),
                        )
                    }
                }
            }
        }

        // Sparse time ticks: start / quarter / half / three-quarter / end.
        val fmtHour = SimpleDateFormat("HH:mm", Locale.getDefault())
        val fmtDay = SimpleDateFormat("EEE", Locale.getDefault())
        val spanMs = hours.last().timeMs - hours.first().timeMs
        val fmt = if (spanMs > 36L * 3600_000L) fmtDay else fmtHour
        Row(Modifier.fillMaxWidth()) {
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEachIndexed { idx, f ->
                val i = ((hours.size - 1) * f).toInt()
                Text(
                    if (idx == 0) stringResource(R.string.weather_now)
                    else fmt.format(Date(hours[i].timeMs)),
                    fontSize = 9.sp,
                    color = MaterialTheme.appColors.textSecondary,
                    textAlign = when (idx) {
                        0 -> TextAlign.Start
                        4 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
