package com.eried.eucplanet.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.model.SparklineStyle

/**
 * Live dashboard metric tile. One renderer for every metric, driven by
 * the per-metric [SparklineStyle] in [com.eried.eucplanet.data.model.MetricCatalog].
 *
 * Visual contract: when the rider hasn't customized the layout, the
 * default tiles (BATTERY / TEMPERATURE / VOLTAGE / CURRENT / LOAD / TRIP)
 * render exactly like the old hardcoded StatCard. Riders who customize
 * to a less-common metric get the same overall shape (label on top, big
 * centered value, sparkline behind) with a style that fits the metric's
 * shape (line for slow-moving, area for accumulating, bipolar-area for
 * signed values that swing through zero).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveMetricTile(
    label: String,
    value: String,
    accent: Color,
    sparkData: List<Float>,
    sparkStyle: SparklineStyle,
    /** Whether the rider has the sparkline background enabled for this slot. */
    sparklineEnabled: Boolean,
    modifier: Modifier = Modifier,
    /** Baseline for [SparklineStyle.AREA_BIPOLAR]; ignored for other styles. */
    bipolarBaseline: Float = 0f,
    /** Negative-lobe colour for [SparklineStyle.AREA_BIPOLAR]; defaults to a darker [accent]. */
    bipolarNegativeAccent: Color? = null,
    /** Top-left side readout — label "MIN" / "MAX" / "AVG" / "P75", etc. */
    cornerLeftLabel: String? = null,
    cornerLeftValue: String? = null,
    /** Top-right side readout, mirror of the left one. */
    cornerRightLabel: String? = null,
    cornerRightValue: String? = null,
    /** Centre stat tag — rendered inline with [label] when the rider made the
     *  big number show a non-default aggregation (e.g. "MAX BATTERY  82%"). */
    centerStatLabel: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val clickModifier = when {
        onClick != null || onLongClick != null -> Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick
        )
        else -> Modifier
    }
    // No data → no chart. The default 5 metrics (BATTERY, TEMPERATURE,
    // VOLTAGE, CURRENT, LOAD) get their own bespoke disconnected demos
    // from DashboardScreen so cold-boot still has personality; every
    // other catalog metric renders empty until real samples arrive.
    // An empty tile honestly signals "no data yet" — better than a
    // synthetic curve that riders could mistake for telemetry.
    val effectiveSparkData = sparkData
    Box(
        modifier = modifier
            // Fixed height matches the composite-tile renderer in
            // DashboardScreen so a row that mixes a MULTI tile with a
            // standard tile reads as a single horizontal band, not a
            // staircase. Long metric labels ellipsise rather than push
            // the tile taller; the label Text below sets maxLines = 1
            // for the same reason.
            .height(61.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(clickModifier),
        contentAlignment = Alignment.Center
    ) {
        // Sparkline canvas — drawn whenever the rider has it enabled
        // AND the metric style isn't NONE. effectiveSparkData supplies
        // a decorative sine when real samples haven't arrived yet so
        // every catalog tile looks alive on cold boot.
        if (sparklineEnabled && sparkStyle != SparklineStyle.NONE && effectiveSparkData.size >= 2) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .matchParentSize()
            ) {
                val padding = size.height * 0.15f
                val drawHeight = size.height - padding * 2
                val stepX = size.width / (effectiveSparkData.size - 1).toFloat()
                val min = effectiveSparkData.min()
                val max = effectiveSparkData.max()
                // Bipolar style anchors the visible range to span the
                // baseline, so the chart honestly shows "this much
                // above zero" vs "this much below zero" rather than
                // normalising the whole range to fill the box.
                val effectiveMin: Float
                val effectiveMax: Float
                if (sparkStyle == SparklineStyle.AREA_BIPOLAR) {
                    effectiveMin = kotlin.math.min(min, bipolarBaseline)
                    effectiveMax = kotlin.math.max(max, bipolarBaseline)
                } else {
                    effectiveMin = min
                    effectiveMax = max
                }
                val range = (effectiveMax - effectiveMin).coerceAtLeast(0.1f)

                fun yFor(v: Float): Float {
                    return padding + drawHeight - ((v - effectiveMin) / range) * drawHeight
                }

                when (sparkStyle) {
                    SparklineStyle.LINE -> {
                        val path = Path()
                        effectiveSparkData.forEachIndexed { idx, v ->
                            val x = idx * stepX
                            val y = yFor(v)
                            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color = accent.copy(alpha = 0.4f), style = Stroke(width = 2.5f))
                    }

                    SparklineStyle.SMOOTH_LINE -> {
                        // Catmull-Rom-ish smoothing via quadratic
                        // beziers between midpoints. Cheaper than a
                        // true Catmull-Rom and visually indistinguishable
                        // at 15-sample sparkline scale.
                        val path = Path()
                        if (effectiveSparkData.isNotEmpty()) {
                            var prevX = 0f
                            var prevY = yFor(effectiveSparkData[0])
                            path.moveTo(prevX, prevY)
                            for (i in 1 until effectiveSparkData.size) {
                                val curX = i * stepX
                                val curY = yFor(effectiveSparkData[i])
                                val midX = (prevX + curX) / 2f
                                val midY = (prevY + curY) / 2f
                                path.quadraticTo(prevX, prevY, midX, midY)
                                prevX = curX; prevY = curY
                            }
                            path.lineTo(prevX, prevY)
                        }
                        drawPath(path, color = accent.copy(alpha = 0.4f), style = Stroke(width = 2.5f))
                    }

                    SparklineStyle.AREA -> {
                        // Today's default look: stroke + faint fill
                        // underneath. Keeps the default tiles visually
                        // identical to the old hardcoded StatCard.
                        val linePath = Path()
                        effectiveSparkData.forEachIndexed { idx, v ->
                            val x = idx * stepX
                            val y = yFor(v)
                            if (idx == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                        }
                        val fillPath = Path()
                        fillPath.addPath(linePath)
                        fillPath.lineTo(size.width, size.height)
                        fillPath.lineTo(0f, size.height)
                        fillPath.close()
                        drawPath(fillPath, color = accent.copy(alpha = 0.08f))
                        drawPath(linePath, color = accent.copy(alpha = 0.4f), style = Stroke(width = 2.5f))
                    }

                    SparklineStyle.AREA_BIPOLAR -> {
                        val baselineY = yFor(bipolarBaseline)
                        val negAccent = bipolarNegativeAccent ?: accent
                        // Positive lobe — fill between the line and the
                        // baseline for samples that sit above baseline.
                        val posFill = Path()
                        posFill.moveTo(0f, baselineY)
                        effectiveSparkData.forEachIndexed { idx, v ->
                            val x = idx * stepX
                            val y = yFor(v)
                            // Cap below the baseline so the negative
                            // lobe's fill stays in the other path.
                            posFill.lineTo(x, kotlin.math.min(y, baselineY))
                        }
                        posFill.lineTo(size.width, baselineY)
                        posFill.close()
                        drawPath(posFill, color = accent.copy(alpha = 0.18f))

                        // Negative lobe — mirror logic for samples below baseline.
                        val negFill = Path()
                        negFill.moveTo(0f, baselineY)
                        effectiveSparkData.forEachIndexed { idx, v ->
                            val x = idx * stepX
                            val y = yFor(v)
                            negFill.lineTo(x, kotlin.math.max(y, baselineY))
                        }
                        negFill.lineTo(size.width, baselineY)
                        negFill.close()
                        drawPath(negFill, color = negAccent.copy(alpha = 0.18f))

                        // Stroke the polyline on top of both fills so
                        // the rider sees the actual curve clearly.
                        val linePath = Path()
                        effectiveSparkData.forEachIndexed { idx, v ->
                            val x = idx * stepX
                            val y = yFor(v)
                            if (idx == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                        }
                        drawPath(linePath, color = accent.copy(alpha = 0.5f), style = Stroke(width = 2.5f))
                    }

                    SparklineStyle.NONE -> Unit
                }
            }
        }

        // Zone overlay. The centre column ALWAYS centres on the tile
        // midpoint, regardless of how many side readings are active —
        // a 2-zone (left+centre) layout used to push the big value off
        // toward 72% of the tile width via a Row + weights, which read
        // as "the centre is wrong". Now the big value is always at the
        // tile centre and the badges sit at the actual corners. With 0,
        // 1 or 2 side readings the visual centre never moves; with both
        // present the result is identical to the previous 3-column row.
        val hasLeft = cornerLeftLabel != null && cornerLeftValue != null
        val hasRight = cornerRightLabel != null && cornerRightValue != null
        val hasSideReadings = hasLeft || hasRight
        val centerBigSp = if (hasSideReadings) 18 else 20
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 12.dp)
        ) {
            // Centre column drawn first as the visual anchor — laid out
            // across the full tile width and centre-aligned so the big
            // number always lands on the tile midpoint.
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                CenterColumn(label, value, centerStatLabel, accent, centerBigSp)
            }
            if (hasLeft) {
                Box(modifier = Modifier.align(Alignment.TopStart)) {
                    SideBadge(cornerLeftLabel!!, cornerLeftValue!!, accent, Alignment.Start)
                }
            }
            if (hasRight) {
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    SideBadge(cornerRightLabel!!, cornerRightValue!!, accent, Alignment.End)
                }
            }
        }
    }
}

@Composable
private fun SideBadge(
    statLabel: String,
    value: String,
    accent: Color,
    align: Alignment.Horizontal
) {
    Column(horizontalAlignment = align) {
        Text(
            statLabel,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp
        )
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1
        )
    }
}

@Composable
private fun CenterColumn(
    metricLabel: String,
    value: String,
    centerStatLabel: String?,
    accent: Color,
    bigSp: Int
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Stat name + metric label on the same row so the header reads as
        // "MAX BATTERY" instead of stacked labels. Stat name hidden when
        // the centre is the default (CURRENT live value) since it'd be
        // redundant alongside the big value.
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
        ) {
            if (centerStatLabel != null) {
                Text(
                    centerStatLabel,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp
                )
            }
            Text(
                metricLabel.uppercase(),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                // Lock to a single line so long names like "Battery power"
                // ellipsise instead of wrapping and forcing the tile taller
                // than its row neighbours.
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontSize = bigSp.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1
        )
    }
}
