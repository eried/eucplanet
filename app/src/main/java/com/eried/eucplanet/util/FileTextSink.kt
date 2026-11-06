package com.eried.eucplanet.util

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/** A [TextSink] over a local file, buffered as the trip CSV always was. */
class FileTextSink(private val file: File) : TextSink {
    private var writer: BufferedWriter? = null

    override fun open() {
        writer = BufferedWriter(FileWriter(file))
    }

    override fun writeLine(line: String) {
        writer?.write(line)
        writer?.newLine()
    }

    override fun flush() {
        writer?.flush()
    }

    override fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }
}

/** A trip CSV writer on [file]. */
fun CsvWriter(file: File): CsvWriter = CsvWriter(FileTextSink(file))
