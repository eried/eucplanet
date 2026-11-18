package com.eried.eucplanet.audio

import java.io.File

/** The grain banks as the phone ships them, read straight from `res/raw` (tests run in the module folder). */
object TestGrainBanks {
    val dir = File("src/main/res/raw")

    fun loader(sampleRate: Int): (String) -> GrainBank? = { asset ->
        val f = File(dir, "$asset.bin")
        if (f.exists()) GrainBank.load(sampleRate) { f.inputStream() } else null
    }
}
