package com.eried.eucplanet.data.sync

import java.io.File

/** A trip CSV in the app's external files dir, for the shared Dropbox sync. */
class AndroidTripFile(val file: File) : TripFile {
    override val name: String get() = file.name
    override fun exists() = file.exists()
    override fun length() = file.length()
    override fun lastModified() = file.lastModified()
    override fun readBytes() = file.readBytes()
    override fun setLastModified(ms: Long) = file.setLastModified(ms)
    override fun delete() = file.delete()
    override fun <R> useLines(block: (Sequence<String>) -> R): R =
        file.bufferedReader().use { block(it.lineSequence()) }
}

/** The trips folder [dir] answers, created on each call as TripRepository.getTripsDir does. */
class AndroidTripFiles(private val dir: () -> File) : TripFileStore {
    override fun files(): List<TripFile> =
        dir().listFiles { f -> f.isFile }?.map { AndroidTripFile(it) }.orEmpty()

    override fun file(name: String): TripFile = AndroidTripFile(File(dir(), name))

    override fun writeWhole(name: String, bytes: ByteArray): TripFile {
        val dest = File(dir(), name)
        val part = File(dest.parentFile, dest.name + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(dest)) {
            dest.delete()
            if (!part.renameTo(dest)) throw java.io.IOException("Could not write ${dest.name}")
        }
        return AndroidTripFile(dest)
    }
}
