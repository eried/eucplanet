package com.eried.eucplanet.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.platform.PlatformFeature
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.section_radar
import com.eried.eucplanet.resources.section_tpms
import com.eried.eucplanet.ui.common.NotYetSettingsRow
import com.eried.eucplanet.ui.settings.AlarmViewModel
import com.eried.eucplanet.ui.settings.FlicViewModel
import com.eried.eucplanet.ui.settings.PermissionGate
import com.eried.eucplanet.ui.settings.RadarSection
import com.eried.eucplanet.ui.settings.SectionHeader
import com.eried.eucplanet.ui.settings.TpmsSection
import com.eried.eucplanet.ui.settings.SettingsHost
import com.eried.eucplanet.ui.settings.SettingsRow
import com.eried.eucplanet.ui.settings.SettingsViewModel
import com.eried.eucplanet.ui.settings.WeatherSettingsContent
import com.eried.eucplanet.ui.settings.eucstats.AvatarPicker
import com.eried.eucplanet.ui.settings.eucstats.PickedAvatar
import platform.UIKit.UIActivityViewController
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * The iOS [SettingsHost]. The sections iOS has no counterpart for (the HUD,
 * the car screen, the phone HUD window, the watches, the folder backups) draw
 * nothing. See docs/IOS_PARITY.md for the split.
 */
internal object IosSettingsHost : SettingsHost {

    override fun openUrl(url: String) {
        val target = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any>(), null)
    }

    override fun linkDropbox() = IosDropboxSync.link()

    // No backup folder on iOS: Dropbox is the only sync.
    override fun openFolder(uri: String) = Unit

    override fun openCrewsPairing() = IosCrews.open(null)

    // The settings, alarms and themes sync through Dropbox; no folder here yet.
    override val supportsBackupFolder = false

    // iOS gives apps no access to the volume buttons.
    override val supportsVolumeKeys = false

    // The watches have no iPhone side planned yet. The navigator and the
    // external sensors are coming: their sections show muted with a "not
    // yet" tap (platform/PlatformFeatures.ios.kt).
    override val hiddenSections = setOf("watch")

    // Pause and Play/Pause work through the audio session (IosMedia); no app
    // can start another app's music or skip its tracks.
    override val drivesMusicPlayer = false

    // What the iPhone has no counterpart for: no back on the dashboard, no
    // keep-alive service, no picture-in-picture, home widget or showing over
    // the lock screen, rotation is the system's; and spoken commands are not
    // on iOS yet. The notification's quick actions are the Live Activity's
    // buttons here. iOS lets no app set the media volume or fully drive
    // another app's player, so the volume and music automations and the
    // volume actions are left out. Nor does it
    // have Android's separate media, notification and alarm streams for the
    // voice to pick from.
    override val hiddenRows = setOf(
        SettingsRow.BACK_ACTION,
        SettingsRow.KEEP_ALIVE,
        SettingsRow.PICTURE_IN_PICTURE,
        SettingsRow.HOME_WIDGET,
        SettingsRow.SHOW_OVER_LOCK_SCREEN,
        SettingsRow.ROTATION_LOCK,
        SettingsRow.VOICE_COMMANDS,
        SettingsRow.AUTO_VOLUME,
        SettingsRow.MEDIA_CONTROL,
        SettingsRow.PHONE_VOLUME,
        SettingsRow.VOICE_OUTPUT_CHANNEL,
    )

    @Composable
    override fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit = {}

    @Composable
    override fun rememberAudioPicker(onPicked: (uri: String, displayName: String) -> Unit): () -> Unit {
        val callback = rememberUpdatedState(onPicked)
        // The Files picker hands over a copy in the app, which IosHorn checks and keeps.
        return remember { { IosHorn.pick { path, name -> callback.value(path, name) } } }
    }

    // Spoken commands are not on iOS yet, so there is nothing to ask for.
    @Composable
    override fun rememberMicPermission(): PermissionGate = PermissionGate(true) {}

    @Composable
    override fun alarmViewModel(): AlarmViewModel = rememberViewModel { IosAppGraph.alarmViewModel() }

    @Composable
    override fun flicViewModel(): FlicViewModel = rememberViewModel { FlicViewModel(IosAppGraph.settings, IosFlic) }

    @Composable
    override fun NavigatorSettings(
        settings: AppSettings,
        viewModel: SettingsViewModel,
        scrollToWeather: Boolean,
        onWeatherTop: (Float) -> Unit,
    ) {
        // The navigator is not on iPhone yet; the weather is, so its block
        // shows under a muted navigation row, as on Android under the routing.
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NotYetSettingsRow(PlatformFeature.NAVIGATION)
            WeatherSettingsContent(settings, viewModel, scrollToWeather, onWeatherTop)
        }
    }

    @Composable
    override fun ExternalGpsSettings() = Unit

    // The shared sections, as AndroidSettingsHost draws them, over the
    // CoreBluetooth radar link and tyre radio (IosAppGraph).
    @Composable
    override fun RadarSettings() {
        SectionHeader(stringResource(Res.string.section_radar))
        RadarSection(
            viewModel = rememberViewModel { IosAppGraph.radarViewModel() },
            settingsViewModel = rememberViewModel { IosAppGraph.settingsViewModel() },
        )
    }

    @Composable
    override fun TpmsSettings() {
        SectionHeader(stringResource(Res.string.section_tpms))
        TpmsSection(viewModel = rememberViewModel { IosAppGraph.tpmsViewModel() })
    }

    @Composable
    override fun HudAndCarSettings(settings: AppSettings, viewModel: SettingsViewModel) = Unit

    @Composable
    override fun PhoneHudSection(settings: AppSettings, viewModel: SettingsViewModel) = Unit

    @Composable
    override fun VoiceVocabularyDialog(onDismiss: () -> Unit, languageTag: String) {
        LaunchedEffect(Unit) { onDismiss() }
    }

    @Composable
    override fun rememberAvatarPicker(onPicked: (PickedAvatar?) -> Unit): AvatarPicker {
        val callback = rememberUpdatedState(onPicked)
        // The photo library's own square crop runs inside its sheet, so the
        // dialog that asked never has to step aside for it.
        return remember { AvatarPicker(launch = { IosAvatarPicker.open { callback.value(it) } }, cropping = false) }
    }

    override fun shareJson(json: String, subject: String) {
        val top = topViewController() ?: return
        val sheet = UIActivityViewController(activityItems = listOf(json), applicationActivities = null)
        top.presentViewController(sheet, animated = true, completion = null)
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
