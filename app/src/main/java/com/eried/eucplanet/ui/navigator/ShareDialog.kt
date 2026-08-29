package com.eried.eucplanet.ui.navigator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.eried.eucplanet.R
import com.eried.eucplanet.share.Freshness
import com.eried.eucplanet.share.Identity
import com.eried.eucplanet.share.IdentityMode
import com.eried.eucplanet.share.PeerState
import com.eried.eucplanet.share.ShareLinks
import com.eried.eucplanet.share.ShareState
import com.eried.eucplanet.ui.dashboard.QrCodeImage
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors
import com.eried.eucplanet.ui.theme.themedSegmentedColors
import com.eried.eucplanet.ui.theme.themedSwitchColors
import com.eried.eucplanet.util.Units
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The two live-location-share dialogs behind the navigator's Share button.
 *
 * [ShareStartDialog] asks how the rider wants to appear to the group, then
 * opens (or joins) a room. [ShareGroupDialog] is the group view once joined:
 * the QR / link to hand to friends, who is in, and the way out.
 *
 * Both keep dismissOnClickOutside off, so a stray tap on the map behind them
 * cannot drop a half-typed name or a room link the rider is still reading.
 */

/** An avatar URL is another rider's string off the relay, so it is checked
 *  before it is handed to the image loader: https only, and no whitespace or
 *  quoting that could matter to whatever consumes it downstream. The map's JS
 *  applies the same rule; the two must not disagree about what is loadable. */
private const val AVATAR_UNSAFE_CHARS = "\"'<>"

private fun safeAvatar(url: String?): String? = url?.takeIf { u ->
    u.startsWith("https://") &&
        u.none { it.isWhitespace() || it in AVATAR_UNSAFE_CHARS }
}

/** Peer palette colours arrive as "#RRGGBB" strings, shared byte for byte with
 *  the web viewer, so they are data rather than theme tokens. Anything
 *  unparseable falls back to a muted theme colour instead of crashing the row. */
@Composable
private fun peerColorOf(hex: String): Color {
    val fallback = MaterialTheme.appColors.textSecondary
    return remember(hex, fallback) {
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
    }
}

@Composable
fun ShareStartDialog(
    titleRes: Int,
    default: Identity,
    hasProfile: Boolean,
    resolveIdentity: suspend (IdentityMode, String, Boolean) -> Identity,
    onStart: (Identity) -> Unit,
    onDismiss: () -> Unit,
    // Starting a new group and joining an existing one share this dialog, so
    // the confirm label is passed in rather than hardcoded, and defaults to
    // the "start a group" label since that is the more common entry point.
    confirmLabelRes: Int = R.string.share_start,
) {
    val scope = rememberCoroutineScope()
    var mode by remember(default) {
        mutableStateOf(
            // A remembered PROFILE choice is useless once the profile is gone.
            if (default.mode == IdentityMode.PROFILE && !hasProfile) IdentityMode.ANON
            else default.mode
        )
    }
    var name by remember(default) { mutableStateOf(default.name) }
    var shareStats by remember(default) { mutableStateOf(default.shareStats) }
    var starting by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.appColors.dialog,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.appColors.textPrimary
                )
                Spacer(Modifier.height(14.dp))
                IdentityPicker(
                    current = mode,
                    hasProfile = hasProfile,
                    onChange = { mode = it }
                )
                if (mode == IdentityMode.SESSION) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.share_name_label)) },
                        singleLine = true,
                        colors = themedFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.share_stats_toggle),
                        color = MaterialTheme.appColors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = shareStats,
                        onCheckedChange = { shareStats = it },
                        colors = themedSwitchColors()
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.appColors.textButton
                        )
                    ) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !starting,
                        onClick = {
                            // Resolving a PROFILE identity reads a file and may
                            // hit the network, so the button waits on it rather
                            // than handing back a half-built identity.
                            starting = true
                            scope.launch { onStart(resolveIdentity(mode, name, shareStats)) }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.appColors.primary,
                            contentColor = MaterialTheme.appColors.onPrimary
                        )
                    ) {
                        if (starting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.appColors.onPrimary
                            )
                        } else {
                            Text(stringResource(confirmLabelRes))
                        }
                    }
                }
            }
        }
    }
}

/** Anonymous / This session / Profile. Profile is disabled rather than hidden
 *  when no profile is linked, so the rider can see the option exists. */
