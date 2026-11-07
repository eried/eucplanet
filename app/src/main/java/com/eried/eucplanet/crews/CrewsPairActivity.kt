package com.eried.eucplanet.crews

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.eried.eucplanet.share.ShareLinks
import com.eried.eucplanet.ui.navigator.QrScannerArea
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import com.eried.eucplanet.ui.theme.appColors
import org.koin.android.ext.android.get

/**
 * Crews pairing: scan a code from a browser, see what it would grant, approve it.
 *
 * This is the entire job of the phone in Crews. Everything else (founding a crew, joining
 * one, approving members, looking at territory) happens in a browser on eucstats. The app is
 * here because it is the only thing that holds the rider's store_id, and the point of the
 * handshake is that the store_id never has to be typed into a web form.
 *
 * Its own activity rather than a destination in the nav graph, because a deep link needs an
 * activity to land on and because pairing is a short errand the rider arrives at from outside
 * the app as often as from inside it.
 */
class CrewsPairActivity : ComponentActivity() {

    val deps: CrewsPairDeps = get()

    /**
     * The link currently being acted on, as state rather than read once.
     *
     * This activity is singleTop, so a second pairing link while it is already open arrives
     * through [onNewIntent] rather than building a new activity. Reading the intent once in
     * onCreate meant that second scan changed nothing on screen: the rider was left looking
     * at the previous code with no sign anything had happened.
     */
    private val link = mutableStateOf<PairLink?>(null)

    private val host = object : CrewsPairHost {
        @Composable
        override fun QrScanner(
            parse: (String) -> PairLink?,
            onFound: (PairLink) -> Unit,
            invalidText: String,
            modifier: Modifier,
            misfit: (String) -> String?,
        ) = QrScannerArea(parse, onFound, invalidText, modifier, misfit)

        override fun isShareLink(text: String) = ShareLinks.parse(text) != null

        // The pass is its own activity with no nav controller, so it starts MainActivity,
        // which navigates once the graph exists (LeaderboardSetupLaunch).
        override fun openLeaderboardSettings() {
            runCatching {
                startActivity(
                    Intent(this@CrewsPairActivity, Class.forName("com.eried.eucplanet.MainActivity"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(
                            com.eried.eucplanet.ui.settings.LeaderboardSetupLaunch.EXTRA_OPEN_LEADERBOARD,
                            true,
                        )
                )
            }
        }
    }

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
                        host = host,
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

