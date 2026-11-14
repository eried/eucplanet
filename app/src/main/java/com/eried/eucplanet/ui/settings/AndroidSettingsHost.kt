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
import androidx.compose.ui.graphics.asImageBitmap
import com.eried.eucplanet.ui.navigator.UserMarkerCropDialog
import com.eried.eucplanet.ui.navigator.decodeDownsampledBitmap
import com.eried.eucplanet.ui.settings.eucstats.AvatarPicker
import com.eried.eucplanet.ui.settings.eucstats.PickedAvatar
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
    override val hiddenRows = emptySet<SettingsRow>()

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
        var micAskedAt by remember { mutableStateOf(0L) }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { ok ->
            micGranted = ok
            // After two refusals Android stops showing the prompt and answers
            // no at once, which left this button doing nothing. An answer that
            // fast was not the rider's, so take them to the app's settings
            // page, the only place the permission can still be given.
            if (!ok && android.os.SystemClock.elapsedRealtime() - micAskedAt < 500L) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", context.packageName, null))
                    )
                }
            }
        }
        return PermissionGate(micGranted) {
            micAskedAt = android.os.SystemClock.elapsedRealtime()
            launcher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    @Composable
    override fun alarmViewModel(): AlarmViewModel = koinViewModel()

    @Composable
    override fun flicViewModel(): FlicViewModel = koinViewModel()

    @Composable
    override fun NavigatorSettings(
        settings: AppSettings,
        viewModel: SettingsViewModel,
        scrollToWeather: Boolean,
        onWeatherTop: (Float) -> Unit,
    ) {
        NavigatorSettingsContent(viewModel = viewModel, scrollToWeather = scrollToWeather, onWeatherTop = onWeatherTop)
    }

    @Composable
    override fun OfflineAreas() {
        OfflineAreasRow()
    }

    @Composable
    override fun ExternalGpsSettings() {
        ExternalGpsSection()
    }

    @Composable
    override fun RadarSettings() {
        SectionHeader(stringResource(Res.string.section_radar))
        RadarSection(viewModel = koinViewModel(), settingsViewModel = koinViewModel())
    }

    @Composable
    override fun TpmsSettings() {
        SectionHeader(stringResource(Res.string.section_tpms))
        TpmsSection(viewModel = koinViewModel())
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
    override fun rememberAvatarPicker(onPicked: (PickedAvatar?) -> Unit): AvatarPicker {
        var picked by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        var showCrop by remember { mutableStateOf(false) }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            // Downsample on decode: a full-res gallery photo would OOM / blow the
            // canvas bitmap limit in the crop dialog and crash the app.
            val bmp = runCatching { decodeDownsampledBitmap(context, uri) }
                .getOrNull() ?: return@rememberLauncherForActivityResult
            picked = bmp
            onPicked(null)
            showCrop = true
        }
        val src = picked
        // The crop step renders full-screen; the dialog that asked draws nothing else meanwhile.
        if (src != null && showCrop) {
            UserMarkerCropDialog(
                source = src,
                onCancel = { showCrop = false },
                onApply = { cropped ->
                    onPicked(PickedAvatar(cropped.asImageBitmap(), cropped.toPngBase64()))
                    showCrop = false
                },
                // 256px so the 96dp preview is crisp (server still re-encodes to 64).
                outputSize = 256,
            )
        }
        return AvatarPicker(launch = { launcher.launch("image/*") }, cropping = src != null && showCrop)
    }

    override fun shareJson(json: String, subject: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, json)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        runCatching {
            context.startActivity(Intent.createChooser(shareIntent, null))
        }
    }
}

/** The cropped avatar as a base64 PNG (no data-URL prefix). */
private fun android.graphics.Bitmap.toPngBase64(): String {
    val baos = java.io.ByteArrayOutputStream()
    compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
    return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
}
