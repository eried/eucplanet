package com.eried.eucplanet.ui.navigator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.eried.eucplanet.share.ShareLocationText
import com.eried.eucplanet.share.ShareSession
import com.eried.eucplanet.share.ShareState
import com.eried.eucplanet.share.ShareStats
import com.eried.eucplanet.share.activePeers
import com.eried.eucplanet.ui.common.ToolRow
import com.eried.eucplanet.ui.dashboard.QR_MAX_PX
import com.eried.eucplanet.ui.dashboard.QrCodeImage
import com.eried.eucplanet.ui.settings.SegmentedChoice
import com.eried.eucplanet.ui.settings.SwitchSettingWithDesc
import com.eried.eucplanet.ui.settings.eucstats.LeaderboardProfileCard
import com.eried.eucplanet.ui.settings.eucstats.RemoteAvatar
import com.eried.eucplanet.ui.settings.eucstats.avatarInitial
import com.eried.eucplanet.ui.settings.eucstats.countryName
import com.eried.eucplanet.ui.settings.eucstats.flagEmoji
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors
import com.eried.eucplanet.util.Units
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Everything behind the navigator's Share button.
 *
 * Not in a ride yet, the button opens a short list of what it can mean
 * ([ShareMenuDialog], built like the trip tools dialog): three of the four
 * things a rider wants from that icon are one tap of work (scan a friend's QR,
 * send a Maps pin, copy the coordinates) and only the fourth opens a group. A
 * dialog that asked "how do you want to appear?" before the rider had said
 * what they were doing put the identity question in front of all four.
 *
 * That list leads to [ShareScannerDialog] (the camera), then to
 * [ShareIdentityDialog] (how the group sees this rider), which is also where
 * "start a group ride" goes directly. Once in a ride the button opens
 * [ShareGroupDialog]: the QR to hand out on one tab, who is in on the other.
 *
 * Every dialog here keeps dismissOnClickOutside off, so a stray tap on the map
 * behind cannot drop a half-typed name or a room link the rider is reading.
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
 * rider, so it is the menu's Join item. The web viewer keeps its paste box
 * because a browser has no such interception.
 */

/**
 * How wide the two square blocks in these dialogs are allowed to get: the
 * group view's QR and the scanner's camera.
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

/**
 * Put something on the clipboard and say so exactly once.
 *
 * Android 13 and up pops its own clipboard confirmation for every write, so a
 * snackbar on top of it says "Copied" twice for one tap. Below that there is
 * no system feedback at all and the snackbar is the only sign anything
 * happened, so it is posted there and only there.
 */
