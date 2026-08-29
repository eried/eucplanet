package com.eried.eucplanet.ui.navigator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.eried.eucplanet.share.ShareLink
import com.eried.eucplanet.share.ShareLinks
import com.eried.eucplanet.share.ShareSession
import com.eried.eucplanet.share.ShareState
import com.eried.eucplanet.ui.dashboard.QrCodeImage
import com.eried.eucplanet.ui.settings.SegmentedChoice
import com.eried.eucplanet.ui.settings.SwitchSettingWithDesc
import com.eried.eucplanet.ui.theme.FieldNotchLabel
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors
import com.eried.eucplanet.ui.theme.themedTonalButtonColors
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
 *
 * Controls are the app's canonical ones, not local copies: the identity picker
 * is [SegmentedChoice] (the 56 dp row with the notched label every settings
 * combo uses) and the stats toggle is [SwitchSettingWithDesc]. An earlier
 * hand-rolled segmented row wrapped "Anonymous" mid-word because it lacked the
 * fixed row height, which is exactly what reusing the shared control prevents.
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

/**
 * A scanned or pasted share link.
 *
 * A rider can paste the whole URL, or just the `roomId.key` fragment that a
 * friend copied out of one. Both name the same room, so the bare fragment is
 * completed to a full link before it is parsed; anything else is refused by
 * [ShareLinks.parse] as before.
 */
internal fun parseShareText(raw: String): ShareLink? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    ShareLinks.parse(text)?.let { return it }
    if (text.contains("://") || text.contains('#')) return null
    return ShareLinks.parse("${ShareLinks.BASE}#$text")
}

/** The clipboard's text, or "" when it holds nothing readable. */
private fun clipboardText(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val item = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
    return item?.coerceToText(context)?.toString().orEmpty()
}

/**
 * The shared dialog shell: a bordered card with a surfaceVariant title strip,
 * the same look the floating theme editor and the studio replay panel use.
 */
@Composable
internal fun ShareDialogCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.appColors.dialog,
            contentColor = MaterialTheme.appColors.textPrimary,
        ),
        border = BorderStroke(1.dp, MaterialTheme.appColors.outline),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.appColors.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    tint = MaterialTheme.appColors.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.appColors.textPrimary,
                )
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
        }
    }
}

/**
 * A block of controls on the section surface, like one open settings section.
 *
 * Not cosmetic: [FieldNotchLabel] fills its notch with `surfaceVariant` so the
 * label blends into the surface the control sits on. On the dialog's own fill
 * the notch would read as a patch, so every notched control in these dialogs
 * lives inside one of these.
 */
@Composable
private fun ShareSection(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.appColors.surfaceVariant,
            contentColor = MaterialTheme.appColors.textPrimary,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)
        ) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.appColors.sectionHeader,
                )
            }
            content()
        }
    }
}

/** What the Profile option can show while the dialog is open. */
private sealed class ProfilePreview {
    object Loading : ProfilePreview()
    object Missing : ProfilePreview()
    data class Ready(val identity: Identity) : ProfilePreview()
}

