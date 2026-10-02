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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.navigator.QrScannerArea
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
    val pending = (step as? Step.Confirming)?.takeIf { it.offer == null }?.link
    LaunchedEffect(pending) {
        val link = pending ?: return@LaunchedEffect
        val offer = deps.api.describe(link)
        val now = step
        if (now is Step.Confirming && now.link == link) {
            step = if (offer == null) Step.Problem(msgExpired) else Step.Confirming(link, offer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.crews_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.appColors.textPrimary,
        )
        Spacer(Modifier.height(4.dp))

        when (val s = step) {
            is Step.Scanning -> {
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
                )
            }

            is Step.Refused -> {
                Banner(
                    stringResource(R.string.crews_foreign_title),
                    stringResource(R.string.crews_foreign_body, s.link.host),
                    danger = true,
                )
                Spacer(Modifier.height(18.dp))
                Secondary(stringResource(R.string.crews_scan_other)) { step = Step.Scanning }
                Secondary(stringResource(R.string.crews_close), onDone)
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
                Text(
                    s.link.code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                    color = MaterialTheme.appColors.textPrimary,
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
                        Primary(stringResource(R.string.crews_opensettings)) {
                            runCatching {
                                ctx.startActivity(
                                    Intent(ctx,
                                           Class.forName("com.eried.eucplanet.MainActivity"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            onDone()
                        }
                        Secondary(stringResource(R.string.crews_close), onDone)
                    } else {
                        Primary(stringResource(R.string.crews_issue)) {
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
                        Secondary(stringResource(R.string.crews_notme), onDone)
                    }
                }
            }

            is Step.Sending -> {
                Spacer(Modifier.height(30.dp))
                CircularProgressIndicator()
            }

            is Step.Done -> {
                Spacer(Modifier.height(24.dp))
                Banner(stringResource(R.string.crews_done_title),
                    stringResource(R.string.crews_done_body), danger = false)
                Spacer(Modifier.height(18.dp))
                Primary(stringResource(R.string.crews_done), onDone)
            }

            is Step.Problem -> {
                Banner(stringResource(R.string.crews_failed_title), s.message, danger = true)
                Spacer(Modifier.height(18.dp))
                Secondary(stringResource(R.string.crews_tryagain)) { step = Step.Scanning }
                Secondary(stringResource(R.string.crews_close), onDone)
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
private fun Primary(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(label) }
}

@Composable
private fun Secondary(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.appColors.textSecondary
        ),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(label) }
}
