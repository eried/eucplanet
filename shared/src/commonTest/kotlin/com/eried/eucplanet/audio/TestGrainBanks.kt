package com.eried.eucplanet.audio

/**
 * The grain banks as the phone ships them, read straight from
 * app/src/main/res/raw ([TEST_GRAIN_DIR], written by the build), on the JVM
 * and on the iOS simulator.
 */
object TestGrainBanks {
    val dir: String = TEST_GRAIN_DIR

    /** The bank file [name] ("grain_v_twin.bin"), or null when it is not there. */
    fun bytes(name: String): ByteArray? = readTestFile("$dir/$name")

    /** Every file name in the folder. */
    fun names(): List<String> = listTestDir(dir)

    fun loader(sampleRate: Int): (String) -> GrainBank? = { asset ->
        val b = bytes("$asset.bin")
        if (b != null) GrainBank.load(sampleRate) { b } else null
    }
}

/** The file at [path], or null when there is none. */
expect fun readTestFile(path: String): ByteArray?

/** The names of the files in the folder at [path]. */
expect fun listTestDir(path: String): List<String>
