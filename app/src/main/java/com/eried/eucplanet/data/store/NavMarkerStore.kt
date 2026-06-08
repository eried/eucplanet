package com.eried.eucplanet.data.store

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the rider's custom navigation-marker photo as a single PNG file in
 * [Context.getNoBackupFilesDir], NOT in the settings JSON.
 *
 * Why a file and why no-backup:
 *  - It's a fat base64 image that would bloat every settings read/write and the
 *    settings backup if it rode along in the JSON blob.
 *  - noBackupFilesDir survives app **updates** but is excluded from Google's
 *    cloud auto-backup, so a full uninstall / new device starts without it --
 *    the marker is intentionally never recovered (re-pickable in the Navigator).
 *
 * The in-memory value is the base64 `data:image/png` URL that the Route Builder
 * map (JS) and the Studio overlay already consume; on disk it is the decoded
 * PNG bytes. The single [photoDataUrl] flow is shared (singleton), so changing
 * the photo in the Navigator updates the Studio overlay live and vice-versa.
 */
@Singleton
class NavMarkerStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File get() = File(context.noBackupFilesDir, MARKER_FILE)

    private val _photoDataUrl = MutableStateFlow(loadDataUrl())
    /** Base64 data URL of the custom marker, or null when none is set. */
    val photoDataUrl: StateFlow<String?> = _photoDataUrl.asStateFlow()

    private fun loadDataUrl(): String? = runCatching {
        val f = file
        if (!f.exists()) null
        else DATA_URL_PREFIX + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    /**
     * Save the marker from a base64 `data:image/...;base64,XXXX` URL (as produced
     * by the crop dialog), or clear it when [dataUrl] is null. Best-effort: writes
     * the decoded bytes to disk and updates [photoDataUrl].
     */
    fun set(dataUrl: String?) {
        if (dataUrl == null) {
            runCatching { file.delete() }
            _photoDataUrl.value = null
            return
        }
        val comma = dataUrl.indexOf(',')
        if (comma <= 0) return
        runCatching {
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            file.writeBytes(bytes)
            _photoDataUrl.value = dataUrl
        }
    }

    companion object {
        private const val MARKER_FILE = "nav_user_marker.png"
        private const val DATA_URL_PREFIX = "data:image/png;base64,"
    }
}
