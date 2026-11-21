package com.eried.eucplanet.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.voice_commands_bind_desc
import com.eried.eucplanet.resources.widget_how_to_add
import com.eried.eucplanet.ui.settings.eucstats.AvatarPicker
import com.eried.eucplanet.ui.settings.eucstats.PickedAvatar
import org.jetbrains.compose.resources.StringResource

/**
 * What SettingsScreen asks of the platform it runs on: the system screens and
 * pickers it opens, and the sections that are still platform code (each with
 * its own view model, or a surface only one phone has). Android's is built in
 * the composition from the Activity (rememberAndroidSettingsHost); iOS has its
 * own, which leaves out what the iPhone cannot do.
 *
 * The section slots draw everything under their heading, the heading too, so a
 * platform without the feature simply draws nothing.
 */
interface SettingsHost {
    /** Opens [url] in the browser; quietly does nothing when nothing can. */
    fun openUrl(url: String)

    /** Starts the Dropbox link flow. */
    fun linkDropbox()

    /** Opens the backup folder [uri] in a file manager, or the platform's file app. */
    fun openFolder(uri: String)

    /** Starts pairing a Crew device (the phone camera scanning its QR code). */
    fun openCrewsPairing()

    /** The volume keys can be bound to actions. */
    val supportsVolumeKeys: Boolean

    /**
     * The music actions can start another app's music and skip its tracks
     * (Start music, Next, Previous). iOS can only pause it and let it resume.
     */
    val drivesMusicPlayer: Boolean get() = true

    /** A backup folder can be picked (Android's document tree, the iPhone's Files folder). */
    val supportsBackupFolder: Boolean

    /**
     * Dropbox is offered only once a backup folder is set, as a mirror of it
     * (Android). The iPhone offers it with or without a folder.
     */
    val dropboxNeedsFolder: Boolean get() = supportsBackupFolder

    /** Keys of the sections this platform leaves out ("watch", "motor"...), for a phone without them. */
    val hiddenSections: Set<String>

    /** Rows inside a shown section that this platform cannot act on, so it leaves them out. */
    val hiddenRows: Set<SettingsRow>

    /** How to put a widget on this phone's home screen, in this phone's own words. */
    val widgetHowToAdd: StringResource
        get() = Res.string.widget_how_to_add

    /**
     * What listening can be bound to, named as this phone has them. Android
     * has the volume keys and the watches; the iPhone has neither.
     */
    val voiceCommandsBindDesc: StringResource get() = Res.string.voice_commands_bind_desc

    /** A folder picker; the returned function opens it. */
    @Composable
    fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit

    /** An audio file picker; the result is the file and its display name. */
    @Composable
    fun rememberAudioPicker(onPicked: (uri: String, displayName: String) -> Unit): () -> Unit

    /** Microphone permission for spoken commands, re-read when the app returns. */
    @Composable
    fun rememberMicPermission(): PermissionGate

    @Composable
    fun alarmViewModel(): AlarmViewModel

    /** The ENGO glasses' Bluetooth link, paired under Integration. */
    fun engoGlasses(): com.eried.eucplanet.service.hud.engo.EngoGlasses

    @Composable
    fun flicViewModel(): FlicViewModel

    /**
     * The Navigation and weather section. A phone without the navigator still
     * draws the shared weather block ([WeatherSettingsContent]).
     */
    @Composable
    fun NavigatorSettings(
        settings: AppSettings,
        viewModel: SettingsViewModel,
        scrollToWeather: Boolean,
        onWeatherTop: (Float) -> Unit,
    )

    /** The Navigation section's "Offline areas" row and the screen it opens. */
    @Composable
    fun OfflineAreas()

    @Composable
    fun ExternalGpsSettings()

    @Composable
    fun RadarSettings()

    @Composable
    fun TpmsSettings()

    /**
     * Sending the HUD its own app from this phone, inside the shared HUD
     * section ([SettingsRow.HUD_APP_SEND]). [linked] while a HUD is paired.
     */
    @Composable
    fun HudAppSettings(viewModel: SettingsViewModel, linked: Boolean)

    /**
     * How to put the HUD and this phone on one network, in this phone's own
     * words. Shown until a HUD links ([linked]).
     */
    @Composable
    fun HudSetupHint(linked: Boolean)

