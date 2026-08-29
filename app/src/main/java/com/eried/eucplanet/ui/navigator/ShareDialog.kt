package com.eried.eucplanet.ui.navigator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
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
import com.eried.eucplanet.share.ShareLink
import com.eried.eucplanet.share.ShareLinks
import com.eried.eucplanet.share.ShareSession
import com.eried.eucplanet.share.ShareState
import com.eried.eucplanet.share.activePeers
import com.eried.eucplanet.ui.dashboard.QR_MAX_PX
import com.eried.eucplanet.ui.dashboard.QrCodeImage
import com.eried.eucplanet.ui.settings.SegmentedChoice
import com.eried.eucplanet.ui.settings.SwitchSettingWithDesc
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors
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
 * combo uses), the stats toggle is [SwitchSettingWithDesc] and the session-name
 * field is the plain labelled [OutlinedTextField] every settings screen uses.
 * An earlier hand-rolled segmented row wrapped "Anonymous" mid-word because it
 * lacked the fixed row height, which is exactly what reusing the shared control
 * prevents.
 *
 * There is no in-app "paste link" box: the app claims
 * https://eucplanet.ried.no/share#... as an Android App Link, so a link that is
 * tapped or pasted anywhere on the phone opens the app on the join step
 * already. Scanning a friend's QR is the one thing the phone cannot do for the
 * rider, so it is the one join action here. The web viewer keeps its paste box
 * because a browser has no such interception.
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

/** The chrome of the browser tab the Open button raises. Read from the theme
 *  while the dialog is composing, because the tab is launched from a click
 *  handler where MaterialTheme is out of scope. */
private data class BrowserTabColors(val toolbar: Int, val navigationBar: Int)

/**
 * Hands the share link to a browser, so Open shows the web viewer.
 *
 * A plain ACTION_VIEW never leaves the app: the same URL is claimed as a
 * verified App Link (see the /share intent filter in the manifest), so Android
 * routes it back into MainActivity, which parses it, sees the room the rider is
 * already in, and dismisses. Nothing appears to happen. A Chrome Custom Tab is
 * addressed to one browser package, so it is not App Link traffic and the page
 * renders.
 *
 * With no custom-tabs browser resolvable the fallback is a browsable view
 * intent inside a chooser, so the system asks which app to use rather than
 * silently handing the link straight back to this one.
 */
private fun openInBrowser(context: Context, url: String, colors: BrowserTabColors) {
    val uri = Uri.parse(url)
    val browser = runCatching { CustomTabsClient.getPackageName(context, null) }.getOrNull()
    if (browser != null) {
        val opened = runCatching {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setDefaultColorSchemeParams(
                    CustomTabColorSchemeParams.Builder()
                        .setToolbarColor(colors.toolbar)
                        .setNavigationBarColor(colors.navigationBar)
                        .build()
                )
                .build()
                .apply { intent.setPackage(browser) }
                .launchUrl(context, uri)
        }.isSuccess
        if (opened) return
    }
    runCatching {
        val view = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(
            Intent.createChooser(view, context.getString(R.string.share_open))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * A scanned share link.
 *
 * A QR can carry the whole URL, or just the `roomId.key` fragment that a friend
 * copied out of one. Both name the same room, so the bare fragment is completed
 * to a full link before it is parsed; anything else is refused by
 * [ShareLinks.parse] as before.
 */
internal fun parseShareText(raw: String): ShareLink? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    ShareLinks.parse(text)?.let { return it }
    if (text.contains("://") || text.contains('#')) return null
    return ShareLinks.parse("${ShareLinks.BASE}#$text")
}

/**
 * The shared dialog shell: a bordered card with a surfaceVariant title strip,
 * the same look the floating theme editor and the studio replay panel use.
 */
@Composable
internal fun ShareDialogCard(
    title: String,
    /** Lets the body scroll under a pinned title strip. The group view needs
     *  it: its QR is as wide as the dialog, so a long enough rider list would
     *  otherwise push Close and Leave off the bottom of the screen. */
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        // Paired with usePlatformDefaultWidth = false at every call site. The
        // platform default is narrow enough that the three identity segments
        // get about 57dp of text each, which is what used to break "Anonymous"
        // across two lines; the shared control cannot fix a row it is not
        // given room for.
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
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
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (scrollable) Modifier.verticalScroll(rememberScrollState())
                        else Modifier
                    )
                    .padding(16.dp),
                content = content,
            )
        }
    }
}

