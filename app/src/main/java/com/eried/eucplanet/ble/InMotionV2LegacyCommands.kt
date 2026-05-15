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

    /**
     * Headlight for V12 HS / HT / Pro. WheelLog's `setLightV12` uses a
     * two-beam packet at sub-cmd 0x50, where the rider can drive the low
     * and high beam independently. We map our single on/off toggle to
     * both beams so the dashboard's Light button behaves like a master
     * switch: on -> both beams on (1, 1), off -> both off (0, 0).
     *
     *   payload: [0x50, lowBeam, highBeam]   (each 0 or 1)
     *
     * V14 family wheels use a different single-byte sub-cmd; this builder
     * is for the V12 HS/HT/Pro path only.
     */
    fun setLightV12(on: Boolean): ByteArray {
        val v = if (on) 0x01.toByte() else 0x00.toByte()
        return InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(0x50, v, v)
        )
    }

    /**
     * Two-tier alarm-speed for V12 HS / HT / Pro. WheelLog's `setAlarmSpeedV12`
     * accepts two thresholds in one packet (sub-cmd 0x3e). Our app exposes a
     * single alarm threshold, so we send the same value for both tiers.
     *
     *   payload: [0x3e, alarm1_lo, alarm1_hi, alarm2_lo, alarm2_hi]
     *            (each uint16-LE * 100)
     */
    fun setAlarmSpeedV12(alarmKmh: Float): ByteArray {
        val raw = ByteUtils.putUint16LE((alarmKmh * 100).toInt())
        return InMotionV2Protocol.buildExtendedPacket(
            Command.CONTROL,
            byteArrayOf(0x3e, raw[0], raw[1], raw[0], raw[1])
        )
    }
}
