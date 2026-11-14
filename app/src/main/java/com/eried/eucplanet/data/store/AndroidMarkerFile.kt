package com.eried.eucplanet.data.store

import android.content.Context
import java.io.File

/**
 * The navigation-marker photo's PNG in noBackupFilesDir: kept across app
 * updates, left out of Google's cloud backup, so a new phone starts without it.
 */
class AndroidMarkerFile(private val context: Context) : NavMarkerStore.MarkerFile {
    private val file: File get() = File(context.noBackupFilesDir, NavMarkerStore.MARKER_FILE)

    override fun read(): ByteArray? = file.takeIf { it.exists() }?.readBytes()

    override fun write(bytes: ByteArray) = file.writeBytes(bytes)

    override fun delete() {
        file.delete()
    }
}
