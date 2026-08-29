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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.R
import com.eried.eucplanet.data.eucstats.RiderCard
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
import com.eried.eucplanet.ui.settings.eucstats.LeaderboardProfileCard
import com.eried.eucplanet.ui.settings.eucstats.RemoteAvatar
import com.eried.eucplanet.ui.settings.eucstats.countryName
import com.eried.eucplanet.ui.settings.eucstats.flagEmoji
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
 * rider, so it is the whole Join tab. The web viewer keeps its paste box
 * because a browser has no such interception.
 */

/**
 * How wide the two square blocks in these dialogs are allowed to get: the
 * group view's QR and the Join tab's camera.
 *
 * Both are laid out edge to edge and are as tall as they are wide, so without
 * a cap a landscape phone or a tablet hands a square the full dialog width and
 * it comes out taller than the window, burying the buttons under it. The cap
 * is the smaller of a portrait phone's dialog width (where nothing changes)
 * and a fraction of the window's height.
 *
 * A wide window is a short one, and that is the only case where the height
 * fraction bites: at 0.4 the caption, the Share row and the top of the rider
 * list come with the square instead of sitting below the fold. A portrait
 * window is tall enough that the 360 dp cap is what applies, so the fraction
 * there stays generous rather than shrinking the QR on a small phone.
 */
@Composable
internal fun shareBlockMaxWidth(): Dp {
    val config = LocalConfiguration.current
    val factor = if (config.screenWidthDp > config.screenHeightDp) 0.4f else 0.55f
    return minOf(360.dp, (config.screenHeightDp * factor).dp)
}

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
 * The shared dialog shell: a bordered card with a surfaceVariant header, the
 * same look the floating theme editor and the studio replay panel use.
 */
@Composable
internal fun ShareDialogCard(
    title: String,
    /** Lets the body scroll under a pinned header. The group view needs it: its
     *  QR is as wide as the dialog, so a long enough rider list would otherwise
     *  push Close and Leave off the bottom of the screen. */
    scrollable: Boolean = false,
    /** Drawn inside the header, directly under the title, on the header's own
     *  surface. The tab row is part of the card's chrome rather than a second
     *  strip stacked on it, so the two read as one block with one bottom edge. */
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        // Paired with usePlatformDefaultWidth = false at every call site. The
        // platform default is narrow enough that the three identity segments
        // get about 57dp of text each, which is what used to break "Anonymous"
        // across two lines; the shared control cannot fix a row it is not
        // given room for.
        //
        // The vertical margin is not cosmetic: a scrollable body makes the card
        // as tall as the window, and without it the Leave button sits flush
        // against the screen edge, under the gesture bar. safeDrawingPadding
        // adds whatever the system bars actually take on top of that.
        modifier = Modifier
            .fillMaxWidth()
            .safeDrawingPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.appColors.dialog,
            contentColor = MaterialTheme.appColors.textPrimary,
        ),
        border = BorderStroke(1.dp, MaterialTheme.appColors.outline),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Title and tabs share one surface and one bottom edge: the tabs
            // switch what the card shows, so they belong to its header rather
            // than to a second strip under it.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.appColors.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                header?.invoke()
            }
            HorizontalDivider(color = MaterialTheme.appColors.divider)
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

/** What the Profile option can show while the dialog is open. */
private sealed class ProfilePreview {
    object Loading : ProfilePreview()
    object Missing : ProfilePreview()
    data class Ready(val identity: Identity) : ProfilePreview()
}

/** The two ways into a ride, one tab each. Join comes first because a rider
 *  who was handed a QR is looking for the camera, but Create is what opens
 *  selected: starting a ride is the entry point riders reach cold, while a
 *  join usually arrives as a link the app intercepts before this dialog. */
private enum class ShareTab { JOIN, CREATE }

