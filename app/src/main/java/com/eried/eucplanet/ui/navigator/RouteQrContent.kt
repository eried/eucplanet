package com.eried.eucplanet.ui.navigator

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.dashboard.QR_MAX_PX
import com.eried.eucplanet.ui.dashboard.QrCodeImage
import com.eried.eucplanet.ui.theme.appColors
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Everything a route window layout shows and does; the layouts only arrange it. */
internal class RouteQrUi(
    val link: String,
    val stopNames: List<String>,
    /** Each leg's length, one per stop after the first, in the rider's units. */
    val legs: List<String>,
    val distance: String,
    val onShare: () -> Unit,
    val onShareImage: () -> Unit,
    val onCopy: () -> Unit,
    val onOpen: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
private fun summary(ui: RouteQrUi): String = when {
    ui.stopNames.size >= 2 ->
        pluralStringResource(R.plurals.route_stop_count, ui.stopNames.size, ui.stopNames.size) + " · " + ui.distance
    // One stop is a place to ride to: how far depends on where the friend starts.
    ui.stopNames.size == 1 -> pluralStringResource(R.plurals.route_stop_count, 1, 1)
    else -> ui.distance
}

@Composable
private fun RouteQrCode(link: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.widthIn(max = shareBlockMaxWidth()).fillMaxWidth()) {
        val density = LocalDensity.current
        val qrPx = remember(maxWidth, density) { with(density) { maxWidth.roundToPx() }.coerceAtMost(QR_MAX_PX) }
        QrCodeImage(content = link, sizePx = qrPx, modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            ecc = ErrorCorrectionLevel.M)
    }
}

@Composable
private fun CloseRow(onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.appColors.textButton)
        ) { Text(stringResource(R.string.action_close)) }
    }
}

/**
 * The stops as a line with a dot per stop, filled at the ends and hollow
 * between, each with how far it is from the one before.
 */
@Composable
private fun StopTimeline(names: List<String>, legs: List<String>) {
    val c = MaterialTheme.appColors
    names.forEachIndexed { i, name ->
        val end = i == 0 || i == names.lastIndex
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(20.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                if (i > 0) Box(Modifier.width(2.dp).height(10.dp).background(c.primary.copy(alpha = .5f)))
                if (i < names.lastIndex) Box(Modifier.padding(top = 10.dp).width(2.dp).fillMaxHeight()
                    .background(c.primary.copy(alpha = .5f)))
                Box(Modifier.padding(top = 4.dp).size(12.dp).clip(CircleShape)
                    .then(if (end) Modifier.background(c.primary) else Modifier.background(c.surfaceVariant)
                        .border(BorderStroke(2.dp, c.primary), CircleShape)))
            }
            Spacer(Modifier.width(10.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary,
                modifier = Modifier.weight(1f).padding(bottom = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            legs.getOrNull(i - 1)?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LabeledAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.appColors.primary)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.appColors.textPrimary,
            textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun ActionRow(ui: RouteQrUi) {
    Row(Modifier.fillMaxWidth()) {
        LabeledAction(Icons.Default.Share, stringResource(R.string.action_share), Modifier.weight(1f), ui.onShare)
        LabeledAction(Icons.Default.QrCode, stringResource(R.string.route_qr_share_image), Modifier.weight(1f), ui.onShareImage)
        LabeledAction(Icons.Default.ContentCopy, stringResource(R.string.share_copy), Modifier.weight(1f), ui.onCopy)
        LabeledAction(Icons.Default.OpenInBrowser, stringResource(R.string.share_open), Modifier.weight(1f), ui.onOpen)
    }
}

/**
 * The route window: a card with the route (how many stops, how far, and every
 * stop with the leg that reaches it), the code, then the four things to do
 * with it, each named.
 */
@Composable
internal fun ColumnScope.RouteQrContent(ui: RouteQrUi) {
    val c = MaterialTheme.appColors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceVariant).padding(12.dp)
    ) {
        Text(summary(ui), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        if (ui.stopNames.isNotEmpty()) { Spacer(Modifier.height(8.dp)); StopTimeline(ui.stopNames, ui.legs) }
    }
    Spacer(Modifier.height(14.dp))
    RouteQrCode(ui.link, Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(8.dp))
    ActionRow(ui)
    CloseRow(ui.onDismiss)
}
