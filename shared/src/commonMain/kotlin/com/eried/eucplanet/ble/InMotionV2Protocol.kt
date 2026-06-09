package com.eried.eucplanet.ble

// InMotion V2 protocol packet encoding/decoding.
// Frame: AA AA {escaped_payload} {checksum}
// Payload: {flags} {length} {command} {data...}
// Checksum: XOR of all unescaped payload bytes
// Escape: 0xAA -> 0xA5 0xAA, 0xA5 -> 0xA5 0xA5
object InMotionV2Protocol {

    const val HEADER: Byte = 0xAA.toByte()
    const val ESCAPE: Byte = 0xA5.toByte()

    object Flags {
        const val INITIAL: Byte = 0x11
        const val DEFAULT: Byte = 0x14
        const val EXTENDED: Byte = 0x16 // Official app format with routing bytes
    }

    // Routing bytes used in the official InMotion app's extended packet format
    private val ROUTING_HEADER = byteArrayOf(0x02, 0x21)

    object Command {
        const val MAIN_VERSION: Byte = 0x01
        const val MAIN_INFO: Byte = 0x02
        const val DIAGNOSTIC: Byte = 0x03
        const val REAL_TIME_INFO: Byte = 0x04
        const val BATTERY_INFO: Byte = 0x05
        const val SOMETHING1: Byte = 0x10
        const val TOTAL_STATS: Byte = 0x11
        const val SETTINGS: Byte = 0x20
        const val CONTROL: Byte = 0x60
    }

    object ControlSubCmd {
        const val SET_MAX_SPEED: Byte = 0x21
        const val SET_PEDAL_TILT: Byte = 0x22
        const val SET_VOLUME: Byte = 0x26
        const val SET_LIGHT_BRIGHTNESS: Byte = 0x2B
        const val SET_DRL: Byte = 0x2D
        const val SET_LOCK: Byte = 0x31
        const val SET_LIGHT: Byte = 0x50
        const val PLAY_SOUND: Byte = 0x51
    }

    /**
     * Build a complete packet ready to write to BLE.
     */
    fun buildPacket(flags: Byte, command: Byte, data: ByteArray = byteArrayOf()): ByteArray {
        val length = (data.size + 1).toByte() // command byte + data
        val payload = ByteArray(3 + data.size)
        payload[0] = flags
        payload[1] = length
        payload[2] = command
        data.copyInto(payload, 3)

        val checksum = xorChecksum(payload)
        val escaped = escapePayload(payload)

        // AA AA [escaped] [checksum]
        val packet = ByteArray(2 + escaped.size + 1)
        packet[0] = HEADER
        packet[1] = HEADER
        escaped.copyInto(packet, 2)
        packet[packet.size - 1] = checksum
        return packet
    }

    /**
     * Build a packet using the extended format (flags=0x16, routing bytes 02 21).
     * This matches the official InMotion app's packet format, needed for
     * security-sensitive commands like lock/unlock.
     */
    fun buildExtendedPacket(command: Byte, data: ByteArray = byteArrayOf()): ByteArray {
        // Payload: [flags=0x16] [length] [0x02] [0x21] [command] [data...]
        val innerSize = ROUTING_HEADER.size + 1 + data.size // routing + command + data
        val length = innerSize.toByte()
        val payload = ByteArray(2 + innerSize)
        payload[0] = Flags.EXTENDED
        payload[1] = length
        ROUTING_HEADER.copyInto(payload, 2)
        payload[4] = command
        data.copyInto(payload, 5)

        val checksum = xorChecksum(payload)
        val escaped = escapePayload(payload)

        val packet = ByteArray(2 + escaped.size + 1)
        packet[0] = HEADER
        packet[1] = HEADER
        escaped.copyInto(packet, 2)
        packet[packet.size - 1] = checksum
        return packet
    }

    /**
     * Parse a raw packet (starting with AA AA). Returns null if invalid.
     */
    fun parsePacket(raw: ByteArray): ParsedPacket? {
        if (raw.size < 5) return null
        if (raw[0] != HEADER || raw[1] != HEADER) return null

        val checksum = raw[raw.size - 1]
        val escapedPayload = raw.copyOfRange(2, raw.size - 1)
        val payload = unescapePayload(escapedPayload)

        val computed = xorChecksum(payload)
        if (computed != checksum) return null

        if (payload.size < 3) return null
        val flags = payload[0]
        val length = payload[1].toInt() and 0xFF
        val command = payload[2]
        val data = if (payload.size > 3) payload.copyOfRange(3, payload.size) else byteArrayOf()

        return ParsedPacket(flags, command, data)
    }

    fun escapePayload(payload: ByteArray): ByteArray {
        val result = mutableListOf<Byte>()
        for (b in payload) {
            if (b == HEADER || b == ESCAPE) {
                result.add(ESCAPE)
            }
            result.add(b)
        }
        return result.toByteArray()
    }

    fun unescapePayload(escaped: ByteArray): ByteArray {
        val result = mutableListOf<Byte>()
        var i = 0
        while (i < escaped.size) {
            if (escaped[i] == ESCAPE && i + 1 < escaped.size) {
                result.add(escaped[i + 1])
                i += 2
            } else {
                result.add(escaped[i])
                i++
            }
        }
        return result.toByteArray()
    }

    fun xorChecksum(payload: ByteArray): Byte {
        var check = 0
        for (b in payload) {
            check = (check xor (b.toInt() and 0xFF)) and 0xFF
        }
        return check.toByte()
    }
}

data class ParsedPacket(
    val flags: Byte,
    val command: Byte,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedPacket) return false
        return flags == other.flags && command == other.command && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = flags.toInt()
        result = 31 * result + command.toInt()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
