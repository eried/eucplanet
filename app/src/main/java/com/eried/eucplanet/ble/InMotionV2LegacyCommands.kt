package com.eried.eucplanet.ble

import com.eried.eucplanet.ble.InMotionV2Protocol.Command
import com.eried.eucplanet.util.ByteUtils

/**
 * Command builders for the older models in the InMotion V2 protocol family —
 * V11, V12 HS / HT / Pro, V12S, V9. These use the same wire framing as V14
 * (CONTROL = 0x60) but a different payload shape for max-speed and horn.
 *
 * Kept in a separate file from [InMotionV2Commands] so the V14 builders, which
 * are known to work on a real wheel, are not touched. Adapter dispatch on
 * detected model picks the right builder.
 *
 * Protocol facts derived from public BLE-protocol research; no third-party
 * source code is reproduced here.
 */
object InMotionV2LegacyCommands {

    /**
     * Max-speed (tiltback) for V11/V12 HS/HT/Pro.
     *
     * These older models accept a SHORT setMaxSpeed packet that contains
     * only the tiltback threshold. Same CONTROL sub-command 0x21 the V14 form
     * uses, but with a 2-byte payload instead of 4. The wheel discriminates
     * by length, not by sub-command.
     *
     *   payload: [0x21, tiltback_lo, tiltback_hi]   (uint16-LE × 100)
     *
     * Models that want the alarm thresholds set should use
     * [InMotionV2Commands.setMaxSpeedV14] instead — those use the longer form.
     */
    fun setMaxSpeedShort(tiltbackKmh: Float): ByteArray {
        val tiltback = ByteUtils.putUint16LE((tiltbackKmh * 100).toInt())
        return InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(0x21, tiltback[0], tiltback[1])
        )
    }

    /**
     * Horn for V11/V12 HS/HT/Pro/V12S/V9. These older models respond to a
     * "playSound" variant whose trailing byte differs from V14's "playBeep":
     *
     *   playBeep (V14):  [0x51, soundId, 0x64]  ← what InMotionV2Commands.horn() emits
     *   playSound (V12): [0x51, soundId, 0x01]  ← what this builder emits
     *
     * The default sound id 0x18 is the conventional horn alert per public
     * protocol research.
     */
    fun playSoundHorn(soundId: Byte = 0x18): ByteArray =
        InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(0x51, soundId, 0x01)
        )
}