@Composable
fun ShareStartDialog(
    titleRes: Int,
    default: Identity,
    hasProfile: Boolean,
    resolveIdentity: suspend (IdentityMode, String, Boolean) -> Identity,
    /** The rider's leaderboard identity, or null when nothing is linked. Cached
     *  by the ViewModel, so re-selecting Profile does not re-fetch it. */
    resolveProfile: suspend () -> Identity?,
    onStart: (Identity) -> Unit,
    onDismiss: () -> Unit,
    // Starting a new group and joining an existing one share this dialog, so
    // the confirm label is passed in rather than hardcoded, and defaults to
    // the "start a group" label since that is the more common entry point.
    confirmLabelRes: Int = R.string.share_start,
    /** Hands back a scanned or pasted link. Null while this dialog IS the join
     *  step for a link the rider already gave us, where offering to join a
     *  second ride would only be confusing. */
    onJoinLink: ((ShareLink) -> Unit)? = null,
    /** The ride the rider last left, offered back to them above the confirm
     *  row. Null when there is nothing to go back to. */
    lastLink: ShareLink? = null,
    onRejoin: ((Identity) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember(default) {
        mutableStateOf(
            // A remembered PROFILE choice is useless once the profile is gone.
            if (default.mode == IdentityMode.PROFILE && !hasProfile) IdentityMode.ANON
            else default.mode
        )
    }
    // Only a remembered SESSION identity carries a name the rider typed; the
    // ANON one is a generated "Rider #1234" that must not pre-fill the field,
    // or the required-name rule would be satisfied by a number nobody chose.
    var name by remember(default) {
        mutableStateOf(if (default.mode == IdentityMode.SESSION) default.name else "")
    }
    var shareStats by remember(default) { mutableStateOf(default.shareStats) }
    var starting by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<ProfilePreview>(ProfilePreview.Loading) }
    // "Join another ride" state: the scanner dialog, and the paste field the
    // rider opens when they have the link as text rather than as a code.
    var scanning by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var pasteInvalid by remember { mutableStateOf(false) }

    // Resolved when the rider first looks at the Profile option, not on every
    // open: it reads the rider-id file and can hit the network.
    LaunchedEffect(mode) {
        if (mode != IdentityMode.PROFILE || profile is ProfilePreview.Ready) return@LaunchedEffect
        profile = ProfilePreview.Loading
        val p = resolveProfile()
        profile = if (p == null) ProfilePreview.Missing else ProfilePreview.Ready(p)
    }

    // A blank session name would publish as "Rider", and a Profile identity
    // that does not exist would silently fall back to a session one. Both are
    // refused at the button instead of being quietly substituted.
    val nameMissing = mode == IdentityMode.SESSION && name.isBlank()
    val profileMissing = mode == IdentityMode.PROFILE && profile is ProfilePreview.Missing
    val canConfirm = !starting && !nameMissing && !profileMissing
    // Only the "not sharing yet" entry point can rejoin: on the join step the
    // rider already said which ride they mean.
    val canRejoin = lastLink != null && onRejoin != null && onJoinLink != null
    // The identity applies to whichever button is pressed, so the two share
    // one resolve. It reads the rider-id file and may hit the network.
    val confirmWith: (((Identity) -> Unit)) -> Unit = { action ->
        starting = true
        scope.launch { action(resolveIdentity(mode, name, shareStats)) }
    }

    if (scanning && onJoinLink != null) {
        ShareQrScanner(
            onLink = { link ->
                scanning = false
                onJoinLink(link)
            },
            onDismiss = { scanning = false }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false)
    ) {
        ShareDialogCard(stringResource(titleRes)) {
            if (onJoinLink != null) {
                ShareSection(stringResource(R.string.share_join_another)) {
                    Spacer(Modifier.height(8.dp))
                    // Paired actions, so a half/half row rather than two
                    // stacked full-width buttons.
                    Row(Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { scanning = true },
                            shape = RoundedCornerShape(12.dp),
                            colors = themedTonalButtonColors(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.share_scan_qr),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                // The rider tapped Paste, so the clipboard is
                                // read once: if it already holds a link there
                                // is nothing left to type.
                                val clip = clipboardText(context)
                                val link = parseShareText(clip)
                                if (link != null) {
                                    onJoinLink(link)
                                } else {
                                    pasting = true
                                    pasteInvalid = false
                                    pasted = clip
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = themedTonalButtonColors(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.share_paste_link),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (pasting) {
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = pasted,
                                onValueChange = { typed ->
                                    pasted = typed
                                    // A link joins the moment it is complete:
                                    // a separate confirm would only be a second
                                    // tap on something already unambiguous.
                                    val link = parseShareText(typed)
                                    pasteInvalid = link == null && typed.isNotBlank()
                                    if (link != null) onJoinLink(link)
                                },
                                singleLine = true,
                                colors = themedFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                            FieldNotchLabel(stringResource(R.string.share_paste_link))
                        }
                        if (pasteInvalid) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.share_link_invalid),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.appColors.statusDanger
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            ShareSection {
                SegmentedChoice(
                    label = stringResource(R.string.share_show_me_as),
                    options = listOf(
                        IdentityMode.ANON.name to stringResource(R.string.share_identity_anon),
                        IdentityMode.SESSION.name to stringResource(R.string.share_identity_session),
                        IdentityMode.PROFILE.name to stringResource(R.string.share_identity_profile),
                    ),
                    current = mode.name,
                    // The row carries one enabled flag for all three segments, so
                    // Profile stays selectable with nothing linked: picking it
                    // explains why it cannot be used instead of looking broken.
                    onChange = { key ->
                        mode = runCatching { IdentityMode.valueOf(key) }.getOrDefault(IdentityMode.ANON)
                    },
                )
                if (mode == IdentityMode.SESSION) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            colors = themedFieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        FieldNotchLabel(stringResource(R.string.share_name_label))
                    }
                }
                if (mode == IdentityMode.PROFILE) {
                    Spacer(Modifier.height(10.dp))
                    ProfilePreviewRow(profile)
                }
                Spacer(Modifier.height(12.dp))
                SwitchSettingWithDesc(
                    label = stringResource(R.string.share_stats_toggle),
                    description = stringResource(R.string.share_stats_desc),
                    checked = shareStats,
                    onCheckedChange = { shareStats = it },
                )
            }
            Spacer(Modifier.height(16.dp))
            if (canRejoin) {
                // The likelier of the two: a rider who left a group ride for a
                // phone call is going back to the same ride, not opening one.
                Button(
                    enabled = canConfirm,
                    onClick = { confirmWith { identity -> onRejoin!!(identity) } },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.appColors.primary,
                        contentColor = MaterialTheme.appColors.onPrimary,
                        disabledContainerColor = MaterialTheme.appColors.surfaceVariant,
                        disabledContentColor = MaterialTheme.appColors.textSecondary,
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.share_rejoin_last),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
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
                    enabled = canConfirm,
                    onClick = { confirmWith(onStart) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.appColors.primary,
                        contentColor = MaterialTheme.appColors.onPrimary,
                        disabledContainerColor = MaterialTheme.appColors.surfaceVariant,
                        disabledContentColor = MaterialTheme.appColors.textSecondary,
                    )
                ) {
                    if (starting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.appColors.onPrimary
                        )
                    } else {
                        // Next to a Rejoin button, "Start sharing" reads as the
                        // same thing; it is the other ride that is on offer.
                        Text(
                            stringResource(
                                if (canRejoin) R.string.share_start_new else confirmLabelRes
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Avatar, display name and flag exactly as the group will see them, so the
 *  rider can tell at a glance which account they are about to ride under. */
@Composable
private fun ProfilePreviewRow(profile: ProfilePreview) {
    when (profile) {
        is ProfilePreview.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.appColors.primary
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.share_profile_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textSecondary
            )
        }

        is ProfilePreview.Missing -> Text(
            stringResource(R.string.share_profile_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.appColors.textSecondary
        )

        is ProfilePreview.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
            val avatar = safeAvatar(profile.identity.avatarUrl)
            if (avatar != null) {
                AsyncImage(
                    model = avatar,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp).clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(peerColorOf(profile.identity.color))
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                profile.identity.name,
                color = MaterialTheme.appColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            profile.identity.flag?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
        ShareDialogCard(stringResource(R.string.share_title)) {
            val copiedMsg = stringResource(R.string.share_copied)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // The QR is what a friend standing next to the rider scans.
                // Long-pressing it still copies the raw link, for the rare
                // case of pasting it somewhere the share sheet cannot reach.
                Box(
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("EUC Planet", url))
                            onNotify(copiedMsg)
                        }
                    )
                ) {
                    QrCodeImage(content = url, sizeDp = 180)
                }
            }
            Spacer(Modifier.height(12.dp))
            // One way out to friends who are not here: the share sheet, with a
            // sentence around the link. The link itself is not shown - it is 60
            // characters of base64 nobody reads, and it filled the dialog.
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            context.getString(R.string.share_invite_text, url)
                        )
                        putExtra(
                            Intent.EXTRA_SUBJECT,
                            context.getString(R.string.share_invite_subject)
                        )
                    }
                    context.startActivity(
                        Intent.createChooser(
                            send, context.getString(R.string.share_invite_subject)
                        )
                    )
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.appColors.primary,
                    contentColor = MaterialTheme.appColors.onPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.action_share),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))
            // The relay reports a full room by closing with 1013, which the
            // session turns into one typed marker; every other error means
            // the service is simply out of reach.
            val roomFull = state.error == ShareSession.ERR_ROOM_FULL
            // A rejoin that found nobody home. Checked before "connected",
            // because the socket IS up: the room behind it is just empty.
            val rideEnded = state.error == ShareSession.ERR_RIDE_ENDED
            Text(
                text = when {
                    roomFull -> stringResource(R.string.share_room_full)
                    rideEnded -> stringResource(R.string.share_ride_ended)
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
                        nowMs = state.nowMs,
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

/** One friend: their dot or avatar, name, live stats and how old the fix is.
 *  Tapping the row flies the map to them. */
@Composable
private fun PeerRow(
    peer: PeerState,
    nowMs: Long,
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
        // Aged against the session's ticking clock, not a fresh reading taken
        // during composition: the row only recomposes when the state changes,
        // so a clock sampled here would only ever be read on the tick that
        // changed something else and the label would sit still in between.
        val ageMs = nowMs - peer.lastSeenMs
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
