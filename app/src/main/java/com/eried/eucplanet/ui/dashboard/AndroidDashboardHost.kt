package com.eried.eucplanet.ui.dashboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.eried.eucplanet.BuildConfig
import com.eried.eucplanet.R
import com.eried.eucplanet.data.repository.startLinkFlow
import com.eried.eucplanet.ui.navigator.NavigationOverlayViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** The Android [DashboardHost], over the Activity the dashboard sits in. */
@Composable
fun rememberAndroidDashboardHost(): DashboardHost {
    val context = LocalContext.current
    val dropbox: com.eried.eucplanet.data.repository.DropboxRepository = koinInject()
    return remember(context) { AndroidDashboardHost(context, dropbox) }
}

private class AndroidDashboardHost(
    private val context: Context,
    private val dropbox: com.eried.eucplanet.data.repository.DropboxRepository,
) : DashboardHost {

    override val versionName: String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }

    override val versionCode: Int =
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (_: Exception) { 0 }

    override val gitBranch: String = BuildConfig.GIT_BRANCH
    override val buildStamp: String = BuildConfig.BUILD_STAMP
    override val isDebugBuild: Boolean = BuildConfig.DEBUG
    override val isDevBuild: Boolean = BuildConfig.IS_DEV

    override fun licenseText(): String =
        try {
            context.resources.openRawResource(R.raw.license)
                .bufferedReader().use { it.readText() }
        } catch (_: Exception) { "" }

    override fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    override fun openGallery(video: Boolean): Boolean {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(collection, if (video) "video/*" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    // finishAndRemoveTask, not finish(): finish() alone leaves the task in
    // recents, and the rider takes the card still there for a failed exit.
    override fun exitApp() {
        val act = context as? Activity
        if (act != null) {
            act.finishAndRemoveTask()
        } else {
            Log.w("Dashboard", "Stop all: no Activity reference, exit may be incomplete")
        }
    }

    override fun moveToBackground() {
        (context as? Activity)?.moveTaskToBack(true)
    }

    // The Custom Tab needs the Activity, which is this context.
    override fun linkDropbox() = dropbox.startLinkFlow(context)

    @Composable
    override fun BackHandler(onBack: () -> Unit) =
        androidx.activity.compose.BackHandler(onBack = onBack)

    @Composable
    override fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri -> if (uri != null) onPicked(uri.toString()) }
        return { launcher.launch(null) }
    }

    @Composable
    override fun rememberNavigation(): DashboardNavigation {
        val vm: NavigationOverlayViewModel = koinViewModel()
        return remember(vm) {
            object : DashboardNavigation {
                override val navState = vm.navState
                override val currentRoute = vm.currentRoute
                override fun requestPopup() = vm.requestPopup()
                override fun endNavigation() = vm.endNavigation()
                override fun startCurrentRoute() = vm.startCurrentRoute()
            }
        }
    }

    @Composable
    override fun ExperimentalBanner(
        state: ExperimentalBannerFlag,
        detectedWheelName: String?,
        detectedFirmware: String?,
    ) = com.eried.eucplanet.ui.common.ExperimentalBanner(
        state = state,
        detectedWheelName = detectedWheelName,
        detectedFirmware = detectedFirmware,
    )

    @Composable
    override fun RadarMini(targetSide: String, modifier: Modifier, onOpenSettings: () -> Unit) =
        DashboardRadarMiniForSide(targetSide = targetSide, modifier = modifier, onOpenSettings = onOpenSettings)

    @Composable
    override fun VoiceVocabularyDialog(onDismiss: () -> Unit, languageTag: String) =
        com.eried.eucplanet.ui.settings.VoiceVocabularyDialog(onDismiss = onDismiss, languageTag = languageTag)

    @Composable
    override fun DiagnosticsDialog(onDismiss: () -> Unit) =
        com.eried.eucplanet.diagnostics.WheelDiagnosticsDialog(onDismiss = onDismiss)

    @Composable
    override fun QrCode(content: String, sizeDp: Int) = QrCodeImage(content = content, sizeDp = sizeDp)

    @Composable
    override fun AppLogo(modifier: Modifier) = androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = modifier,
    )

    @Composable
    override fun appLogoBackground(): androidx.compose.ui.graphics.Color =
        androidx.compose.ui.res.colorResource(R.color.ic_launcher_background)

    @Composable
    override fun DataSourcesSheet(speedUnit: String, onDismiss: () -> Unit) =
        com.eried.eucplanet.ui.dashboard.sources.DataSourcesSheet(speedUnit = speedUnit, onDismiss = onDismiss)
}
