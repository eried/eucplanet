package com.eried.eucplanet.tpms

/**
 * The valve-cap sensor sold with the LY TPMS app (`com.zl.dev.tire.lytpms`).
 *
 * The maths here is not inferred from captures any more. It is the vendor
 * app's own, read out of `com.wicarlink.zl.data.bean.TireBean.parse` in the
 * APK the rider pulled off their phone, so the units, the offsets and the odd
 * scale factor are the manufacturer's rather than a fit to a handful of
 * points. Wicarlink is the actual maker; "LY" is one of several names the same
 * hardware is sold under, ITPMS being another from the same developer.
 *
 * The app reads the WHOLE advertisement, so its indices count the two AD
 * header bytes and the two company-id bytes that Android strips before it
 * hands us the payload. Everything below is shifted by four to match:
 *
 *   app[4]  -> [0]  battery, hundredths of a volt above 1.22 V
 *   app[5]  -> [1]  pressure, low byte
 *   app[6]  -> [2]  temperature, degrees C plus 55
 *   app[7]  -> [3]  state
 *   app[8]  -> [4]  protocol version
 *   app[9]  -> [5]  checksum
 *   app[10] -> [6]  pressure, high byte
 *   app[11] -> [7]  checksum
 *   app[12] -> [8]  BLE version, tenths (0x28 -> 4.0)
 *   app[16..18] -> [12..14]  sensor id, printed back to front
 *
 * Two things this corrects, both of which had looked right for the wrong
 * reason. The high byte of the pressure is [6], not [3]; [6] was zero in every
 * capture, so a wrong guess and the right answer agreed on everything up to
 * 803 kPa. And the reading is not kPa but a raw count scaled by 3.144, so a
 * rider at 3.6 bar saw 1.15 - the number was the count, unscaled.
 *
 * Temperature had the same kind of luck. Byte [2] read 82 at a real 27 C, and
 * both "Fahrenheit" and "Celsius plus 55" land on 27 from 82. The app says it
 * is the offset, and the two only diverge away from room temperature, which no
 * capture ever reached.
 */
object LyTpmsDecoder {

    /** The (squatted) manufacturer id this family advertises under. */
    const val COMPANY_ID = 0x00AC

    /** Every packet from this family is exactly this long. */
    const val PAYLOAD_LEN = 15

    /**
     * The vendor's own scale from raw count to kPa.
     *
     * Not a round number and not ours to tidy: it is the constant in the app,
     * and 115 x 3.144 is 361.6 kPa against a gauge reading 3.6 bar.
     */
    private const val KPA_PER_COUNT = 3.144f

    /** Degrees the sensor adds to the temperature before sending it. */
    private const val TEMP_OFFSET_C = 55

    /** Pressure in kPa, or null when this is not one of these sensors. */
    fun pressureKpa(companyId: Int, data: ByteArray, address: String?): Float? {
        if (!isThisFamily(companyId, data, address)) return null
        val counts = (data[1].toInt() and 0xFF) + 256 * (data[6].toInt() and 0xFF)
        val kpa = counts * KPA_PER_COUNT
        // A cap on a flat tyre reports 0, which is a reading. Past 1000 kPa is
        // not a tyre any of these sits on.
        return kpa.takeIf { it >= 0f && it < 1000f }
    }

    /** Temperature in Celsius, or null when this is not one of these sensors. */
    fun temperatureC(companyId: Int, data: ByteArray, address: String?): Float? {
        if (!isThisFamily(companyId, data, address)) return null
        val c = ((data[2].toInt() and 0xFF) - TEMP_OFFSET_C).toFloat()
        return c.takeIf { it > -40f && it < 120f }
    }

    /**
     * Battery volts, or null when this is not one of these sensors.
     *
     * The app's own curve: hundredths of a volt above 1.22. A full CR2032
     * reads 3.0 V here, which is what the rider's showed while their app said
     * about 85 percent.
     */
    fun batteryVolts(companyId: Int, data: ByteArray, address: String?): Float? {
        if (!isThisFamily(companyId, data, address)) return null
        val volts = (data[0].toInt() and 0xFF) * 0.01f + 1.22f
        return volts.takeIf { it > 1.2f && it < 3.8f }
    }

    /**
     * The id the vendor app shows for this sensor, such as "5B611B".
     *
     * Its own three bytes, printed back to front, and the same characters the
     * rider sees in the app they bought it with.
     */
    fun sensorId(companyId: Int, data: ByteArray, address: String?): String? {
        if (!isThisFamily(companyId, data, address)) return null
        return "%02X%02X%02X".format(data[14], data[13], data[12])
    }

    /** BLE protocol version the cap reports, such as 4.0. */
    fun bleVersion(companyId: Int, data: ByteArray, address: String?): Float? {
        if (!isThisFamily(companyId, data, address)) return null
        return (data[8].toInt() and 0xFF) / 10f
    }

    /** What the cap says it is doing. Values are the vendor application's. */
    enum class State { NORMAL, LEAKAGE, INFLATION, START_UP, POWER_ON, WAKE_UP }

    /**
     * The cap's own state, or null when this is not one of these sensors.
     *
     * Read as static field values out of the vendor application rather than
     * guessed from the order they are declared in, which had two of them the
     * wrong way round.
     *
     * Only leakage and inflation say anything about the tyre. The other three
     * are the cap waking up and mean nothing to a rider.
     */
    fun state(companyId: Int, data: ByteArray, address: String?): State? {
        if (!isThisFamily(companyId, data, address)) return null
        return when (data[3].toInt() and 0xFF) {
            0 -> State.NORMAL
            1 -> State.LEAKAGE
            2 -> State.INFLATION
            3 -> State.START_UP
            4 -> State.POWER_ON
            5 -> State.WAKE_UP
            else -> null
        }
    }

    private fun isThisFamily(companyId: Int, data: ByteArray, address: String?): Boolean =
        companyId == COMPANY_ID &&
            data.size == PAYLOAD_LEN &&
            TpmsSignature.endsWithOwnMac(data, address)
}
