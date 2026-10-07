package com.eried.eucplanet.crews

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.ui.navigator.ShareDialogCard
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.navigator.QrScannerArea
import com.eried.eucplanet.share.ShareLinks
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import com.eried.eucplanet.ui.theme.appColors
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Crews pairing: scan a code from a browser, see what it would grant, approve it.
 *
 * This is the entire job of the phone in Crews. Everything else — founding a crew, joining
 * one, approving members, looking at territory — happens in a browser on eucstats. The app is
 * here because it is the only thing that holds the rider's store_id, and the point of the
 * handshake is that the store_id never has to be typed into a web form.
 *
 * Its own activity rather than a destination in the nav graph, because a deep link needs an
 * activity to land on and because pairing is a short errand the rider arrives at from outside
 * the app as often as from inside it.
 */
@AndroidEntryPoint
class CrewsPairActivity : ComponentActivity() {

    @Inject lateinit var deps: CrewsPairDeps

    /**
     * The link currently being acted on, as state rather than read once.
     *
     * This activity is singleTop, so a second pairing link while it is already open arrives
     * through [onNewIntent] rather than building a new activity. Reading the intent once in
     * onCreate meant that second scan changed nothing on screen — the rider was left looking
     * at the previous code with no sign anything had happened.
     */
    private val link = mutableStateOf<PairLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link.value = PairLink.parse(intent?.dataString)
        setContent {
            EucPlanetTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.appColors.surface,
                ) {
                    CrewsPairScreen(
                        deps = deps,
                        initialLink = link.value,
                        onDone = { finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        link.value = PairLink.parse(intent.dataString)
    }
}

/**
 * What the screen needs, behind one object so the activity stays thin.
 *
 * Built by [com.eried.eucplanet.di.CrewsModule] rather than constructor-injected: two of the
 * three members are lambdas reading live state, and a bare lambda is not something Hilt can
 * resolve on its own.
 */
class CrewsPairDeps(
    val api: PairApi,
    private val storeIdProvider: () -> String?,
    val developerMode: () -> Boolean,
) {
    fun storeId(): String? = storeIdProvider()
}

private sealed interface Step {
    data object Scanning : Step
    data class Confirming(val link: PairLink, val offer: PairOffer?) : Step
    data class Refused(val link: PairLink) : Step
    data object Sending : Step
    data object Done : Step
    data class Problem(val message: String) : Step
}

@Composable
fun CrewsPairScreen(
    deps: CrewsPairDeps,
    initialLink: PairLink?,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val devMode = remember { deps.developerMode() }
    // resolved here, not inside the coroutine: stringResource is a composable call
    val shareCode = stringResource(R.string.crews_scan_is_share)
    val msgExpired = stringResource(R.string.crews_expired)
    val msgRate = stringResource(R.string.crews_ratelimited)
    val msgUnreachTpl = stringResource(R.string.crews_unreachable, "%HOST%")
    val msgRefusedTpl = stringResource(R.string.crews_refused, 0)
    // keyed on the link, so a fresh one arriving from a new intent resets the screen instead
    // of leaving the previous code on display
    var step by remember(initialLink) {
        mutableStateOf<Step>(
            if (initialLink == null) Step.Scanning
            else if (initialLink.trust(devMode) == PairTrust.REFUSED) Step.Refused(initialLink)
            else Step.Confirming(initialLink, null)
        )
    }

    // Ask the server what the code is for as soon as there is one, so the approve button is
    // never the first thing the rider sees with nothing but "Allow?" above it.
    // Typing a code, for the case the web copy has always described: the app did not open by
    // itself, and a phone cannot photograph its own screen. A code with no host means THIS
    // build's own server -- `PairLink.parseAppScheme` does the same when `host` is absent --
    // so a typed code cannot aim the phone anywhere else and the trust rules below are
    // untouched by it.
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var typedBad by remember { mutableStateOf(false) }

    fun submitTyped() {
        val link = PairLink.parse("eucplanet://pair?code=$typed")
        if (link == null) {
            typedBad = true
            return
        }
        typedBad = false
        step = if (link.trust(devMode) == PairTrust.REFUSED) Step.Refused(link)
        else Step.Confirming(link, null)
    }

    val pending = (step as? Step.Confirming)?.takeIf { it.offer == null }?.link
    LaunchedEffect(pending) {
        val link = pending ?: return@LaunchedEffect
        val offer = deps.api.describe(link)
        val now = step
        if (now is Step.Confirming && now.link == link) {
            step = if (offer == null) Step.Problem(msgExpired) else Step.Confirming(link, offer)
        }
    }

    // The same window Join group ride uses, rather than a full-screen column with a headline
    // on it: both are "point this at a code somebody else is showing", and only one of them
    // looked like it. `ShareDialogCard` brings the header, the divider and the border with it.
    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(
            // A stray tap outside must not drop a half-finished pairing.
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
      ShareDialogCard(
          title = stringResource(R.string.crews_pass_action),
          icon = Icons.Filled.QrCodeScanner,
          // The camera square is as tall as the card is wide, so in landscape the body has to
          // scroll to reach the buttons -- the same reason the join scanner sets it.
          scrollable = true,
      ) {
        when (val s = step) {
            is Step.Scanning -> {
                // One or the other, never both. This used to show a live camera preview, a
                // text field and a full-width button stacked together in a card that then had
                // to scroll; a phone cannot photograph its own screen, so whichever of the two
                // you are using, the other one is in the way.
                if (!typing) {
                    Hint(stringResource(R.string.crews_scan_hint))
                    Spacer(Modifier.height(16.dp))
                    QrScannerArea(
                        parse = { PairLink.parse(it) },
                        onFound = { link ->
                            step = if (link.trust(devMode) == PairTrust.REFUSED) Step.Refused(link)
                            else Step.Confirming(link, null)
                        },
                        invalidText = stringResource(R.string.crews_scan_invalid),
                        modifier = Modifier.fillMaxWidth(),
                        // The live-location code is the one a rider is most likely to point
                        // this at by mistake: it is the other QR the app deals in, it is on
                        // the map a tap away, and it scans perfectly here and means nothing.
                        // Saying so beats telling them a working code is invalid.
                        misfit = { if (ShareLinks.parse(it) != null) shareCode else null },
                    )
                    // eucstats tells riders in nineteen languages to type the code in if the
                    // app did not open by itself. There was nothing to type it into.
                    Spacer(Modifier.height(14.dp))
                    Secondary(stringResource(R.string.crews_type_instead)) { typing = true }
                } else {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.trim().uppercase().take(12) },
                        label = { Text(stringResource(R.string.crews_code_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { submitTyped() }),
                        isError = typedBad,
                        supportingText = if (typedBad) {
                            { Text(stringResource(R.string.crews_scan_invalid)) }
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                    )
                    Spacer(Modifier.height(14.dp))
                    // The way back is the camera, not a dead end: somebody who opened this by
                    // mistake, or whose code will not type, has one press to the scanner.
                    ActionRow {
                        Primary(
                            stringResource(R.string.action_continue),
                            Modifier.weight(1f),
                        ) { submitTyped() }
                        Secondary(
                            stringResource(R.string.crews_scan_switch),
                            Modifier.weight(1f),
                        ) { typing = false; typed = ""; typedBad = false }
                    }
                }
            }

            is Step.Refused -> {
                Banner(
                    stringResource(R.string.crews_foreign_title),
                    stringResource(R.string.crews_foreign_body, s.link.host),
                    danger = true,
                )
                Spacer(Modifier.height(18.dp))
                ActionRow {
                    Secondary(stringResource(R.string.crews_scan_other), Modifier.weight(1f)) {
                        step = Step.Scanning
                    }
                    Secondary(stringResource(R.string.crews_close), Modifier.weight(1f), onDone)
                }
            }

            is Step.Confirming -> {
                if (!s.link.isProduction) {
                    Banner(
                        stringResource(R.string.crews_dev_title),
                        stringResource(R.string.crews_dev_body, s.link.host),
                        danger = false,
                    )
                    Spacer(Modifier.height(14.dp))
                }
                // The card's body aligns its children to the start, so the code sat against
                // the left edge under a centred header. It is the one thing on this screen the
                // rider is checking against the browser, so it goes in the middle.
                Text(
                    s.link.code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.appColors.textPrimary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                if (s.offer == null) {
                    CircularProgressIndicator(Modifier.height(26.dp))
                } else {
                    Hint(stringResource(R.string.crews_grant))
                    Spacer(Modifier.height(18.dp))
                    val storeId = deps.storeId()
                    if (storeId.isNullOrBlank()) {
                        Banner(
                            stringResource(R.string.crews_noprofile_title),
                            stringResource(R.string.crews_noprofile_body),
                            danger = true,
                        )
                        Spacer(Modifier.height(14.dp))
                        // A dead end with one Close button is still a dead end. This is the
                        // only screen in the flow that tells somebody to go and do something
                        // else, so it takes them there.
                        val ctx = LocalContext.current
                        ActionRow {
                            Primary(
                                stringResource(R.string.crews_opensettings),
                                Modifier.weight(1f),
                            ) {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(ctx,
                                               Class.forName("com.eried.eucplanet.MainActivity"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                                onDone()
                            }
                            Secondary(
                                stringResource(R.string.crews_close),
                                Modifier.weight(1f),
                                onDone,
                            )
                        }
                    } else {
                        ActionRow {
                            Primary(
                                stringResource(R.string.crews_issue),
                                Modifier.weight(1f),
                            ) {
                                step = Step.Sending
                                scope.launch {
                                    step = when (val r = deps.api.confirm(s.link, storeId)) {
                                        is PairResult.Ok -> Step.Done
                                        is PairResult.Expired -> Step.Problem(msgExpired)
                                        is PairResult.RateLimited -> Step.Problem(msgRate)
                                        is PairResult.Unreachable -> Step.Problem(
                                            msgUnreachTpl.replace("%HOST%", s.link.host))
                                        is PairResult.Failed -> Step.Problem(
                                            r.detail ?: msgRefusedTpl.replace("0", r.code.toString())
                                        )
                                    }
                                }
                            }
                            Secondary(
                                stringResource(R.string.crews_notme),
                                Modifier.weight(1f),
                                onDone,
                            )
                        }
                    }
                }
            }

            is Step.Sending -> {
                Spacer(Modifier.height(30.dp))
                // The old scaffold centred every child; the card's body does not.
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }

            is Step.Done -> {
                Spacer(Modifier.height(24.dp))
                Banner(stringResource(R.string.crews_done_title),
                    stringResource(R.string.crews_done_body), danger = false)
                Spacer(Modifier.height(18.dp))
                Primary(stringResource(R.string.crews_done), onClick = onDone)
            }

            is Step.Problem -> {
                Banner(stringResource(R.string.crews_failed_title), s.message, danger = true)
                Spacer(Modifier.height(18.dp))
                ActionRow {
                    Secondary(stringResource(R.string.crews_tryagain), Modifier.weight(1f)) {
                        step = Step.Scanning
                    }
                    Secondary(stringResource(R.string.crews_close), Modifier.weight(1f), onDone)
                }
            }
        }
      }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.appColors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

@Composable
private fun Banner(title: String, body: String, danger: Boolean) {
    val tint = if (danger) MaterialTheme.appColors.statusDanger
    else MaterialTheme.appColors.textButton
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.appColors.surfaceVariant)
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = tint)
            Text(body, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textSecondary)
        }
    }
}

@Composable
private fun Primary(
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) { Text(label) }
}

@Composable
private fun Secondary(
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.appColors.textSecondary
        ),
        modifier = modifier,
    ) { Text(label) }
}

/** Two actions, side by side. A yes/no question is one decision, not two stacked bars. */
@Composable
private fun ActionRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
