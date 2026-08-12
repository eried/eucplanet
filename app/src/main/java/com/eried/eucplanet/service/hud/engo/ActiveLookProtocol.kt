package com.eried.eucplanet.service.hud.engo

/**
 * Pure encoder for the ActiveLook BLE command protocol used by ENGO 2 / 3
 * smart glasses. Builds command byte arrays only - no Android, no state - so
 * every command is unit-tested against the published spec with no hardware.
 *
 * Frame: `0xFF | cmdId | format | length | [data] | 0xAA`.
 *  - `format` bit 5 (0x20) selects the length field width: 0 = 1 byte, 1 = 2
 *    bytes. Bits 4-1 would carry a query-id byte count; we never use a query id,
 *    so `format` is 0x00 for short frames and 0x20 for long ones.
 *  - `length` is the WHOLE frame size (header + data + footer), Big Endian.
 *  - All scalars are Big Endian. Max 512 data bytes per command.
 *
 * Spec: docs/superpowers/specs/2026-08-12-engo-hud-design.md (pinned from the
 * public ActiveLook API reference).
 */
object ActiveLookProtocol {

    // --- BLE GATT (ActiveLook Commands Interface) ---
    const val SERVICE_UUID = "0783b03e-8535-b5a0-7140-a304d2495cb7"
    const val CHAR_RX_UUID = "0783b03e-8535-b5a0-7140-a304d2495cba" // write commands
    const val CHAR_TX_UUID = "0783b03e-8535-b5a0-7140-a304d2495cb8" // notify responses
    const val CHAR_CTRL_UUID = "0783b03e-8535-b5a0-7140-a304d2495cb9" // flow control / errors

    // Control-characteristic flow-control codes (see spec section 7).
    const val FLOW_RESUME = 0x01 // client may send
    const val FLOW_PAUSE = 0x02 // RX buffer 75% full, hold off

    // holdFlush actions.
    const val HOLD = 0x00
    const val FLUSH = 0x01
    const val HOLD_RESET = 0xFF

    private const val START: Byte = 0xFF.toByte()
    private const val FOOTER: Byte = 0xAA.toByte()

    // Command opcodes (subset used by the HUD renderer).
    private const val CMD_CLEAR = 0x01
    private const val CMD_BATTERY = 0x05
    private const val CMD_VERS = 0x06
    private const val CMD_LUMA = 0x10
    private const val CMD_GRAYSCALE = 0x30
    private const val CMD_LINE = 0x32
    private const val CMD_RECT = 0x33
    private const val CMD_RECTF = 0x34
    private const val CMD_CIRCF = 0x36
    private const val CMD_TXT = 0x37
    private const val CMD_HOLD_FLUSH = 0x39
    private const val CMD_ARC = 0x3C
    private const val CMD_COLOR = 0x3D
    private const val CMD_TXT_COLOR = 0x3E
    private const val CMD_FONT_SELECT = 0x52

    /** Wrap a command id + parameter bytes into a full ActiveLook frame. */
    fun frame(cmdId: Int, data: ByteArray = ByteArray(0)): ByteArray {
        // Short frame: FF cmd fmt len data AA -> 5 header/footer bytes + data.
        val shortLen = 5 + data.size
        return if (shortLen <= 0xFF) {
            ByteArray(shortLen).also {
                it[0] = START
                it[1] = cmdId.toByte()
                it[2] = 0x00 // format: 1-byte length, no query id
                it[3] = shortLen.toByte()
                data.copyInto(it, 4)
                it[shortLen - 1] = FOOTER
            }
        } else {
            // Long frame: FF cmd 0x20 lenHi lenLo data AA -> 6 bytes + data.
            val longLen = 6 + data.size
            ByteArray(longLen).also {
                it[0] = START
                it[1] = cmdId.toByte()
                it[2] = 0x20 // format: 2-byte length
                it[3] = (longLen ushr 8).toByte()
                it[4] = longLen.toByte()
                data.copyInto(it, 5)
                it[longLen - 1] = FOOTER
            }
        }
    }

