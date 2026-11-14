package com.eried.eucplanet.data.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.io.encoding.Base64

/**
 * Persists the rider's custom navigation-marker photo as a single PNG file
 * ([file]; Android keeps it in noBackupFilesDir), NOT in the settings JSON.
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
class NavMarkerStore(
    private val file: MarkerFile,
) {

    private val _photoDataUrl = MutableStateFlow(loadDataUrl())
    /** Base64 data URL of the custom marker, or null when none is set. */
    val photoDataUrl: StateFlow<String?> = _photoDataUrl.asStateFlow()

    private fun loadDataUrl(): String? = runCatching {
        file.read()?.let { DATA_URL_PREFIX + Base64.Default.encode(it) }
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
            val bytes = MIME.decode(dataUrl.substring(comma + 1))
            file.write(bytes)
            _photoDataUrl.value = dataUrl
        }
    }

    /**
     * The one file the photo lives in, as raw PNG bytes. [read] is null when
     * there is none; [write] and [delete] may throw, which [set] swallows.
     */
    interface MarkerFile {
        fun read(): ByteArray?
        fun write(bytes: ByteArray)
        fun delete()
    }

    companion object {
        /** The file's name, in Android's noBackupFilesDir. */
        const val MARKER_FILE = "nav_user_marker.png"
        private const val DATA_URL_PREFIX = "data:image/png;base64,"

        /** Reads what `android.util.Base64.decode(s, DEFAULT)` reads: line breaks skipped, padding optional. */
        private val MIME = Base64.Mime.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    }
}
