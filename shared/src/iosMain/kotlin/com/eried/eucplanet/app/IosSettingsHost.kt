package com.eried.eucplanet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.hud_setup_hint_iphone
import com.eried.eucplanet.resources.section_radar
import com.eried.eucplanet.resources.section_tpms
import com.eried.eucplanet.resources.voice_commands_bind_desc_iphone
import com.eried.eucplanet.resources.widget_how_to_add_iphone
import com.eried.eucplanet.ui.settings.AlarmViewModel
import com.eried.eucplanet.ui.settings.FlicViewModel
import com.eried.eucplanet.ui.settings.HudSetupCard
import com.eried.eucplanet.ui.settings.PermissionGate
import com.eried.eucplanet.ui.settings.RadarSection
import com.eried.eucplanet.ui.settings.SectionHeader
import com.eried.eucplanet.ui.settings.TpmsSection
import com.eried.eucplanet.ui.settings.SettingsHost
import com.eried.eucplanet.ui.settings.SettingsRow
import com.eried.eucplanet.ui.settings.SettingsViewModel
import com.eried.eucplanet.ui.settings.eucstats.AvatarPicker
import com.eried.eucplanet.ui.settings.eucstats.PickedAvatar
import platform.UIKit.UIActivityViewController
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * The iOS [SettingsHost]. The sections iOS has no counterpart for (the car
 * screen, the phone HUD window, the watches) draw nothing; the HUD section
 * is the shared one, less sending the HUD its app. The backup folder is one
 * picked in the Files app.
 * See docs/IOS_PARITY.md for the split.
 */
internal object IosSettingsHost : SettingsHost {

