package com.eried.eucplanet.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.eried.eucplanet.crews.CrewsPairDeps
import com.eried.eucplanet.crews.CrewsPairHost
import com.eried.eucplanet.crews.PairApi
import com.eried.eucplanet.crews.PairLink
import com.eried.eucplanet.platform.HttpClient
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.theme.appColors
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIView

/**
 * What the Swift camera bridge (iosApp/QrScannerBridge.swift) does when asked,
 * as LocationSdk does for CoreLocation.
 */
interface QrCameraSdk {
    /** False on a simulator, or a phone whose camera is restricted. */
    fun cameraAvailable(): Boolean
    /** 0 not asked yet, 1 refused, 2 allowed. */
    fun authorization(): Int
    fun requestAccess(onResult: (Boolean) -> Unit)
    /** A live back-camera preview that reports each QR it reads. */
    fun makeScannerView(onCode: (String) -> Unit): UIView
    fun stopScannerView(view: UIView)
}

/**
 * The crew pass on the iPhone: Android's CrewsPairActivity is a screen on top
 * of whatever is showing, opened by an eucplanet://pair link or from Settings.
 */
object IosCrews {
    var camera: QrCameraSdk? = null

    /** One opening of the pass, and the link it came with, if any. */
    class Request(val link: PairLink?, val id: Int)

    private var count = 0
    private val _request = MutableStateFlow<Request?>(null)
    internal val request: StateFlow<Request?> = _request.asStateFlow()

    private val _openLeaderboard = MutableStateFlow(0)
    /** Bumped when the pass sends the rider to the leaderboard settings. */
    internal val openLeaderboard: StateFlow<Int> = _openLeaderboard.asStateFlow()

    internal fun open(link: PairLink?) {
        _request.value = Request(link, ++count)
    }

    internal fun close() {
        _request.value = null
    }

    /**
     * An URL the app was opened with (AppDelegate). An eucplanet://pair link
     * opens the pass, on its code when it parses and on the scanner when not,
     * as the Android activity does. Returns whether it was ours.
     */
    fun handleUrl(url: String): Boolean {
        if (!url.lowercase().startsWith("eucplanet://pair")) return false
        open(PairLink.parse(url))
        return true
    }

    internal val deps: CrewsPairDeps by lazy {
        CrewsPairDeps(
            api = PairApi(HttpClient()),
            storeIdProvider = { IosEucStats.riderStoreId.value },
            // Read when the screen opens: the rider may have just turned it on.
            developerMode = { IosAppGraph.settings.current.value.crewsDevServerEnabled },
        )
    }

    internal fun requestLeaderboardSettings() {
        _openLeaderboard.value++
    }
}

/** The pass's platform side on the iPhone. */
internal object IosCrewsHost : CrewsPairHost {

    // ShareLinks.BASE: the live-location link, which is Android only for now.
    override fun isShareLink(text: String) = text.trim().startsWith("https://eucplanet.ried.no/share")

    override fun openLeaderboardSettings() = IosCrews.requestLeaderboardSettings()

    @OptIn(ExperimentalForeignApi::class)
    @Composable
    override fun QrScanner(
        parse: (String) -> PairLink?,
        onFound: (PairLink) -> Unit,
        invalidText: String,
        modifier: Modifier,
        misfit: (String) -> String?,
    ) {
        val sdk = IosCrews.camera
        val available = remember { sdk?.cameraAvailable() == true }
        var auth by remember { mutableStateOf(sdk?.authorization() ?: 1) }
        val latestFound by rememberUpdatedState(onFound)
        val latestParse by rememberUpdatedState(parse)
        val latestMisfit by rememberUpdatedState(misfit)
        var handled by remember { mutableStateOf(false) }
        var lastInvalidMs by remember { mutableStateOf(0L) }
        var invalidNote by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(lastInvalidMs) {
            if (lastInvalidMs == 0L) return@LaunchedEffect
            delay(2_500)
            invalidNote = null
        }

        Column(modifier) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.appColors.surfaceVariant)
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    sdk == null || !available -> CameraIcon()
                    auth == 2 -> UIKitView(
                        factory = {
                            sdk.makeScannerView { text ->
                                if (handled) return@makeScannerView
                                val link = latestParse(text)
                                if (link != null) {
                                    handled = true
                                    latestFound(link)
                                } else {
                                    invalidNote = latestMisfit(text) ?: invalidText
                                    lastInvalidMs = System.currentTimeMillis()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { sdk.stopScannerView(it) },
                    )
                    else -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        CameraIcon()
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = {
                                if (auth == 0) sdk.requestAccess { ok -> auth = if (ok) 2 else 1 }
                                else NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
                                    UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.appColors.outline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.appColors.textButton,
                            ),
                        ) { Text(stringResource(Res.string.share_camera_allow), maxLines = 1) }
                    }
                }
            }
            invalidNote?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.statusDanger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    @Composable
    private fun CameraIcon() = Icon(
        Icons.Default.PhotoCamera,
        contentDescription = stringResource(Res.string.share_camera_needed),
        tint = MaterialTheme.appColors.textSecondary,
        modifier = Modifier.size(40.dp),
    )
}