/**
 * The dialog before the rider is in a ride: one card, two tabs.
 *
 * Join is the camera, live the moment the tab is shown, and it becomes the
 * identity form as soon as it reads a share link. Create is that same identity
 * form with a different confirm. Both write the same three answers (how to
 * appear, under what name, with or without stats), so there is one form and
 * one set of state behind both tabs rather than two stacked groups.
 *
 * Opened for a link that arrived from outside the app there are no tabs at
 * all: that dialog is the join step for one specific ride, and offering to
 * open a different one would only be in the way.
 */
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
     * yet" entry point, which is also what puts the tabs on the card.
     *
     * The scanned link is held here rather than handed straight back, so the
     * rider stays in ONE dialog: the identity they are looking at is the one
     * they will join as, and this window is never torn down mid-flow.
     */
    onJoin: ((ShareLink, Identity) -> Unit)? = null,
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
    /** The link the camera read. While it is set the Join tab is the identity
     *  form for that ride instead of the scanner. */
    var joinLink by remember { mutableStateOf<ShareLink?>(null) }
    /** Tabs, or the one join step for a link that came from outside the app. */
    val tabbed = onJoin != null
    var tab by remember { mutableStateOf(ShareTab.CREATE) }
    /** The camera is the whole Join tab until it has read a link. */
    val scanning = tabbed && tab == ShareTab.JOIN && joinLink == null

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
    // The identity applies to whichever button is pressed, so the two share
    // one resolve. It reads the rider-id file and may hit the network.
    val confirmWith: (((Identity) -> Unit)) -> Unit = { action ->
        starting = true
        scope.launch { action(resolveIdentity(mode, name, shareStats)) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        )
    ) {
        ShareDialogCard(
            title = stringResource(if (joinLink != null) R.string.share_join_title else titleRes),
            // The camera square is as tall as the dialog is wide, so in
            // landscape the body has to be able to scroll to reach the
            // buttons under it.
            scrollable = true,
            header = if (!tabbed) null else ({
                ShareTabRow(
                    selected = tab,
                    onSelect = { picked ->
                        tab = picked
                        // Any tap on the row drops the scanned ride. Walking
                        // over to Create leaves it behind, and tapping Join
                        // is the rider asking for the camera again, not for
                        // the link they already read.
                        joinLink = null
                    }
                )
            })
        ) {
            if (scanning) {
                ShareQrScannerArea(
                    onLink = { link -> joinLink = link },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                ShareIdentityForm(
                    mode = mode,
                    onModeChange = { mode = it },
                    name = name,
                    onNameChange = { name = it },
                    shareStats = shareStats,
                    onShareStatsChange = { shareStats = it },
                    profile = profile,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    // On the form for a ride the camera just read, Cancel is a
                    // step back to the camera rather than the way out of the
                    // dialog: the rider is correcting a scan, not leaving.
                    onClick = { if (tabbed && joinLink != null) joinLink = null else onDismiss() },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.appColors.textButton
                    )
                ) { Text(stringResource(R.string.action_cancel)) }
                // Nothing to confirm while the camera is still looking.
                if (!scanning) {
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
                            Text(
                                stringResource(
                                    if (joinLink != null) R.string.share_join else confirmLabelRes
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
}

/**
 * Join | Create, the app's tab row with every colour named.
 *
 * The Material default would paint itself from the Material slots; this row is
 * the bottom half of the card's header, so it is told the header's surface and
 * which accent marks the selected tab. It draws no divider of its own: the
 * header carries one edge, under the tabs, for the whole block.
 */
// The tab row's own indicator slot is still experimental in Material 3; it is
// opted into rather than skipped, because the default indicator paints itself
// from the Material slots and every colour in these dialogs is named.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareTabRow(selected: ShareTab, onSelect: (ShareTab) -> Unit) {
    val appColors = MaterialTheme.appColors
    PrimaryTabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = appColors.surfaceVariant,
        contentColor = appColors.textPrimary,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selected.ordinal, matchContentSize = true),
                color = appColors.primary,
            )
        },
        divider = {},
    ) {
        ShareTab.values().forEach { entry ->
            Tab(
                selected = selected == entry,
                onClick = { onSelect(entry) },
                // The indicator under the tab is what the accent marks;
                // the label stays the readable text colour, because the
                // accent on the light theme's white card is well under the
                // contrast bar for a label.
                selectedContentColor = appColors.textPrimary,
                unselectedContentColor = appColors.textSecondary,
                text = {
                    // Material 3 Tab pads its text slot, so a label can
                    // measure narrower than the text it holds and clip on the
                    // right even when the tab has room. wrapContentWidth with
                    // unbounded = true lets the Text report its own width.
                    Text(
                        stringResource(
                            when (entry) {
                                ShareTab.JOIN -> R.string.share_tab_join
                                ShareTab.CREATE -> R.string.share_tab_create
                            }
                        ),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.wrapContentWidth(unbounded = true)
                    )
                }
            )
        }
    }
}

