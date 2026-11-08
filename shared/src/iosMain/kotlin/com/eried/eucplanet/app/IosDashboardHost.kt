package com.eried.eucplanet.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.NavState
import com.eried.eucplanet.ui.dashboard.DashboardHost
import com.eried.eucplanet.ui.dashboard.DashboardNavigation
import com.eried.eucplanet.ui.dashboard.ExperimentalBannerFlag
import com.eried.eucplanet.ui.theme.appColors
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * The iOS [DashboardHost]. The panels that are still Android code (radar,
 * diagnostics, data sources, voice vocabulary, QR) are not on iOS yet: each
 * closes itself or keeps its space, and docs/IOS_PARITY.md lists them.
 */
internal class IosDashboardHost : DashboardHost {
    private val info = NSBundle.mainBundle.infoDictionary

    override val versionName: String = info?.get("CFBundleShortVersionString") as? String ?: "?"
    override val versionCode: Int = (info?.get("CFBundleVersion") as? String)?.toIntOrNull() ?: 0
    override val gitBranch: String = ""
    override val buildStamp: String = ""
    override val isDebugBuild: Boolean = false
    override val isDevBuild: Boolean = false
    // Spoken commands are not on iOS yet (IosDashboardPorts' NoVoiceCommands).
    override val supportsVoiceCommands: Boolean = false

    // The LICENSE file is not in the iOS bundle yet.
    override fun licenseText(): String = ""

    override fun openUrl(url: String) {
        val target = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any>(), null)
    }

    // The ride camera is Android only, so there is no gallery of its own to open.
    override fun openGallery(video: Boolean): Boolean = false

    // iOS apps do not quit themselves; stopEverything already dropped the wheel.
    override fun exitApp() = Unit
    override fun moveToBackground() = Unit

    override fun linkDropbox() = IosDropboxSync.link()

    // No system back on iOS: the dashboard is the root screen.
    @Composable
    override fun BackHandler(onBack: () -> Unit) = Unit

    // A folder backup is Android's Storage Access Framework; iOS syncs through Dropbox.
    @Composable
    override fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit = remember { {} }

    @Composable
    override fun rememberNavigation(): DashboardNavigation = NoNavigation

    @Composable
    override fun ExperimentalBanner(state: ExperimentalBannerFlag, detectedWheelName: String?, detectedFirmware: String?) = Unit

    @Composable
    override fun RadarMini(targetSide: String, modifier: Modifier, onOpenSettings: () -> Unit) = Unit

    @Composable
    override fun VoiceVocabularyDialog(onDismiss: () -> Unit, languageTag: String) {
        LaunchedEffect(Unit) { onDismiss() }
    }

    @Composable
    override fun DiagnosticsDialog(onDismiss: () -> Unit) {
        LaunchedEffect(Unit) { onDismiss() }
    }

    @Composable
    override fun QrCode(content: String, sizeDp: Int) = Box(Modifier.size(sizeDp.dp))

    @Composable
    override fun AppLogo(modifier: Modifier) = Box(modifier)

    @Composable
    override fun appLogoBackground(): Color = MaterialTheme.appColors.menuBackground

    @Composable
    override fun DataSourcesSheet(speedUnit: String, onDismiss: () -> Unit) {
        LaunchedEffect(Unit) { onDismiss() }
    }
}

/** The navigator is not on iOS yet: never active, no saved route. */
private object NoNavigation : DashboardNavigation {
    override val navState: StateFlow<NavState> = MutableStateFlow(NavState()).asStateFlow()
    override val currentRoute: StateFlow<NavRoute?> = MutableStateFlow<NavRoute?>(null).asStateFlow()
    override fun requestPopup() = Unit
    override fun endNavigation() = Unit
    override fun startCurrentRoute() = Unit
}
