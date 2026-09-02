package com.eried.eucplanet.tpms

/**
 * The one cheap-TPMS format that is actually documented and checkable.
 *
 * Sold as ZEEPIN and under a dozen other names, and the family several open
 * source projects settled on: an ESP32 reader, an iOS logger and a Home
 * Assistant integration all decode the same layout, and their published worked
 * example agrees with itself on all four fields at once.
 *
 * Company id 0x0001 with an 18 byte advertisement. Android hands the two id
 * bytes over separately, so what arrives here is the remaining 16:
 *
 *   [0]      sensor number, 0x80..0x83 for wheels one to four
 *   [1..5]   sensor address
 *   [6..9]   pressure, little endian, thousandths of a kPa
 *   [10..13] temperature, little endian, hundredths of a degree
 *   [14]     battery percent
 *   [15]     alarm flag
 *
 * Checked against the published packet
 * 83 EA CA 40 61 81 C8 1C 04 00 0B 0B 00 00 4B 00, which reads as 269 kPa,
 * 28.27 C and 75 percent battery. Four independent fields all landing on
 * believable values is the evidence the rider's own sensor never produced.
 *
 * Not the rider's sensor. Theirs advertises under a squatted id with a payload
 * where nothing moves when the tyre does, and this decoder deliberately does
 * not try to stretch to cover it.
 */
object ZeepinTpmsDecoder {

    const val COMPANY_ID = 0x0001

    /** The 16 bytes left after the company id. */
    const val PAYLOAD_LEN = 16

    /** Pressure in kPa, or null when this is not one of these sensors. */
    fun pressureKpa(companyId: Int, data: ByteArray): Float? {
        if (companyId != COMPANY_ID || data.size != PAYLOAD_LEN) return null
        // Wheel one to four. Anything else is a different device that happens
        // to share the id, and there are plenty of those.
        val sensorNumber = data[0].toInt() and 0xFF
        if (sensorNumber !in 0x80..0x83) return null
        val kpa = u32le(data, 6) / 1000f
        // A bicycle tyre runs about 200 kPa and a truck about 900. Zero means
        // the sensor is off the valve, and anything past 1500 is a misread
        // rather than a tyre.
        return kpa.takeIf { it > 0f && it < 1500f }
    }

    /** Temperature in Celsius, or null when this is not one of these sensors. */
    fun temperatureC(companyId: Int, data: ByteArray): Float? {
        if (companyId != COMPANY_ID || data.size != PAYLOAD_LEN) return null
        if ((data[0].toInt() and 0xFF) !in 0x80..0x83) return null
        val c = u32le(data, 10) / 100f
        return c.takeIf { it > -50f && it < 120f }
    }

    /** Battery percent, or null when this is not one of these sensors. */
    fun batteryPercent(companyId: Int, data: ByteArray): Int? {
        if (companyId != COMPANY_ID || data.size != PAYLOAD_LEN) return null
        if ((data[0].toInt() and 0xFF) !in 0x80..0x83) return null
        return (data[14].toInt() and 0xFF).takeIf { it in 0..100 }
    }

    /** Which tyre this sensor was installed on, 1 to 4, or null. */
    fun wheelNumber(companyId: Int, data: ByteArray): Int? {
        if (companyId != COMPANY_ID || data.size != PAYLOAD_LEN) return null
        val n = data[0].toInt() and 0xFF
        return if (n in 0x80..0x83) n - 0x7F else null
    }

    private fun u32le(d: ByteArray, at: Int): Long =
        (d[at].toLong() and 0xFF) or
            ((d[at + 1].toLong() and 0xFF) shl 8) or
            ((d[at + 2].toLong() and 0xFF) shl 16) or
            ((d[at + 3].toLong() and 0xFF) shl 24)
}