/**
 * How the rider will appear to the group: the one form behind both tabs.
 *
 * The controls are the app's canonical ones rather than local copies, so the
 * identity row is the same 56 dp segmented control every settings combo uses
 * and the name field is the same notched one. An earlier hand-rolled segmented
 * row wrapped "Anonymous" mid-word because it lacked the fixed row height.
 *
 * The form sits straight on the dialog surface. It used to be wrapped in a
 * tinted card, which read as a box inside a box; the only reason it needed one
 * was the notched label, and the control is told which surface it is on
 * instead.
 */
@Composable
private fun ShareIdentityForm(
    mode: IdentityMode,
    onModeChange: (IdentityMode) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    shareStats: Boolean,
    onShareStatsChange: (Boolean) -> Unit,
    profile: ProfilePreview,
) {
    Column(Modifier.fillMaxWidth()) {
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
                onModeChange(runCatching { IdentityMode.valueOf(key) }.getOrDefault(IdentityMode.ANON))
            },
            // The row is on the dialog's own fill, so the notched label fills
            // its notch with that colour rather than the settings section one.
            notchFill = MaterialTheme.appColors.dialog,
        )
        if (mode == IdentityMode.SESSION) {
            Spacer(Modifier.height(10.dp))
            // The app's standard settings field: the label lives in the
            // control's own notch, so there is no overlay to keep in sync
            // with the field's padding.
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
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
            onCheckedChange = onShareStatsChange,
        )
    }
}

/**
 * The account the rider is about to ride under.
 *
 * Ready, it is [LeaderboardProfileCard], the very composable the settings
 * screen draws for the same account: same 48 dp avatar, same name and flag,
 * same country under it. There is one leaderboard profile, so it looks the
 * same everywhere it is shown; an earlier 96 dp portrait here made the dialog
 * and the settings screen look like two different accounts.
 *
 * Loading and "nothing linked" stay one muted line: neither is a profile to
 * look at, and a placeholder the size of the card would make the form jump.
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

        is ProfilePreview.Ready ->
            LeaderboardProfileCard(profile.identity.asLeaderboardCard())
    }
}

/**
 * The share identity read as the leaderboard card the settings screen draws.
 *
 * Both come from the same rider on the same server, so the card's identity
 * fields map straight across; the stats it also carries are the settings
 * screen's own business and are not shown here, so they are left at zero
 * rather than invented. The country is the name of the flag's country, which
 * is what the settings card shows under the name.
 */
