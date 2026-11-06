package com.eried.eucplanet.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.eried.eucplanet.data.eucstats.RiderCard
import com.eried.eucplanet.data.model.AppSettings

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

    @Composable
    fun LeaderboardProfileCard(card: RiderCard)

    /** Joining the online leaderboard: name, flag and avatar. */
    @Composable
    fun OnlineUploadOnboardingDialog(viewModel: SettingsViewModel, onDismiss: () -> Unit, onRegistered: () -> Unit)

    /** The rider's leaderboard profile, to edit or delete. */
    @Composable
    fun OnlineProfileDialog(viewModel: SettingsViewModel, onDismiss: () -> Unit)
}

/** A permission the rider grants outside the app. */
class PermissionGate(val granted: Boolean, val request: () -> Unit)

/** The host of the SettingsScreen being drawn, for the sections deep inside it. */
val LocalSettingsHost = staticCompositionLocalOf<SettingsHost> {
    error("SettingsScreen provides LocalSettingsHost")
}
