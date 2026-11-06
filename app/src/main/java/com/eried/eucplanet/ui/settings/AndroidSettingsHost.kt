package com.eried.eucplanet.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eried.eucplanet.data.eucstats.RiderCard
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.DropboxRepository
import com.eried.eucplanet.data.repository.startLinkFlow
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** The Android [SettingsHost], over the Activity the settings screen sits in. */
@Composable
fun rememberAndroidSettingsHost(): SettingsHost {
    val context = LocalContext.current
    val dropbox: DropboxRepository = koinInject()
    return remember(context) { AndroidSettingsHost(context, dropbox) }
}

private class AndroidSettingsHost(
    private val context: Context,
    private val dropbox: DropboxRepository,
) : SettingsHost {

    override fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Throwable) { /* no browser installed */ }
    }

    // The Custom Tab needs the Activity, which is this context.
    override fun linkDropbox() = dropbox.startLinkFlow(context)

    override fun openFolder(uri: String) {
        val treeUri = android.net.Uri.parse(uri)
        // The raw tree URI lands in the DocumentsUI root for most
        // browsers. Convert to a document URI that points at the
        // tree's own document id so the browser opens *inside* the
        // rider's chosen folder rather than its provider root.
        val docUri = runCatching {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        }.getOrNull() ?: treeUri
        val openIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(
                docUri,
                android.provider.DocumentsContract.Document.MIME_TYPE_DIR
            )
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        runCatching { context.startActivity(openIntent) }
            .onFailure {
                // Fallback for ROMs whose Files app doesn't handle the
                // document URI directly, try the raw tree URI as a
                // last resort. Some browsers still land at the root,
                // but at least the click does something visible.
                val fallback = android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(treeUri, "*/*")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(fallback) }
            }
    }

    // eucstats told riders whose app had not opened to come here and type the
    // code; the pairing screen scans it instead.
    override fun openCrewsPairing() {
        context.startActivity(Intent(context, com.eried.eucplanet.crews.CrewsPairActivity::class.java))
    }

    override val supportsVolumeKeys = true
    override val supportsBackupFolder = true
    override val hiddenSections = emptySet<String>()

    @Composable
    override fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri -> if (uri != null) onPicked(uri.toString()) }
        return { launcher.launch(null) }
    }

    @Composable
    override fun rememberAudioPicker(onPicked: (uri: String, displayName: String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment.orEmpty()
            onPicked(uri.toString(), name)
        }
        return { launcher.launch(arrayOf("audio/*")) }
    }

    // Re-read on resume: the rider grants it outside the app and comes back, so
    // a value captured once would be stale exactly when it matters.
    @Composable
    override fun rememberMicPermission(): PermissionGate {
        fun granted() = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        var micGranted by remember { mutableStateOf(granted()) }
        val lifecycle = LocalLifecycleOwner.current
        DisposableEffect(lifecycle) {
            val obs = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) micGranted = granted()
            }
            lifecycle.lifecycle.addObserver(obs)
            onDispose { lifecycle.lifecycle.removeObserver(obs) }
        }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { ok -> micGranted = ok }
        return PermissionGate(micGranted) { launcher.launch(android.Manifest.permission.RECORD_AUDIO) }
    }

    @Composable
    override fun alarmViewModel(): AlarmViewModel = koinViewModel()

    @Composable
    override fun flicViewModel(): FlicViewModel = koinViewModel()

    @Composable
    override fun Automations() {
        AutomationsContent()
    }

    @Composable
    override fun NavigatorSettings(scrollToWeather: Boolean, onWeatherTop: (Float) -> Unit) {
        NavigatorSettingsContent(scrollToWeather = scrollToWeather, onWeatherTop = onWeatherTop)
    }

    @Composable
    override fun ExternalGpsSettings() {
        ExternalGpsSection()
    }

    @Composable
    override fun RadarSettings() {
        SectionHeader(stringResource(Res.string.section_radar))
        RadarSection()
    }

    @Composable
    override fun TpmsSettings() {
        SectionHeader(stringResource(Res.string.section_tpms))
        TpmsSection()
    }

    @Composable
    override fun HudAndCarSettings(settings: AppSettings, viewModel: SettingsViewModel) {
        HudIntegrationSection(settings = settings, viewModel = viewModel)
        AndroidAutoSection(settings = settings, viewModel = viewModel)
    }

    @Composable
    override fun PhoneHudSection(settings: AppSettings, viewModel: SettingsViewModel) {
        com.eried.eucplanet.ui.settings.PhoneHudSection(settings, viewModel)
    }

    @Composable
    override fun VoiceVocabularyDialog(onDismiss: () -> Unit, languageTag: String) {
        com.eried.eucplanet.ui.settings.VoiceVocabularyDialog(onDismiss = onDismiss, languageTag = languageTag)
    }

    @Composable
    override fun LeaderboardProfileCard(card: RiderCard) {
        com.eried.eucplanet.ui.settings.eucstats.LeaderboardProfileCard(card)
    }

    @Composable
    override fun OnlineUploadOnboardingDialog(viewModel: SettingsViewModel, onDismiss: () -> Unit, onRegistered: () -> Unit) {
        com.eried.eucplanet.ui.settings.eucstats.OnlineUploadOnboardingDialog(
            onDismiss = onDismiss,
            onRegistered = onRegistered,
            viewModel = viewModel,
        )
    }

    @Composable
    override fun OnlineProfileDialog(viewModel: SettingsViewModel, onDismiss: () -> Unit) {
        com.eried.eucplanet.ui.settings.eucstats.OnlineProfileDialog(onDismiss = onDismiss, viewModel = viewModel)
    }
}
