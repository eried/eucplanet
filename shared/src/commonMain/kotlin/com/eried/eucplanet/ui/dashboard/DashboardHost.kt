package com.eried.eucplanet.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.NavState
import kotlinx.coroutines.flow.StateFlow

/**
 * What DashboardScreen asks of the platform it runs on: the build it is,
 * the system screens it opens, and the few panels that are still
 * platform code. Android's is built in the composition from the Activity
 * (rememberAndroidDashboardHost); iOS has its own.
 */
interface DashboardHost {
    val versionName: String
    val versionCode: Int
    /** The branch the build came from; empty when unknown. */
    val gitBranch: String
    val buildStamp: String
    val isDebugBuild: Boolean
    /** A branch build with the developer tools in the welcome tour. */
    val isDevBuild: Boolean

    /** The LICENSE file as shipped, hard-wrapped; empty when missing. */
    fun licenseText(): String

    /** Opens [url] in the browser; quietly does nothing when nothing can. */
    fun openUrl(url: String)

    /** Opens the phone's gallery at its videos or photos; false when there is none. */
    fun openGallery(video: Boolean): Boolean

    /** Ends the app after DashboardViewModel.stopEverything. */
    fun exitApp()

    /** Sends the app to the background, leaving everything running. */
    fun moveToBackground()

    /** Starts the Dropbox link flow. */
    fun linkDropbox()

    /** The system back gesture or button, while the dashboard shows. */
    @Composable
    fun BackHandler(onBack: () -> Unit)

    /** A folder picker; the returned function opens it. */
    @Composable
    fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit

    /** The navigator's state, for the dashboard's map button. */
    @Composable
    fun rememberNavigation(): DashboardNavigation

    @Composable
    fun ExperimentalBanner(state: ExperimentalBannerFlag, detectedWheelName: String?, detectedFirmware: String?)

    /** The rear radar's mini view on one side of the dial. */
    @Composable
    fun RadarMini(targetSide: String, modifier: Modifier, onOpenSettings: () -> Unit)

    /** "What can I say": the spoken-command list. */
    @Composable
    fun VoiceVocabularyDialog(onDismiss: () -> Unit, languageTag: String)

    /** Service mode: the wheel diagnostics. */
    @Composable
    fun DiagnosticsDialog(onDismiss: () -> Unit)

    @Composable
    fun QrCode(content: String, sizeDp: Int)

    /** The launcher artwork, for the About panel. */
    @Composable
    fun AppLogo(modifier: Modifier)

    /** The launcher icon's backdrop colour, behind [AppLogo]. */
    @Composable
    fun appLogoBackground(): Color

    /** Where each live value comes from. */
    @Composable
    fun DataSourcesSheet(speedUnit: String, onDismiss: () -> Unit)
}

/** The navigator as the dashboard's map button sees it. */
interface DashboardNavigation {
    val navState: StateFlow<NavState>
    val currentRoute: StateFlow<NavRoute?>
    fun requestPopup()
    fun endNavigation()
    fun startCurrentRoute()
}
