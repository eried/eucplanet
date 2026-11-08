package com.eried.eucplanet.data.repository

import java.io.BufferedWriter
import java.io.File

/**
 * Android's trips folder for the shared trip tools ([TripTools], [TripDerive]):
 * plain `java.io.File`s, read through a BufferedReader so a long ride is
 * streamed, never held in memory.
 */
class AndroidTripToolFiles(private val dir: () -> File) : TripToolFiles {
    private fun f(name: String) = File(dir(), name)
    override fun exists(name: String) = f(name).exists()
    override fun length(name: String) = f(name).length()
    override fun delete(name: String) = f(name).delete()
    override fun rename(from: String, to: String) = f(from).renameTo(f(to))
    override fun lines(name: String): LineSource = f(name).lineSource()
    override fun <R> write(name: String, block: (LineSink) -> R): R = f(name).writeLines(block)
}

/** [this] file's lines, opened afresh for each pass. */
fun File.lineSource(): LineSource = object : LineSource {
    override fun <R> useLines(block: (Sequence<String>) -> R): R = this@lineSource.useLines(block = block)
}

/** Writes [this] file line by line, opening it at the first line, as [TripToolFiles.write] asks. */
fun <R> File.writeLines(block: (LineSink) -> R): R {
    var out: BufferedWriter? = null
    try {
        return block(LineSink { line ->
            val w = out ?: bufferedWriter().also { out = it }
            w.write(line)
            w.newLine()
        })
    } finally {
        out?.close()
    }
}

// The File forms the trip tools had before they were shared, kept for the
// JVM tests that drive them on real files.

fun TripDerive.writeSection(source: File, dest: File, startMs: Long, endMs: Long): Int {
    if (!source.exists()) return 0
    return dest.writeLines { writeSection(source.lineSource(), it, startMs, endMs) }
}

fun TripDerive.rewriteWheelIdentity(source: File, dest: File, name: String, mac: String?): Int {
    if (!source.exists()) return 0
    return dest.writeLines { rewriteWheelIdentity(source.lineSource(), it, name, mac) }
}

fun TripDerive.rewriteWheelIdentity(source: File, dest: File, identity: Map<String, String>): Int {
    if (!source.exists()) return 0
    return dest.writeLines { rewriteWheelIdentity(source.lineSource(), it, identity) }
}

fun TripDerive.rewriteTripName(source: File, dest: File, name: String): Int {
    if (!source.exists()) return 0
    return dest.writeLines { rewriteTripName(source.lineSource(), it, name) }
}

fun TripDerive.stampDerived(file: File, identity: Map<String, String>): Boolean =
    stampDerived(AndroidTripToolFiles { file.parentFile!! }, file.name, identity)

fun TripDerive.writeJoined(sources: List<File>, dest: File): Int {
    val present = sources.filter { it.exists() }
    if (present.isEmpty()) return 0
    return dest.writeLines { writeJoined(present.map { it.lineSource() }, it) }
}