    // --- General ---
    fun clear(): ByteArray = frame(CMD_CLEAR)
    fun battery(): ByteArray = frame(CMD_BATTERY)
    fun version(): ByteArray = frame(CMD_VERS)

    /** Display luminance, 0-15. */
    fun luma(level: Int): ByteArray = frame(CMD_LUMA, byteArrayOf(u8(level, 0, 15)))

    /** Grey level for subsequent draws, 0-15 (ENGO 2 + fallback on ENGO 3). */
    fun grayscale(level: Int): ByteArray = frame(CMD_GRAYSCALE, byteArrayOf(u8(level, 0, 15)))

    /** RG colour for subsequent draws, 8-bit RRGG (ENGO 3 colour glasses only). */
    fun color(rg: Int): ByteArray = frame(CMD_COLOR, byteArrayOf(u8(rg, 0, 255)))

    /** Batch a frame: [HOLD] before the draws, [FLUSH] to present them at once. */
    fun holdFlush(action: Int): ByteArray = frame(CMD_HOLD_FLUSH, byteArrayOf(action.toByte()))

    /** Select the active font by id. */
    fun fontSelect(id: Int): ByteArray = frame(CMD_FONT_SELECT, byteArrayOf(u8(id, 0, 255)))

    // --- Shapes ---
    fun line(x0: Int, y0: Int, x1: Int, y1: Int): ByteArray =
        frame(CMD_LINE, s16(x0) + s16(y0) + s16(x1) + s16(y1))

    fun rect(x0: Int, y0: Int, x1: Int, y1: Int): ByteArray =
        frame(CMD_RECT, s16(x0) + s16(y0) + s16(x1) + s16(y1))

    fun rectf(x0: Int, y0: Int, x1: Int, y1: Int): ByteArray =
        frame(CMD_RECTF, s16(x0) + s16(y0) + s16(x1) + s16(y1))

    fun circf(x: Int, y: Int, radius: Int): ByteArray =
        frame(CMD_CIRCF, s16(x) + s16(y) + byteArrayOf(u8(radius, 0, 255)))

    /** Arc: centre (x,y), radius, start/end angle (deg, 0 = 3 o'clock, clockwise), thickness. */
    fun arc(x: Int, y: Int, radius: Int, angleStart: Int, angleEnd: Int, thickness: Int): ByteArray =
        frame(
            CMD_ARC,
            s16(x) + s16(y) + byteArrayOf(u8(radius, 0, 255)) +
                s16(angleStart) + s16(angleEnd) + byteArrayOf(u8(thickness, 0, 255)),
        )

    // --- Text ---
    /**
     * Greyscale text (ENGO 2 + fallback). rotation 0-7 (8 directions), font id,
     * grey 0-15. The string is ASCII and NUL-terminated (ActiveLook convention;
     * confirm on a real unit).
     */
    fun txt(x: Int, y: Int, rotation: Int, font: Int, grey: Int, text: String): ByteArray =
        frame(
            CMD_TXT,
            s16(x) + s16(y) +
                byteArrayOf(u8(rotation, 0, 7), u8(font, 0, 255), u8(grey, 0, 15)) +
                asciiz(text),
        )

    /** Coloured text (ENGO 3 colour glasses only). color is 8-bit RRGG. */
    fun txtColor(x: Int, y: Int, rotation: Int, font: Int, color: Int, text: String): ByteArray =
        frame(
            CMD_TXT_COLOR,
            s16(x) + s16(y) +
                byteArrayOf(u8(rotation, 0, 7), u8(font, 0, 255), u8(color, 0, 255)) +
                asciiz(text),
        )

    // --- helpers ---
    private fun s16(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun u8(v: Int, min: Int, max: Int): Byte = v.coerceIn(min, max).toByte()

    /** ASCII bytes with a trailing NUL. Non-ASCII chars are dropped to '?'. */
    private fun asciiz(text: String): ByteArray {
        val bytes = ByteArray(text.length + 1)
        for (i in text.indices) {
            val c = text[i].code
            bytes[i] = (if (c in 0..127) c else '?'.code).toByte()
        }
        bytes[text.length] = 0
        return bytes
    }
}