private fun copyToClipboard(
    context: Context,
    text: String,
    copied: String,
    onNotify: (String) -> Unit,
) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("EUC Planet", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onNotify(copied)
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

/**
 * One rider's stats as every surface in the app writes them: the group list's
 * rows, the rider's own row, and the label a tapped marker opens on the map.
 *
 * One function on purpose. The map's tooltip is built in Kotlin and pushed to
 * the page as finished text rather than re-derived in JS, so a peer's line
 * cannot read "23 km/h" in the dialog and "14 mph" on the map behind it.
 */
internal fun shareStatsLine(
    context: Context,
    stats: ShareStats,
    speedUnit: String,
    tempUnit: String,
): String = String.format(
    Locale.getDefault(),
    "%.0f %s · %d%% · %.0f%s",
    Units.speed(stats.speedKmh, speedUnit),
    Units.speedUnit(context, speedUnit),
    stats.batteryPct,
    Units.temperature(stats.tempC, tempUnit),
    Units.tempUnit(tempUnit),
)

/** The chrome of the browser tab the Open button raises. Read from the theme
 *  while the dialog is composing, because the tab is launched from a click
 *  handler where MaterialTheme is out of scope. */
private data class BrowserTabColors(val toolbar: Int, val navigationBar: Int)

/**
 * The generic "open a web page" probe used both to find the phone's browser
 * and to verify a candidate really is one.
 *
 * Needs the VIEW + BROWSABLE + scheme https entry in the manifest's
 * `<queries>` block: without it, both [PackageManager.resolveActivity] and
 * [PackageManager.queryIntentActivities] go blind to every browser package
 * on API 30+, and [resolveBrowserPackage] always returns null.
 */
private fun browserProbe(): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
        .addCategory(Intent.CATEGORY_BROWSABLE)

/**
 * The package that owns http/https VIEW links on this phone, or null if none
 * can be told apart from this app itself.
 *
 * [PackageManager.resolveActivity] answers the way a launcher would ("who
 * opens this by default"), but its answer is not trusted on its own: some
 * builds report a resolver or chooser activity instead of a real browser
 * package, and in principle any app that merely claims the https scheme
 * (without being a browser) could be the default. So the candidate is
 * accepted only if it also turns up in [PackageManager.queryIntentActivities]
 * for the very same probe, meaning it genuinely registered as a handler for
 * a generic web page rather than a name that happened to come back from a
 * resolver. When there is no default, or the default fails that check, the
 * first entry of queryIntentActivities that is not this app is used instead.
 * A device where neither path survives returns null, and the caller falls
 * back to a Custom Tab.
 */
private fun resolveBrowserPackage(context: Context): String? {
    val pm = context.packageManager
    val probe = browserProbe()
    val ownPackage = context.packageName
    val candidates = runCatching {
        pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
    }.getOrDefault(emptyList())

    val default = runCatching {
        pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()
    if (default != null && default != ownPackage &&
        candidates.any { it.activityInfo?.packageName == default }
    ) {
        return default
    }

    return candidates.firstOrNull { it.activityInfo?.packageName != ownPackage }
        ?.activityInfo?.packageName
}

/**
 * Hands [uri] to the phone's real browser as an explicit ACTION_VIEW, so it
 * opens in the browser's own task rather than inside this app's. See
 * [resolveBrowserPackage] for how the browser is named.
 *
 * Never throws: returns false, without launching anything, when no browser
 * package can be told apart, or when the explicit launch itself fails (the
 * expected case is an ActivityNotFoundException, the package answers the
 * browser question but not this exact URL). Either way the caller is free to
 * fall back to a Custom Tab.
 */
private fun openInBrowser(context: Context, uri: Uri): Boolean {
    val browserPackage = resolveBrowserPackage(context) ?: return false
    val view = Intent(Intent.ACTION_VIEW, uri)
        .setPackage(browserPackage)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(view) }.isSuccess
}

/**
 * Hands the share link to the phone's real browser, so Open shows the web
 * viewer as an ordinary tab the rider leaves the app for and comes back from.
 *
 * A plain ACTION_VIEW never leaves the app: the same URL is claimed as a
 * verified App Link (see the /share intent filter in the manifest), so Android
 * routes it back into MainActivity, which parses it, sees the room the rider is
 * already in, and dismisses. Nothing appears to happen.
 *
 * So the browser is named explicitly: [openInBrowser] resolves who owns
 * "open a web page" on this phone (see [resolveBrowserPackage]) and addresses
 * that package directly with the real link. An explicit intent is not App
 * Link traffic, and it lands in the browser's own task, which is what makes
 * it a real Chrome tab: the task switcher lists Chrome, and coming back lands
 * on the map where the rider left it. A Custom Tab renders the page inside
 * this app's own task instead, which is exactly what the rider asked not to
 * happen.
 *
 * Addressing the browser with ACTION_MAIN instead (makeMainSelectorActivity)
 * does raise Chrome, but Chrome answers a MAIN launch with its own home page
 * and drops the data URI, so the rider lands on a blank tab. Verified on the
 * emulator, which is why the link travels as a VIEW.
 *
 * A device where no browser package can be told apart falls back to a Custom
 * Tab, then to a browsable view inside a chooser, so Open still does
 * something rather than silently handing the link back to this app.
 */
private fun openShareLink(context: Context, url: String, colors: BrowserTabColors) {
    val uri = Uri.parse(url)
    if (openInBrowser(context, uri)) return
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
    /** What the body is currently showing, when the card has tabs. The scroll
     *  position belongs to the tab, not to the card: one shared state carried
     *  a scroll down the QR tab straight into the rider list, which opened
     *  already scrolled past the rider's own row. */
    scrollKey: Any? = null,
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
            key(scrollKey) {
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
}

/**
 * The Share button's window when the rider is not in a ride: four things the
 * icon can mean, each said in one line with the detail under it.
 *
 * Built exactly like the trip tools dialog (same [ToolRow] rows, same 12 dp
 * shape, same lone Cancel), because it is the same kind of thing: a short list
 * of what one button can mean. It used to be a dropdown hanging off the icon,
 * which read as a pattern of its own next to every other list of actions in
 * the app.
 *
 * The two live-ride items (scan a friend's code, start a group) hand back to
 * the caller, because both open a dialog the caller owns. The two one-shot
 * items are finished here: a static position is an Android share sheet and a
 * clipboard write, and neither needs a screen.
 *
 * Both one-shot items need a fix. Without one they stay in the list, disabled,
 * saying what is missing: hiding them would make the dialog change shape
 * between two openings a few seconds apart, and a rider who tapped Share for a
 * Maps pin would be left looking for an item that is not there.
 *
 * A rider already in a group reaches this same list by holding the Share
 * button down (a tap goes to the group view, which is the one thing that icon
 * can mean mid-ride). The two group items are disabled there for the same
 * reason the GPS ones are: the list keeps its shape, and each row says why it
 * cannot be tapped.
 */
@Composable
fun ShareMenuDialog(
    /** The rider's current fix, or null while there is none. */
    fixLat: Double?,
    fixLng: Double?,
    /** True when the rider is already in a group ride. Joining or starting one
     *  is then a leave away, so those two rows say so instead of acting. */
    inGroup: Boolean = false,
    onDismiss: () -> Unit,
    /** Open the camera: the rider is joining a friend's ride. */
    onScan: () -> Unit,
    /** Open the identity form: the rider is starting one. */
    onStartGroup: () -> Unit,
    onNotify: (String) -> Unit,
) {
    val context = LocalContext.current
    val hasFix = fixLat != null && fixLng != null
    val coords = if (hasFix) ShareLocationText.coordinates(fixLat!!, fixLng!!) else null
    val waiting = stringResource(R.string.share_waiting_gps)
    val leaveFirst = stringResource(R.string.share_leave_first)
    val copied = stringResource(R.string.share_copied)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        text = {
            Column {
                ToolRow(
                    Icons.Default.QrCodeScanner,
                    stringResource(R.string.share_menu_join),
                    if (inGroup) leaveFirst else stringResource(R.string.share_menu_join_desc),
                    enabled = !inGroup,
                ) { onDismiss(); onScan() }
                ToolRow(
                    Icons.Default.Share,
                    stringResource(R.string.share_menu_live),
                    if (inGroup) leaveFirst else stringResource(R.string.share_menu_live_desc),
                    enabled = !inGroup,
                ) { onDismiss(); onStartGroup() }
                ToolRow(
                    Icons.Default.Place,
                    stringResource(R.string.share_menu_static),
                    if (hasFix) stringResource(R.string.share_menu_static_desc) else waiting,
                    enabled = hasFix,
                ) {
                    onDismiss()
                    val link = ShareLocationText.mapsLink(fixLat!!, fixLng!!)
                    val subject = context.getString(R.string.share_static_subject)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(send, subject))
                    }
                }
                ToolRow(
                    Icons.Default.ContentCopy,
                    stringResource(R.string.share_menu_copy),
                    // The live coordinates ARE the subtitle: the rider sees
                    // exactly what lands on the clipboard before they tap.
                    coords ?: waiting,
                    enabled = hasFix,
                ) {
                    onDismiss()
                    copyToClipboard(context, coords.orEmpty(), copied, onNotify)
                }
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
 * The camera, as its own window.
 *
 * It used to be a tab of the start dialog, which meant the camera came up
 * whenever the rider was only trying to start a ride of their own. Behind its
 * own menu item it is opened by a rider who is holding a phone up at a
 * friend's screen and nothing else, so it is the whole window: a live square
 * and one way out.
 */
@Composable
fun ShareScannerDialog(
    onLink: (ShareLink) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        )
    ) {
        ShareDialogCard(
            title = stringResource(R.string.share_menu_join),
            // The camera square is as tall as the dialog is wide, so in
            // landscape the body has to be able to scroll to reach Cancel.
            scrollable = true,
        ) {
            ShareQrScannerArea(onLink = onLink, modifier = Modifier.fillMaxWidth())
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
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

/** What the Profile option can show while the dialog is open. */
private sealed class ProfilePreview {
    object Loading : ProfilePreview()
    object Missing : ProfilePreview()
    data class Ready(val identity: Identity) : ProfilePreview()
}

/**
 * How the rider wants the group to see them, asked once, for whichever ride
 * they are about to be in.
 *
 * Starting a ride and joining one ask the same three questions (how to appear,
 * under what name, with or without stats), so there is one form; only the
 * title and the confirm label differ. There are no tabs: by the time this is
 * on screen the rider has already said which of the two they are doing, in the
 * menu or by opening a link.
 */
@Composable
fun ShareIdentityDialog(
    titleRes: Int,
    default: Identity,
    hasProfile: Boolean,
    resolveIdentity: suspend (IdentityMode, String, Boolean) -> Identity,
    /** The rider's leaderboard identity, or null when nothing is linked. Cached
     *  by the ViewModel, so re-selecting Profile does not re-fetch it. */
    resolveProfile: suspend () -> Identity?,
    onConfirm: (Identity) -> Unit,
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
    // Only a remembered SESSION identity carries a name the rider typed; the
    // ANON one is a generated "Rider #1234" that must not pre-fill the field,
    // or the required-name rule would be satisfied by a number nobody chose.
    var name by remember(default) {
        mutableStateOf(if (default.mode == IdentityMode.SESSION) default.name else "")
    }
    var shareStats by remember(default) { mutableStateOf(default.shareStats) }
    var starting by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<ProfilePreview>(ProfilePreview.Loading) }

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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        )
    ) {
        ShareDialogCard(title = stringResource(titleRes), scrollable = true) {
            ShareIdentityForm(
                mode = mode,
                onModeChange = { mode = it },
                name = name,
                onNameChange = { name = it },
                shareStats = shareStats,
                onShareStatsChange = { shareStats = it },
                profile = profile,
            )
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
                ) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = canConfirm,
                    onClick = {
                        // Resolving the identity reads the rider-id file and
                        // may hit the network, so the button holds a spinner
                        // rather than letting a second tap start twice.
                        starting = true
                        scope.launch { onConfirm(resolveIdentity(mode, name, shareStats)) }
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
                            stringResource(confirmLabelRes),
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
 * How the rider will appear to the group.
 *
 * The controls are the app's canonical ones rather than local copies, so the
 * identity row is the same 56 dp segmented control every settings combo uses
 * and the name field is the same notched one. An earlier hand-rolled segmented
 * row wrapped "Anonymous" mid-word because it lacked the fixed row height.
 *
 * What sits under the segmented row changes with the choice (nothing, a name
 * field, a profile card), so it is crossfaded rather than swapped: the three
 * blocks are different heights, and a hard swap made the dialog jump under the
 * rider's finger on every tap of the row.
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
    val slidePx = with(LocalDensity.current) { IDENTITY_SLIDE.roundToPx() }
    Column(Modifier.fillMaxWidth()) {
        SegmentedChoice(
            label = stringResource(R.string.share_identity_label),
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
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                (fadeIn(tween(IDENTITY_ANIM_MS)) +
                    slideInVertically(tween(IDENTITY_ANIM_MS)) { slidePx })
                    .togetherWith(
                        fadeOut(tween(IDENTITY_ANIM_MS)) +
                            slideOutVertically(tween(IDENTITY_ANIM_MS)) { -slidePx }
                    )
            },
            label = "identity",
        ) { current ->
            when (current) {
                IdentityMode.ANON -> Box(Modifier.fillMaxWidth())

                IdentityMode.SESSION -> Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(10.dp))
                    // The app's standard settings field: the label lives in the
                    // control's own notch, so there is no overlay to keep in
                    // sync with the field's padding.
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

                IdentityMode.PROFILE -> Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(10.dp))
                    ProfilePreviewCard(profile)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // No caption: "Show my speed and battery" already says what it does,
        // and a line under it repeating the three field names was one more
        // thing to read in a dialog that asks three questions.
        SwitchSettingWithDesc(
            label = stringResource(R.string.share_stats_label),
            description = "",
            checked = shareStats,
            onCheckedChange = onShareStatsChange,
        )
    }
}

/** How far the block under the identity row travels while it fades, and for
 *  how long. Short and small on purpose: it is a hint that the form changed
 *  shape, not a transition the rider waits out. */
private val IDENTITY_SLIDE = 8.dp
private const val IDENTITY_ANIM_MS = 200

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
 * The card fades in over the spinner when the fetch lands, rather than
 * replacing it in one frame.
 */
@Composable
private fun ProfilePreviewCard(profile: ProfilePreview) {
    AnimatedContent(
        targetState = profile,
        transitionSpec = {
            fadeIn(tween(IDENTITY_ANIM_MS)) togetherWith fadeOut(tween(IDENTITY_ANIM_MS))
        },
        label = "profile",
    ) { current ->
        when (current) {
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
                stringResource(R.string.share_profile_missing, stringResource(R.string.tab_cloud)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            is ProfilePreview.Ready ->
                LeaderboardProfileCard(current.identity.asLeaderboardCard())
        }
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

/** The two halves of the group view. The QR is what the rider opens the dialog
 *  for while friends are still arriving; the rider list is what they open it
 *  for once the ride is under way. */
enum class GroupTab { QR, CONNECTED }

/**
 * The group view once the rider is in a ride: the code to hand out on one tab,
 * who is in on the other.
 *
 * They were one long column, which meant the riders sat under a full-width QR
 * and were reached by scrolling past it every single time. Tabs put the two
 * jobs side by side, and the selected one is remembered only while the dialog
 * is open: reopening lands on the QR again, because a rider who reopens this
 * mid-ride is usually showing it to someone.
 */
@Composable
fun ShareGroupDialog(
    state: ShareState.Joined,
    /** Host of the relay carrying this group, no scheme, for the header caption. */
    relayHost: String,
    speedUnit: String,
    tempUnit: String,
    /** What this rider is broadcasting right now, or null when nothing real is
     *  going out (stats off, or no wheel connected). The rider's own row shows
     *  exactly this, so it matches what the group is being told.
     *
     *  The flow, not a collected value: it recombines on every wheel telemetry
     *  tick, so a reader in the navigator's own body would invalidate the whole
     *  screen several times a second with this dialog closed. Collected here,
     *  the subscription and the recomposition both last exactly as long as the
     *  rider list is on screen, which is what its WhileSubscribed assumes. */
    myStats: StateFlow<ShareStats?>,
    /** Centre the map on one friend and open their marker's label. The id is
     *  the relay sender id, which is what the map keys its markers by. */
    onGoToPeer: (String, Double, Double) -> Unit,
    /** Centre the map on the rider themself, the way the location button does. */
    onGoToMe: () -> Unit,
    /** The tab this dialog was last on during this app run, so reopening it
     *  lands where the rider left it. Not persisted. */
    initialTab: GroupTab,
    onTabChange: (GroupTab) -> Unit,
    onNotify: (String) -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val url = remember(state.link) { ShareLinks.format(state.link) }
    // Entries, not values: the row key must be the relay sender id the map is
    // keyed by (unique by construction), not the id inside the decrypted body.
    val peers = state.peers.entries.toList()
    // The one number the toolbar badge shows: the others who are in the room
    // right now. The rider header and the "no one else here yet" line both
    // read it, so the dialog can never say "2 riders" over "nobody is here".
    // It is deliberately not peers.size: a rider who left keeps a greyed row
    // the way their marker stays on the map, and counting those rows would
    // print "2 riders" directly above "no one else here yet".
    val activeCount = state.activePeers.size
    val stats by myStats.collectAsState()
    // The tab counts the rider too: "Connected (1)" alone in a room they just
    // opened is the truth, where a 0 next to their own row on the tab under it
    // would not be.
    val connectedCount = activeCount + 1
    var tab by remember { mutableStateOf(initialTab) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        )
    ) {
        ShareDialogCard(
            title = stringResource(R.string.share_title_group),
            scrollable = true,
            // A tab switch is a new body, so it starts at the top rather than
            // wherever the other tab was left.
            scrollKey = tab,
            header = {
                ShareGroupTabRow(
                    selected = tab,
                    connectedCount = connectedCount,
                    onSelect = { tab = it; onTabChange(it) },
                )
            }
        ) {
            val copiedMsg = stringResource(R.string.share_copied)
            val appColors = MaterialTheme.appColors
            val tabColors = remember(appColors) {
                BrowserTabColors(
                    toolbar = appColors.surfaceVariant.toArgb(),
                    navigationBar = appColors.dialog.toArgb(),
                )
            }
            when (tab) {
                GroupTab.QR -> {
                    // The relay reports a full room by closing with 1013, which
                    // the session turns into one typed marker; every other error
                    // means the service is simply out of reach.
                    val roomFull = state.error == ShareSession.ERR_ROOM_FULL
                    val statusColor = when {
                        roomFull || state.error != null -> MaterialTheme.appColors.statusDanger
                        state.connected -> MaterialTheme.appColors.statusGood
                        else -> MaterialTheme.appColors.statusWarn
                    }
                    // Service state and the three things a rider does with the
                    // link, as ONE header row in the shape the trip detail
                    // screen uses: the title on the left, tinted icon actions at
                    // the side. Three outlined buttons under the QR were a
                    // second block repeating what this row already says.
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
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    roomFull -> stringResource(R.string.share_room_full)
                                    state.connected -> stringResource(R.string.share_connected)
                                    state.error != null ->
                                        stringResource(R.string.share_cannot_reach)
                                    else -> stringResource(R.string.share_reconnecting)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = statusColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // Which relay is carrying the group. On the default
                            // one it is just where the ride lives; a rider
                            // running their own needs to see which server
                            // actually answered. Hidden for the one frame before
                            // the settings flow has emitted a value, rather than
                            // showing "Relay: " with nothing after it.
                            if (relayHost.isNotEmpty()) {
                                Text(
                                    text = stringResource(
                                        R.string.share_relay_caption, relayHost
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.appColors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        // Three ways to hand the link to a friend who is not
                        // standing here. The link text itself is never shown, it
                        // is 60 characters of base64 nobody reads.
                        ShareLinkAction(
                            icon = Icons.Default.Share,
                            description = stringResource(R.string.action_share),
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
                        ShareLinkAction(
                            icon = Icons.Default.ContentCopy,
                            description = stringResource(R.string.share_copy),
                            onClick = { copyToClipboard(context, url, copiedMsg, onNotify) }
                        )
                        ShareLinkAction(
                            icon = Icons.Default.OpenInBrowser,
                            description = stringResource(R.string.share_open),
                            // The web viewer in the phone's own browser, so the
                            // rider sees the group the way their friends will.
                            onClick = { openShareLink(context, url, tabColors) }
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    // The QR, capped and centred: a square that fills a
                    // landscape dialog is taller than the screen and buries
                    // Leave under a scroll. It carries no caption, a QR under a
                    // Share icon needs no line telling a rider to scan it.
                    BoxWithConstraints(
                        Modifier
                            .widthIn(max = shareBlockMaxWidth())
                            .fillMaxWidth()
                            .align(Alignment.CenterHorizontally)
                    ) {
                        // Encode once, at the row's own pixel width but never
                        // above QR_MAX_PX: the bitmap is remembered per (link,
                        // size) and the Image scales it to fill the row, so a
                        // landscape phone or a tablet cannot pull a
                        // multi-megabyte allocation through here.
                        val density = LocalDensity.current
                        val qrPx = remember(maxWidth, density) {
                            with(density) { maxWidth.roundToPx() }.coerceAtMost(QR_MAX_PX)
                        }
                        QrCodeImage(
                            content = url,
                            sizePx = qrPx,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        )
                    }
                }

                GroupTab.CONNECTED -> {
                    // The rider themself, first and set apart: the same row the
                    // group sees for them, in a filled container so it reads as
                    // "this is you" without a second visual language.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.appColors.surfaceVariant)
                            // Tappable like every other row: it centres the
                            // map on the rider themself.
                            .clickable(onClick = onGoToMe)
                            .padding(horizontal = ROW_INSET, vertical = 8.dp)
                    ) {
                        RiderRow(
                            name = state.me.name,
                            flag = state.me.flag,
                            avatarUrl = state.me.avatarUrl,
                            color = state.me.color,
                            stats = stats,
                            speedUnit = speedUnit,
                            tempUnit = tempUnit,
                            faded = false,
                            trailing = {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.share_you),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.appColors.textSecondary,
                                    maxLines = 1
                                )
                            },
                        )
                    }
                    // Nobody else is in the room right now, either because no
                    // one has joined yet or every other rider has left or aged
                    // out. Tied to the same count the tab shows, so the two
                    // cannot disagree. It also covers a link into a room the
                    // relay already dropped (it clears a room a couple of
                    // minutes after its last socket closes): there is no way to
                    // tell that apart from "not here yet", so it is not
                    // reported as anything more alarming.
                    if (activeCount == 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.share_alone),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.appColors.textSecondary,
                            modifier = Modifier.padding(horizontal = ROW_INSET)
                        )
                    }
                    // Riders who left or went LOST keep their row, greyed, the
                    // way the map keeps their marker: the rider wants to see who
                    // was along. So the rows are drawn whenever the room has ever
                    // held anyone, independently of the empty line above.
                    if (peers.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        // A plain Column, not a LazyColumn: a lazy list inside
                        // the scrolling body would be measured with an unbounded
                        // height. Its own height is capped at six rows and it
                        // scrolls past that, so a full room cannot push Close and
                        // Leave off the bottom of the dialog.
                        Column(
                            Modifier
                                .fillMaxWidth()
                                // Inset by exactly what the filled container
                                // above pads its own row by, so every avatar
                                // in the list sits on one vertical line.
                                .padding(horizontal = ROW_INSET)
                                .heightIn(max = PEER_LIST_MAX_HEIGHT)
                                .verticalScroll(rememberScrollState())
                        ) {
                            peers.forEachIndexed { index, entry ->
                                val peer = entry.value
                                // Keyed by the rider, not by position: the room
                                // keeps arrival order today, but a row that
                                // changed rider under a positional identity would
                                // keep the old rider's remembered state and
                                // restart their avatar.
                                key(entry.key) {
                                    if (index > 0) {
                                        HorizontalDivider(
                                            color = MaterialTheme.appColors.divider
                                        )
                                    }
                                    PeerRow(
                                        peer = peer,
                                        nowMs = state.nowMs,
                                        speedUnit = speedUnit,
                                        tempUnit = tempUnit,
                                        onClick = {
                                            onGoToPeer(
                                                entry.key, peer.last.lat, peer.last.lng
                                            )
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        }
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
 * QR code | Connected (N), the app's tab row with every colour named.
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
private fun ShareGroupTabRow(
    selected: GroupTab,
    connectedCount: Int,
    onSelect: (GroupTab) -> Unit,
) {
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
        GroupTab.values().forEach { entry ->
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
                        when (entry) {
                            GroupTab.QR -> stringResource(R.string.share_tab_qr)
                            GroupTab.CONNECTED ->
                                stringResource(R.string.share_tab_connected, connectedCount)
                        },
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
 * One of the three link actions in the group header: a tinted icon button, the
 * same affordance the trip detail bar uses for its actions.
 *
 * The label they used to carry is the content description now. That is what
 * lets the row collapse to one line: "Compartilhar" (pt-rBR) and "Udostepnij"
 * (pl) never fit a third of the dialog, so the old outlined cells needed two
 * lines of text and 76 dp of height to stay square with each other.
 */
@Composable
private fun ShareLinkAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.appColors.primary,
        )
    }
}

/** How much of the rider list is on screen before it scrolls on its own: six
 *  rows of 56 dp and the hairlines between them. The dialog body scrolls too,
 *  but a full room would otherwise push Close and Leave under the fold every
 *  time the group view is opened. */
private val PEER_LIST_MAX_HEIGHT = 56.dp * 6 + 5.dp

/** The rider's own row sits in a filled container and the others do not, so
 *  the inset that container pads by is a shared number: the list under it uses
 *  the same one and every avatar lines up down the column. */
private val ROW_INSET = 8.dp

/**
 * One rider as the group sees them: a 40 dp avatar or coloured initial, the
 * name with their flag inline, their stats under it, and whatever the caller
 * puts at the end of the row.
 *
 * The rider's own row and a friend's row are the same composable on purpose.
 * The dialog's whole claim about the top row is that it shows the rider what
 * the group sees, and two layouts that only look alike would drift apart the
 * first time either was touched.
 */
@Composable
private fun RiderRow(
    name: String,
    flag: String?,
    avatarUrl: String?,
    /** Palette colour, "#RRGGBB", the same one this rider's map marker uses. */
    color: String,
    stats: ShareStats?,
    speedUnit: String,
    tempUnit: String,
    faded: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val dot = peerColorOf(color)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .alpha(if (faded) 0.45f else 1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The same avatar the profile card uses, one size down: the real
        // picture when the account has one, the rider's initial on their map
        // colour otherwise, so the row is the same height either way and the
        // dot still says which marker on the map is theirs.
        RemoteAvatar(
            url = safeAvatar(avatarUrl),
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
                    text = avatarInitial(name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.appColors.onPrimary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.appColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                flag?.let {
                    Spacer(Modifier.width(6.dp))
                    // The flag as the flag, not as its two-letter code, the
                    // way the profile card shows it.
                    Text(
                        flagEmoji(it).ifEmpty { it },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            stats?.let { s ->
                Text(
                    shareStatsLine(context, s, speedUnit, tempUnit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing()
    }
}

/** One friend in the group list: [RiderRow] with how fresh their fix is at the
 *  end. Tapping the row centres the map on them. */
@Composable
private fun PeerRow(
    peer: PeerState,
    nowMs: Long,
    speedUnit: String,
    tempUnit: String,
    onClick: () -> Unit,
) {
    val faded = peer.left || peer.freshness == Freshness.LOST
    // Aged against the session's ticking clock, not a fresh reading taken
    // during composition: the row only recomposes when the state changes,
    // so a clock sampled here would only ever be read on the tick that
    // changed something else and the label would sit still in between.
    val ageMs = nowMs - peer.lastSeenMs
    // A fresh fix says nothing: every rider in a live group is under 15 s
    // old nearly all the time, so a counter there was a number that changed
    // for no reason and read like a ping. It appears only once the fix has
    // actually started to age, which is the one time it means something.
    val ageText = when {
        peer.left -> stringResource(R.string.share_left)
        peer.freshness == Freshness.LOST -> stringResource(R.string.share_lost)
        peer.freshness == Freshness.FRESH -> ""
        ageMs < 60_000L -> stringResource(R.string.share_age_seconds, (ageMs / 1000L).toInt())
        else -> stringResource(R.string.share_age_minutes, (ageMs / 60_000L).toInt())
    }
    // Green while the fix is current, amber once it starts aging, muted
    // once the rider is gone: the same three states the map's markers use.
    // The else arm is FRESH, which never actually reaches the screen (its
    // ageText is empty, so the Text below is skipped entirely); it stays
    // here as an explicit "else" only because a boolean when needs one to
    // compile as an expression, not because the colour is ever shown.
    val ageColor = when {
        peer.left || peer.freshness == Freshness.LOST ->
            MaterialTheme.appColors.textSecondary
        peer.freshness == Freshness.STALE -> MaterialTheme.appColors.statusWarn
        else -> MaterialTheme.appColors.statusGood
    }
    RiderRow(
        name = peer.last.name,
        flag = peer.last.flag,
        avatarUrl = peer.last.avatarUrl,
        color = peer.last.color,
        stats = peer.last.stats,
        speedUnit = speedUnit,
        tempUnit = tempUnit,
        faded = faded,
        modifier = Modifier.clickable(onClick = onClick),
        trailing = {
            if (ageText.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    ageText,
                    style = MaterialTheme.typography.labelMedium,
                    color = ageColor,
                    maxLines = 1
                )
            }
        },
    )
}