@Composable
private fun IdentityPicker(
    current: IdentityMode,
    hasProfile: Boolean,
    onChange: (IdentityMode) -> Unit,
) {
    val options = listOf(
        IdentityMode.ANON to R.string.share_identity_anon,
        IdentityMode.SESSION to R.string.share_identity_session,
        IdentityMode.PROFILE to R.string.share_identity_profile,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (optMode, labelRes) ->
            SegmentedButton(
                selected = current == optMode,
                onClick = { onChange(optMode) },
                enabled = optMode != IdentityMode.PROFILE || hasProfile,
                shape = SegmentedButtonDefaults.itemShape(
                    index = index, count = options.size, baseShape = RoundedCornerShape(12.dp)
                ),
                colors = themedSegmentedColors(),
                icon = {},
            ) {
                Text(
                    stringResource(labelRes),
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ShareGroupDialog(
    state: ShareState.Joined,
    speedUnit: String,
    tempUnit: String,
    onFlyTo: (Double, Double) -> Unit,
    onNotify: (String) -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val url = remember(state.link) { ShareLinks.format(state.link) }
    val peers = state.peers.values.toList()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.appColors.dialog,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.share_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.appColors.textPrimary
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    QrCodeImage(content = url, sizeDp = 180)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.link,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("EUC Planet", url))
                            onNotify(url)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.appColors.textButton
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            stringResource(R.string.share_copy_link),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    send, context.getString(R.string.share_send_link)
                                )
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.appColors.primary,
                            contentColor = MaterialTheme.appColors.onPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            stringResource(R.string.share_send_link),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // The relay reports a full room through the session error; every
                // other error means the service is simply out of reach.
                val roomFull = state.error?.contains("full", ignoreCase = true) == true
                Text(
                    text = when {
                        roomFull -> stringResource(R.string.share_room_full)
                        state.connected -> stringResource(R.string.share_connected)
                        state.error != null -> stringResource(R.string.share_cannot_reach)
                        else -> stringResource(R.string.share_reconnecting)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        roomFull || state.error != null -> MaterialTheme.appColors.statusDanger
                        state.connected -> MaterialTheme.appColors.statusGood
                        else -> MaterialTheme.appColors.statusWarn
                    }
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "${stringResource(R.string.share_riders)} (${peers.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.appColors.sectionHeader
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                ) {
                    items(peers, key = { it.last.id }) { peer ->
                        PeerRow(
                            peer = peer,
                            speedUnit = speedUnit,
                            tempUnit = tempUnit,
                            onClick = {
                                onFlyTo(peer.last.lat, peer.last.lng)
                                onDismiss()
                            }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.appColors.textButton
                        )
                    ) { Text(stringResource(R.string.action_close)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onLeave,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.appColors.statusDanger,
                            contentColor = MaterialTheme.appColors.onPrimary
                        )
                    ) { Text(stringResource(R.string.share_leave)) }
                }
            }
        }
    }
}

/** One friend: their dot or avatar, name, live stats and how old the fix is.
 *  Tapping the row flies the map to them. */
@Composable
private fun PeerRow(
    peer: PeerState,
    speedUnit: String,
    tempUnit: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val dot = peerColorOf(peer.last.color)
    val faded = peer.left || peer.freshness == Freshness.LOST
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .alpha(if (faded) 0.45f else 1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val avatar = safeAvatar(peer.last.avatarUrl)
        if (avatar != null) {
            AsyncImage(
                model = avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(dot),
                contentAlignment = Alignment.Center
            ) {
                peer.last.flag?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                peer.last.name,
                color = MaterialTheme.appColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            peer.last.stats?.let { s ->
                Text(
                    String.format(
                        Locale.getDefault(),
                        "%.0f %s · %d%% · %.0f%s",
                        Units.speed(s.speedKmh, speedUnit),
                        Units.speedUnit(context, speedUnit),
                        s.batteryPct,
                        Units.temperature(s.tempC, tempUnit),
                        Units.tempUnit(tempUnit)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        val ageMs = System.currentTimeMillis() - peer.lastSeenMs
        val ageText = when {
            peer.left -> stringResource(R.string.share_left)
            peer.freshness == Freshness.LOST -> stringResource(R.string.share_lost)
            peer.freshness == Freshness.STALE ->
                stringResource(R.string.share_age_seconds, (ageMs / 1000L).toInt())
            else -> ""
        }
        if (ageText.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                ageText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textSecondary,
                maxLines = 1
            )
        }
    }
}