/**
 * A block of controls on the section surface, like one open settings section.
 *
 * Not cosmetic: a notched field label fills its notch with `surfaceVariant` so
 * the label blends into the surface the control sits on. On the dialog's own
 * fill the notch would read as a patch, so every notched control in these
 * dialogs lives inside one of these.
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
    /**
     * Joins the ride behind a scanned link. Non-null only on the "not sharing
     * yet" entry point: when this dialog IS the join step for a link that
     * arrived from outside the app, offering to join a second ride would only
     * be confusing.
     *
     * The captured link is held here rather than handed straight back, so the
     * rider stays in ONE dialog: the identity they are looking at is the one
     * they will join as, and this window is never torn down mid-flow.
     */
    onJoin: ((ShareLink, Identity) -> Unit)? = null,
    /** The ride the rider last left, offered back to them above the confirm
     *  row. Null when there is nothing to go back to. */
    lastLink: ShareLink? = null,
    onRejoin: ((Identity) -> Unit)? = null,
) {
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
    /** The scanner dialog, the single "join another ride" action. */
    var scanning by remember { mutableStateOf(false) }
    /** A link the rider scanned. While it is set the dialog is the join step
     *  for that ride: same identity controls, different confirm. */
    var joinLink by remember { mutableStateOf<ShareLink?>(null) }

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
    val canRejoin = lastLink != null && onRejoin != null && onJoin != null && joinLink == null
    // The identity applies to whichever button is pressed, so the two share
    // one resolve. It reads the rider-id file and may hit the network.
    val confirmWith: (((Identity) -> Unit)) -> Unit = { action ->
        starting = true
        scope.launch { action(resolveIdentity(mode, name, shareStats)) }
    }

    if (scanning && onJoin != null) {
        ShareQrScanner(
            onLink = { link ->
                scanning = false
                joinLink = link
            },
            onDismiss = { scanning = false }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        )
    ) {
        ShareDialogCard(
            stringResource(if (joinLink != null) R.string.share_join_title else titleRes)
        ) {
            if (onJoin != null) {
                ShareSection(stringResource(R.string.share_join_another)) {
                    Spacer(Modifier.height(8.dp))
                    // One action, so one full-width button rather than a
                    // half-width one with a gap where its pair used to be.
                    OutlinedButton(
                        onClick = { scanning = true },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.appColors.outline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.appColors.textButton
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = MaterialTheme.appColors.textButton,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.share_scan_qr),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                    // The app's standard settings field: the label lives in the
                    // control's own notch, so there is no overlay to keep in
                    // sync with the field's padding.
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
                if (mode == IdentityMode.PROFILE) {
                    Spacer(Modifier.height(10.dp))
                    ProfilePreviewCard(profile)
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
                    onClick = {
                        val link = joinLink
                        val join = onJoin
                        if (link != null && join != null) confirmWith { join(link, it) }
                        else confirmWith(onStart)
                    },
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
                                when {
                                    joinLink != null -> R.string.share_join
                                    canRejoin -> R.string.share_start_new
                                    else -> confirmLabelRes
                                }
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

/**
 * The account the rider is about to ride under, at the size a picture is worth
 * looking at: avatar, display name and flag, centred.
 *
 * A thumbnail the size of a list bullet cannot answer the one question this
 * preview exists for, which is "is this the right account", so the avatar is
 * shown full size rather than as a 28 dp dot.
 */
@Composable
private fun ProfilePreviewCard(profile: ProfilePreview) {
    when (profile) {
        is ProfilePreview.Loading -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            color = MaterialTheme.appColors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        is ProfilePreview.Ready -> Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val avatar = safeAvatar(profile.identity.avatarUrl)
            if (avatar != null) {
                AsyncImage(
                    model = avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp).clip(CircleShape)
                )
            } else {
                // No picture on the account, so the peer colour stands in: it
                // is the same colour the group will see on the map.
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(peerColorOf(profile.identity.color))
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.identity.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.appColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                profile.identity.flag?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
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
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        )
    ) {
        ShareDialogCard(stringResource(R.string.share_title), scrollable = true) {
            val copiedMsg = stringResource(R.string.share_copied)
            val appColors = MaterialTheme.appColors
            val tabColors = remember(appColors) {
                BrowserTabColors(
                    toolbar = appColors.surfaceVariant.toArgb(),
                    navigationBar = appColors.dialog.toArgb(),
                )
            }
            // The relay reports a full room by closing with 1013, which the
            // session turns into one typed marker; every other error means
            // the service is simply out of reach.
            val roomFull = state.error == ShareSession.ERR_ROOM_FULL
            val statusColor = when {
                roomFull || state.error != null -> MaterialTheme.appColors.statusDanger
                state.connected -> MaterialTheme.appColors.statusGood
                else -> MaterialTheme.appColors.statusWarn
            }
            // One status line, read the way the rest of the app reads them:
            // a coloured dot, the state, and the count on the far right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        roomFull -> stringResource(R.string.share_room_full)
                        state.connected -> stringResource(R.string.share_connected)
                        state.error != null -> stringResource(R.string.share_cannot_reach)
                        else -> stringResource(R.string.share_reconnecting)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.share_rider_count, peers.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.appColors.textSecondary,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(14.dp))
            // The QR, its caption and the three link actions are one block:
            // the code is sized from the row's own width so its edges land on
            // the outer edges of the buttons under it.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Encode once, at the row's own pixel width but never above
                // QR_MAX_PX: the bitmap is remembered per (link, size) and the
                // Image scales it to fill the row, so a landscape phone or a
                // tablet cannot pull a multi-megabyte allocation through here.
                val density = LocalDensity.current
                val qrPx = remember(maxWidth, density) {
                    with(density) { maxWidth.roundToPx() }.coerceAtMost(QR_MAX_PX)
                }
                Column(Modifier.fillMaxWidth()) {
                    QrCodeImage(
                        content = url,
                        sizePx = qrPx,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.share_scan_to_join),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    // Three ways to hand the link to a friend who is not
                    // standing here. The link text itself is never shown - it
                    // is 60 characters of base64 nobody reads, and it filled
                    // the dialog.
                    Row(Modifier.fillMaxWidth()) {
                        ShareLinkAction(
                            icon = Icons.Default.Share,
                            label = stringResource(R.string.action_share),
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
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(
                                            send,
                                            context.getString(R.string.share_invite_subject)
                                        )
                                    )
                                }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        ShareLinkAction(
                            icon = Icons.Default.ContentCopy,
                            label = stringResource(R.string.share_copy),
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("EUC Planet", url))
                                onNotify(copiedMsg)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        ShareLinkAction(
                            icon = Icons.Default.OpenInBrowser,
                            label = stringResource(R.string.share_open),
                            // The web viewer, so the rider can see the group
                            // the way the friends they invite will see it.
                            onClick = { openInBrowser(context, url, tabColors) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            ShareSection(stringResource(R.string.share_riders)) {
                Spacer(Modifier.height(6.dp))
                // Nobody else is in the room, either because no one has joined
                // yet or every other rider has left. This also covers a rejoin
                // into a room the relay quietly recycled after its 1 h idle
                // expiry: there is no way to tell that apart from "not here
                // yet", so it is not reported as anything more alarming. The
                // line sits inside the section, so the header is never left
                // standing over nothing.
                val alone = peers.isEmpty() || peers.all { it.left }
                if (alone) {
                    Text(
                        stringResource(R.string.share_alone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.textSecondary
                    )
                } else {
                    // A plain Column, not a LazyColumn: the dialog body is the
                    // scrolling container now, and a lazy list inside it would
                    // be measured with an unbounded height.
                    peers.forEachIndexed { index, peer ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.appColors.divider)
                        }
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
            }
            Spacer(Modifier.height(16.dp))
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
                ) {
                    Text(
                        stringResource(R.string.share_leave),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * One of the three equal link actions under the QR. Same outlined style, same
 * width, icon over a one-word label so the row survives the longest locale.
 */
@Composable
private fun RowScope.ShareLinkAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.appColors.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.appColors.textButton
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
        modifier = Modifier.weight(1f)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.appColors.textButton,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** One friend, laid out like the app's other list rows: a 40 dp dot or avatar,
 *  the name with their flag inline, their stats under it, and how fresh the fix
 *  is on the right. Tapping the row flies the map to them. */
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
            .padding(vertical = 8.dp)
            .alpha(if (faded) 0.45f else 1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val avatar = safeAvatar(peer.last.avatarUrl)
        if (avatar != null) {
            AsyncImage(
                model = avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    peer.last.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.appColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                peer.last.flag?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
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
            else -> stringResource(R.string.share_age_seconds, (ageMs / 1000L).toInt())
        }
        // Green while the fix is current, amber once it starts aging, muted
        // once the rider is gone: the same three states the map's markers use.
        val ageColor = when {
            peer.left || peer.freshness == Freshness.LOST ->
                MaterialTheme.appColors.textSecondary
            peer.freshness == Freshness.STALE -> MaterialTheme.appColors.statusWarn
            else -> MaterialTheme.appColors.statusGood
        }
        Spacer(Modifier.width(8.dp))
        Text(
            ageText,
            style = MaterialTheme.typography.labelMedium,
            color = ageColor,
            maxLines = 1
        )
    }
}
