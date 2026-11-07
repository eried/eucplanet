package com.eried.eucplanet.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.ui.settings.eucstats.AvatarPicker
import com.eried.eucplanet.ui.settings.eucstats.PickedAvatar

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

    /** A backup folder can be picked (Android's document tree); without one, Dropbox is the backup. */
    val supportsBackupFolder: Boolean

    /** Keys of the sections this platform leaves out ("watch", "motor"...), for a phone without them. */
    val hiddenSections: Set<String>

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

    @Composable
    fun flicViewModel(): FlicViewModel

    @Composable
    fun Automations()

    @Composable
    fun NavigatorSettings(scrollToWeather: Boolean, onWeatherTop: (Float) -> Unit)

    @Composable
    fun ExternalGpsSettings()

    @Composable
    fun RadarSettings()

    @Composable
    fun TpmsSettings()

    /** The companion HUD and the car screen. */
    @Composable
    fun HudAndCarSettings(settings: AppSettings, viewModel: SettingsViewModel)

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

/** A permission the rider grants outside the app. */
class PermissionGate(val granted: Boolean, val request: () -> Unit)

/** The host of the SettingsScreen being drawn, for the sections deep inside it. */
val LocalSettingsHost = staticCompositionLocalOf<SettingsHost> {
    error("SettingsScreen provides LocalSettingsHost")
}
