@file:OptIn(ExperimentalForeignApi::class)

package com.eried.eucplanet.audio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer

/**
 * Realtime bridge for the iOS `EngineAudioBridge` (AVAudioSourceNode). Swift passes
 * the address of its mono Float output buffer; we render the synth into a reused
 * scratch array and copy it across. Reused scratch + native pointer copy keep the
 * audio render callback allocation-free.
 */
object EngineAudioNative {
    private val scratch = FloatArray(8192)

    /** Render [count] mono Float32 samples into the buffer at [bufferAddr]. */
    fun render(bufferAddr: Long, count: Int) {
        val n = count.coerceAtMost(scratch.size)
        EngineSoundController.render(scratch, n)
        val ptr = bufferAddr.toCPointer<FloatVar>() ?: return
        for (i in 0 until n) ptr[i] = scratch[i]
    }
}
