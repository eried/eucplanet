package com.eried.eucplanet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eried.eucplanet.data.eucstats.RiderCard
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.ui.settings.AlarmViewModel
import com.eried.eucplanet.ui.settings.FlicViewModel
import com.eried.eucplanet.ui.settings.PermissionGate
import com.eried.eucplanet.ui.settings.SettingsHost
import com.eried.eucplanet.ui.settings.SettingsViewModel
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * The iOS [SettingsHost]. The sections iOS has no counterpart for (the HUD,
 * the car screen, the phone HUD window, watches' radar and tyre sensors, the
 * folder backups) draw nothing; see docs/IOS_PARITY.md for what joins later.
 */
internal object IosSettingsHost : SettingsHost {

    override fun openUrl(url: String) {
        val target = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any>(), null)
    }

    override fun linkDropbox() = IosDropboxSync.link()

    // No backup folder on iOS: Dropbox is the only sync.
    override fun openFolder(uri: String) = Unit

    override fun openCrewsPairing() = Unit

    // The settings, alarms and themes sync through Dropbox; no folder here yet.
    override val supportsBackupFolder = false

    // iOS gives apps no access to the volume buttons.
    override val supportsVolumeKeys = false

    // Their content is platform code still on Android only: the automations,
    // the navigator, the external sensors, the engine sound and the watches.
    override val hiddenSections = setOf("auto", "navigator", "location", "motor", "watch")

    @Composable
    override fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit = {}

    @Composable
    override fun rememberAudioPicker(onPicked: (uri: String, displayName: String) -> Unit): () -> Unit = {}

    // Spoken commands are not on iOS yet, so there is nothing to ask for.
    @Composable
    override fun rememberMicPermission(): PermissionGate = PermissionGate(true) {}

    @Composable
    override fun alarmViewModel(): AlarmViewModel = rememberViewModel { IosAppGraph.alarmViewModel() }

    @Composable
    override fun flicViewModel(): FlicViewModel = rememberViewModel { FlicViewModel(IosAppGraph.settings, IosFlic) }

    @Composable
    override fun Automations() = Unit

    @Composable
    override fun NavigatorSettings(scrollToWeather: Boolean, onWeatherTop: (Float) -> Unit) = Unit

    @Composable
    override fun ExternalGpsSettings() = Unit

    @Composable
    override fun RadarSettings() = Unit

    @Composable
    override fun TpmsSettings() = Unit

    @Composable
    override fun HudAndCarSettings(settings: AppSettings, viewModel: SettingsViewModel) = Unit

    @Composable
    override fun PhoneHudSection(settings: AppSettings, viewModel: SettingsViewModel) = Unit

    @Composable
    override fun VoiceVocabularyDialog(onDismiss: () -> Unit, languageTag: String) {
        LaunchedEffect(Unit) { onDismiss() }
    }

    @Composable
    override fun LeaderboardProfileCard(card: RiderCard) = Unit

    @Composable
    override fun OnlineUploadOnboardingDialog(viewModel: SettingsViewModel, onDismiss: () -> Unit, onRegistered: () -> Unit) {
        LaunchedEffect(Unit) { onDismiss() }
    }

    @Composable
    override fun OnlineProfileDialog(viewModel: SettingsViewModel, onDismiss: () -> Unit) {
        LaunchedEffect(Unit) { onDismiss() }
    }
}

/** A view model kept for as long as the composable that asked for it. */
@Composable
internal inline fun <reified T : ViewModel> rememberViewModel(crossinline create: () -> T): T {
    val store = remember { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    return remember(store) {
        ViewModelProvider.create(store, viewModelFactory { initializer { create() } })[T::class]
    }
}
