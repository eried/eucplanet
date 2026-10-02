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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            step = if (offer == null) Step.Problem(EXPIRED) else Step.Confirming(link, offer)
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
            "Crews",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.appColors.textPrimary,
        )
        Spacer(Modifier.height(4.dp))

        when (val s = step) {
            is Step.Scanning -> {
                Hint(
                    "Open eucstats in a browser, pick Crews, and point the camera at the code " +
                        "it shows. Nothing is typed and your rider id stays on this phone."
                )
                Spacer(Modifier.height(16.dp))
                QrScannerArea(
                    parse = { PairLink.parse(it) },
                    onFound = { link ->
                        step = if (link.trust(devMode) == PairTrust.REFUSED) Step.Refused(link)
                        else Step.Confirming(link, null)
                    },
                    invalidText = "That is not an eucstats pairing code.",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is Step.Refused -> {
                Banner(
                    "This code points somewhere else",
                    "It wants to pair with ${s.link.host}, which is not the eucstats this app " +
                        "talks to. Approving it would send your rider id there.\n\n" +
                        "If you are testing against your own server, turn on developer mode " +
                        "in Settings first.",
                    danger = true,
                )
                Spacer(Modifier.height(18.dp))
                Secondary("Scan something else") { step = Step.Scanning }
                Secondary("Close", onDone)
            }

            is Step.Confirming -> {
                if (!s.link.isProduction) {
                    Banner(
                        "Developer mode",
                        "Pairing with ${s.link.host} instead of the usual server.",
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
                    Hint(
                        "Sign a browser in as you, for crews only.\n\n" +
                            "It will be able to create a crew, join one, leave one and act on " +
                            "members. It cannot upload a ride, rename you, or delete anything " +
                            "— those stay here on the phone."
                    )
                    Spacer(Modifier.height(18.dp))
                    val storeId = deps.storeId()
                    if (storeId.isNullOrBlank()) {
                        Banner(
                            "Not registered with eucstats yet",
                            "Crews are attached to your eucstats rider, and this phone has " +
                                "not registered one. Set that up in Settings → EUC Stats, " +
                                "then scan the code again.",
                            danger = true,
                        )
                        Spacer(Modifier.height(14.dp))
                        Secondary("Close", onDone)
                    } else {
                        Primary("Approve") {
                            step = Step.Sending
                            scope.launch {
                                step = when (val r = deps.api.confirm(s.link, storeId)) {
                                    is PairResult.Ok -> Step.Done
                                    is PairResult.Expired -> Step.Problem(EXPIRED)
                                    is PairResult.RateLimited -> Step.Problem(
                                        "Too many attempts. Wait a minute and try again."
                                    )
                                    is PairResult.Unreachable -> Step.Problem(
                                        "Could not reach ${s.link.host}."
                                    )
                                    is PairResult.Failed -> Step.Problem(
                                        r.detail ?: "The server refused that (${r.code})."
                                    )
                                }
                            }
                        }
                        Secondary("Not me — cancel", onDone)
                    }
                }
            }

            is Step.Sending -> {
                Spacer(Modifier.height(30.dp))
                CircularProgressIndicator()
            }

            is Step.Done -> {
                Spacer(Modifier.height(24.dp))
                Banner("Approved", "The browser is signed in. You can put the phone down.",
                    danger = false)
                Spacer(Modifier.height(18.dp))
                Primary("Done", onDone)
            }

            is Step.Problem -> {
                Banner("That did not work", s.message, danger = true)
                Spacer(Modifier.height(18.dp))
                Secondary("Try again") { step = Step.Scanning }
                Secondary("Close", onDone)
            }
        }
    }
}

private const val EXPIRED =
    "That code has expired. Codes last three minutes — ask the browser for a new one."

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