private fun Identity.asLeaderboardCard(): RiderCard = RiderCard(
    displayName = name,
    flag = flag,
    // Both read the checked url, so a non-https or malformed one cannot
    // claim a picture the card is never given.
    hasAvatar = safeAvatar(avatarUrl) != null,
    avatarUrl = safeAvatar(avatarUrl),
    totalKm = 0.0,
    trips = 0,
    topSpeedKmh = 0.0,
    maxGforce = 0.0,
    mileageRank = null,
    country = flag?.takeIf { it.length == 2 }?.let { countryName(it) },
)

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
    // The one number the toolbar badge shows: the others who are in the room
    // right now. The rider header and the "no one else here yet" line both
    // read it, so the dialog can never say "2 riders" over "nobody is here".
    // It is deliberately not peers.size: a rider who left keeps a greyed row
    // the way their marker stays on the map, and counting those rows would
    // print "2 riders" directly above "no one else here yet".
    val activeCount = state.activePeers.size

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
            // One status line, read the way the rest of the app reads them: a
            // coloured dot and the state. The rider count is not repeated here
            // - it is the rider section's own header, one line further down.
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
            }
            Spacer(Modifier.height(14.dp))
            // The QR and the three link actions are one block: the code is
            // sized from the row's own width so its edges land on the outer
            // edges of the buttons under it. The block is capped and centred,
            // because a square that fills a landscape dialog is taller than
            // the screen and buries Leave under a scroll. It carries no caption
            // - a QR over a Share row needs no line telling a rider to scan it.
            BoxWithConstraints(
                Modifier
                    .widthIn(max = shareBlockMaxWidth())
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
            ) {
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
            // Riders, as one of the app's list sections rather than a card: a
            // header with the count on the right, then rows separated by a
            // divider, straight on the dialog surface. A rounded box here sat
            // inside the dialog's own rounded bottom corner, which is two
            // corners inside each other and reads as unfinished.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.share_riders),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.appColors.sectionHeader,
                    modifier = Modifier.weight(1f)
                )
                // The one place the count is shown, and it counts who is here
                // right now, so it can read lower than the rows under it when
                // someone has left: their row stays, greyed, and the badge on
                // the map toolbar shows this same number.
                Text(
                    pluralStringResource(
                        R.plurals.share_rider_count, activeCount, activeCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.textSecondary,
                    maxLines = 1
                )
            }
            // Nobody else is in the room right now, either because no one has
            // joined yet or every other rider has left or aged out. Tied to the
            // same count the header shows, so the two cannot disagree. It also
            // covers a link into a room the relay already dropped (it clears a
            // room a couple of minutes after its last socket closes): there is
            // no way to tell that apart from "not here yet", so it is not
            // reported as anything more alarming.
            if (activeCount == 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.share_alone),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.textSecondary
                )
            }
            // Riders who left or went LOST keep their row, greyed, the way the
            // map keeps their marker: the rider wants to see who was along. So
            // the rows are drawn whenever the room has ever held anyone,
            // independently of the empty line above.
            if (peers.isNotEmpty()) {
                if (activeCount == 0) Spacer(Modifier.height(6.dp))
                // A plain Column, not a LazyColumn: a lazy list inside the
                // scrolling body would be measured with an unbounded height.
                // Its own height is capped at six rows and it scrolls past
                // that, so a full room cannot push Close and Leave off the
                // bottom of the dialog.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = PEER_LIST_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                ) {
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
 * One of the three equal link actions under the QR: same outlined style, same
 * width, an icon over its label.
 *
 * A third of the dialog is about 99dp, which "Compartilhar" (pt-rBR) and
 * "Udostepnij" (pl) do not fit on one line at labelLarge, so the label is a
 * step smaller and allowed a second line. The minimum height is the two-line
 * height, so a wrapped cell and a one-word cell stay the same size and the row
 * does not go ragged.
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
        modifier = Modifier.weight(1f).heightIn(min = 76.dp)
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
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** How much of the rider list is on screen before it scrolls on its own: six
 *  rows of 56 dp and the hairlines between them. The dialog body scrolls too,
 *  but a full room would otherwise push Close and Leave under the fold every
 *  time the group view is opened. */
private val PEER_LIST_MAX_HEIGHT = 56.dp * 6 + 5.dp

/** One friend, laid out like the app's other list rows: a 40 dp avatar or
 *  coloured initial, the name with their flag inline, their stats under it, and
 *  how fresh the fix is on the right. Tapping the row flies the map to them. */
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
        // The same avatar the profile card uses, one size down: the real
        // picture when the account has one, the rider's initial on their map
        // colour otherwise, so the row is the same height either way and the
        // dot still says which marker on the map is theirs.
        RemoteAvatar(
            url = safeAvatar(peer.last.avatarUrl),
            modifier = Modifier.size(40.dp).clip(CircleShape),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(dot),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // The palette colours are bright, so the theme's ink for a
                    // filled control is what reads on them.
                    text = peer.last.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.appColors.onPrimary,
                )
            }
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
                    // The flag as the flag, not as its two-letter code, the
                    // way the profile card shows it.
                    Text(
                        flagEmoji(it).ifEmpty { it },
                        style = MaterialTheme.typography.bodyMedium
                    )
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