    /** The address a HUD on this phone's hotspot typically gets, as the address field's example. */
    val hudAddressExample: String

    /** This phone's keyboard flags for typing an address, null for its defaults. */
    val addressImeOptions: androidx.compose.ui.text.input.PlatformImeOptions? get() = null

    /** The car screen ([SettingsRow.CAR_SCREEN]). */
    @Composable
    fun CarSettings(settings: AppSettings, viewModel: SettingsViewModel)

    /** A window over other apps that draws an overlay preset. */
    @Composable
    fun PhoneHudSection(settings: AppSettings, viewModel: SettingsViewModel)

    /** "What can I say": the spoken-command list. */
    @Composable
    fun VoiceVocabularyDialog(onDismiss: () -> Unit, languageTag: String)

    /**
     * The leaderboard picture picker: the platform's photo picker and a
     * square crop to 256 px. [onPicked] gets null when a new photo is chosen
     * (the previous crop is dropped) and the cropped picture once applied.
     */
    @Composable
    fun rememberAvatarPicker(onPicked: (PickedAvatar?) -> Unit): AvatarPicker

    /** Hands [json] to the share sheet, as the leaderboard's "export my data". */
    fun shareJson(json: String, subject: String)
}

/**
 * A row (or block of rows) in a section both phones show that only some
 * platforms can act on. A platform lists the ones it lacks in
 * [SettingsHost.hiddenRows] and the screen draws nothing for them, so no
 * switch sits there doing nothing.
 */
enum class SettingsRow {
    /** What back on the dashboard does; a phone with no back there has nothing to choose. */
    BACK_ACTION,

    /** The keep-alive notification that holds the app open with no wheel. */
    KEEP_ALIVE,

    /** The rider's quick actions on the ride notification. */
    NOTIFICATION_ACTIONS,

    /** The picture-in-picture window. */
    PICTURE_IN_PICTURE,

    /** The home-screen widget. */
    HOME_WIDGET,

    /** Showing the dashboard over the lock screen. */
    SHOW_OVER_LOCK_SCREEN,

    /** The upside-down lockout and ignoring the system rotation lock. */
    ROTATION_LOCK,

    /** Spoken commands: the microphone, the cues and the headset button. */
    VOICE_COMMANDS,

    /**
     * The headset button opening the microphone: the app as the phone's
     * assistant, which only Android lets an app be (an iPhone's is Siri's).
     */
    VOICE_HEADSET_BUTTON,

    /** The phone's media volume following the speed (Automations). */
    AUTO_VOLUME,

    /** Pausing and resuming other apps' music by speed (Automations). */
    MEDIA_CONTROL,

    /** The actions that set the phone's media volume: Volume up, Volume down and Mute. */
    PHONE_VOLUME,

    /** Which system stream the voice speaks on (media, notification or alarm). */
    VOICE_OUTPUT_CHANNEL,

    /** Offline routing: preferring the downloaded areas, their server, the areas and the reminder before a ride. */
    OFFLINE_ROUTING,

    /** Sending the HUD its own app, and the card that shows its version. */
    HUD_APP_SEND,

    /** The car screen: its alarm notifications. */
    CAR_SCREEN,

    /** The phone HUD: a window over other apps that draws an overlay preset. */
    PHONE_HUD_WINDOW,

    /** Binding actions to the volume keys. Follows [SettingsHost.supportsVolumeKeys]; not listed in hiddenRows. */
    VOLUME_KEYS,

    /** Choosing a backup folder. Follows [SettingsHost.supportsBackupFolder]; not listed in hiddenRows. */
    BACKUP_FOLDER,
}

/**
 * Every row this platform leaves out, its host switches included. The
 * settings search reads it, so a row the screen does not draw is not found.
 */
val SettingsHost.absentRows: Set<SettingsRow>
    get() = hiddenRows + buildSet {
        if (!supportsVolumeKeys) add(SettingsRow.VOLUME_KEYS)
        if (!supportsBackupFolder) add(SettingsRow.BACKUP_FOLDER)
    }

/** A permission the rider grants outside the app. */
class PermissionGate(val granted: Boolean, val request: () -> Unit)

/** The host of the SettingsScreen being drawn, for the sections deep inside it. */
val LocalSettingsHost = staticCompositionLocalOf<SettingsHost> {
    error("SettingsScreen provides LocalSettingsHost")
}