    override fun openUrl(url: String) {
        val target = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any>(), null)
    }

    override fun linkDropbox() = IosDropboxSync.link()

    // The Files app, at the backup folder.
    override fun openFolder(uri: String) = IosBackupFolders.openInFiles(uri)

    override fun openCrewsPairing() = IosCrews.open(null)

    // A folder picked in the Files app (IosBackupFolders, IosFolderSync).
    override val supportsBackupFolder = true

    // Dropbox and the leaderboard need no folder on the iPhone.
    override val dropboxNeedsFolder = false

    // iOS gives apps no access to the volume buttons.
    override val supportsVolumeKeys = false

    // The watches have no iPhone side planned yet. The external sensors are
    // coming: their section shows muted with a "not yet" tap
    // (platform/PlatformFeatures.ios.kt).
    override val hiddenSections = setOf("watch")

    // Pause and Play/Pause work through the audio session (IosMedia); no app
    // can start another app's music or skip its tracks.
    override val drivesMusicPlayer = false

    // What the iPhone has no counterpart for: no back on the dashboard, no
    // keep-alive service, no picture-in-picture or showing over the lock
    // screen, rotation is the system's, and the headset button opens Siri, not
    // an app. The home screen widget is WidgetKit's (IosWidgets). The notification's quick actions are the Live Activity's
    // buttons here. iOS lets no app set the media volume or fully drive
    // another app's player, so the volume and music automations and the
    // volume actions are left out. Nor does it
    // have Android's separate media, notification and alarm streams for the
    // voice to pick from. The HUD is an Android device that takes its app
    // from an Android phone, the car screen is Android Auto, and no iPhone
    // app draws a window over other apps.
    override val hiddenRows = setOf(
        SettingsRow.BACK_ACTION,
        SettingsRow.KEEP_ALIVE,
        SettingsRow.PICTURE_IN_PICTURE,
        SettingsRow.SHOW_OVER_LOCK_SCREEN,
        SettingsRow.ROTATION_LOCK,
        SettingsRow.VOICE_HEADSET_BUTTON,
        SettingsRow.AUTO_VOLUME,
        SettingsRow.MEDIA_CONTROL,
        SettingsRow.PHONE_VOLUME,
        SettingsRow.VOICE_OUTPUT_CHANNEL,
        SettingsRow.HUD_APP_SEND,
        SettingsRow.CAR_SCREEN,
        SettingsRow.PHONE_HUD_WINDOW,
    )

    // Touch and hold, Edit, Add Widget: iOS's own words.
    override val widgetHowToAdd get() = Res.string.widget_how_to_add_iphone

    // Flic and the HUD: an iPhone app gets no volume keys, and no watch has an iPhone side yet.
    override val voiceCommandsBindDesc get() = Res.string.voice_commands_bind_desc_iphone

    @Composable
    override fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit {
        val callback = rememberUpdatedState(onPicked)
        // The Files app's folder picker; the pick comes back as its bookmark.
        return remember { { IosBackupFolders.pick { uri -> callback.value(uri) } } }
    }

    @Composable
    override fun rememberAudioPicker(onPicked: (uri: String, displayName: String) -> Unit): () -> Unit {
        val callback = rememberUpdatedState(onPicked)
        // The Files picker hands over a copy in the app, which IosHorn checks and keeps.
        return remember { { IosHorn.pick { path, name -> callback.value(path, name) } } }
    }

    // Spoken commands are not on iOS yet, so there is nothing to ask for.
    @Composable
    override fun rememberMicPermission(): PermissionGate {
        // The microphone and speech recognition, both needed to listen. Re-read
        // on every return to the front, as Android re-reads on resume: the
        // rider allows them in Settings and comes back.
        var granted by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(IosSpeechListener.permitted())
        }
        DisposableEffect(Unit) {
            val center = platform.Foundation.NSNotificationCenter.defaultCenter
            val token = center.addObserverForName(
                platform.UIKit.UIApplicationWillEnterForegroundNotification, null,
                platform.Foundation.NSOperationQueue.mainQueue,
            ) { _ -> granted = IosSpeechListener.permitted() }
            onDispose { center.removeObserver(token) }
        }
        // Asked once by iOS; after a refusal only the app's page in Settings
        // can change it, as Android after two refusals.
        return PermissionGate(granted) {
            if (IosSpeechListener.refused()) IosAppHealth.openSettings()
            else IosSpeechListener.ask { ok ->
                granted = ok
                if (ok) IosAppHealth.microphoneGranted()
            }
        }
    }

    @Composable
    override fun alarmViewModel(): AlarmViewModel = rememberViewModel { IosAppGraph.alarmViewModel() }

    override fun engoGlasses(): com.eried.eucplanet.service.hud.engo.EngoGlasses = IosAppGraph.engoGlasses

    @Composable
    override fun flicViewModel(): FlicViewModel = rememberViewModel { FlicViewModel(IosAppGraph.settings, IosFlic) }

    @Composable
    override fun NavigatorSettings(
        settings: AppSettings,
        viewModel: SettingsViewModel,
        scrollToWeather: Boolean,
        onWeatherTop: (Float) -> Unit,
    ) {
        com.eried.eucplanet.ui.settings.NavigatorSettingsContent(
            viewModel = viewModel,
            scrollToWeather = scrollToWeather,
            onWeatherTop = onWeatherTop,
        )
    }

    // The shared row and screen, over the iPhone's downloads and web view.
    @Composable
    override fun OfflineAreas() {
        com.eried.eucplanet.ui.settings.OfflineAreasRow(
            store = IosAppGraph.offlineStore,
            host = IosOfflineDataHost,
            openAtStart = remember { IosLaunchHooks.takeOfflineAreas() },
            dialog = { dismiss ->
                com.eried.eucplanet.ui.settings.offline.OfflineDataDialog(
                    onDismiss = dismiss,
                    viewModel = rememberViewModel { IosAppGraph.offlineDataViewModel() },
                    host = IosOfflineDataHost,
                    offlineMapJs = IosOfflineMap.js,
                )
            },
        )
    }

    // The shared section, as AndroidSettingsHost draws it, over the
    // CoreBluetooth GPS link (IosAppGraph).
    @Composable
    override fun ExternalGpsSettings() {
        com.eried.eucplanet.ui.settings.ExternalGpsSection(
            viewModel = rememberViewModel { IosAppGraph.externalGpsViewModel() },
            settingsViewModel = rememberViewModel { IosAppGraph.settingsViewModel() },
        )
    }

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

    // The HUD section is shared (HudIntegrationSection). An iPhone cannot
    // send the HUD its app, so that card is a hidden row.
    @Composable
    override fun HudAppSettings(viewModel: SettingsViewModel, linked: Boolean) = Unit

    // No app can read whether Personal Hotspot is on, so one line covers both
    // ways onto a network, and the permission iOS asks for the first time.
    @Composable
    override fun HudSetupHint(linked: Boolean) {
        if (linked) return
        HudSetupCard(stringResource(Res.string.hud_setup_hint_iphone))
    }

    // What Personal Hotspot hands its first device (172.20.10.0/28).
    override val hudAddressExample: String = "172.20.10.2"

    @Composable
    override fun CarSettings(settings: AppSettings, viewModel: SettingsViewModel) = Unit

    @Composable
    override fun PhoneHudSection(settings: AppSettings, viewModel: SettingsViewModel) = Unit

    @Composable
    override fun VoiceVocabularyDialog(onDismiss: () -> Unit, languageTag: String) {
        val strings = remember(languageTag) { IosLanguageStrings.forTag(languageTag) }
        com.eried.eucplanet.ui.settings.VoiceVocabularyList(onDismiss = onDismiss, strings = strings)
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
