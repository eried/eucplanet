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
    /** Bottom-left corner readout (e.g. label "MIN", value "78%"). Null hides the corner. */
    cornerLeftLabel: String? = null,
    cornerLeftValue: String? = null,
    /** Bottom-right corner readout (e.g. label "MAX", value "94%"). */
    cornerRightLabel: String? = null,
    cornerRightValue: String? = null,
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
            .heightIn(min = 61.dp)
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

        // Text content centered. Padding gives the sparkline room to
        // breathe behind the value text without overlapping it visually.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                // Uppercase so labels read consistently regardless of
                // whether the source is a stat_* string (already caps)
                // or a metric_chip_* string (proper case in the editor).
                // .uppercase() is idempotent on already-uppercase strings.
                label.uppercase(),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1
            )
        }

        // Corner readouts. Two small chips at the bottom-left / bottom-right
        // showing rolling-window stats picked in the slot editor (MIN / MAX
        // / AVG / Median / Pxx). Rendered as inline "LABEL value" so a
        // rider scanning the tile reads "MAX 94" without an explainer.
        // Each is opt-in: nil corners draw nothing, preserving the
        // default-tile look for riders who didn't customize.
        if (cornerLeftLabel != null && cornerLeftValue != null) {
            CornerReadout(
                stat = cornerLeftLabel,
                value = cornerLeftValue,
                accent = accent,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 4.dp)
            )
        }
        if (cornerRightLabel != null && cornerRightValue != null) {
            CornerReadout(
                stat = cornerRightLabel,
                value = cornerRightValue,
                accent = accent,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun CornerReadout(
    stat: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            stat,
            fontSize = 7.sp,
            color = accent.copy(alpha = 0.85f),
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(0.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 2.dp))
        Text(
            value,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent.copy(alpha = 0.9f),
            maxLines = 1
        )
    }
}
