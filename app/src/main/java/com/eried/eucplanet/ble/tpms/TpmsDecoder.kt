package com.eried.eucplanet.ble.tpms

/**
 * Multi-format decoder for cheap aftermarket BLE TPMS sensors. None of
 * these protocols are formally specified; the layouts below come from
 * publicly reverse-engineered ESP32 / ESPHome projects.
 *
 * Strategy: try each known format in turn. The first one that decodes
 * to plausible numbers (pressure 0-1000 kPa, temperature -40..120 °C)
 * wins. If nothing fits we still return a reading with the alarm-state
 * enum decoded (when the format exposes one) and the raw bytes for
 * diagnostics -- so the bind UI lists the sensor and the rider can at
 * least confirm we're seeing it.
 *
 * Service UUID for the scanner: 0xFBB0 is the de-facto identifier for
 * the cheap-FBB0 family; many sensors include it in the advertising
 * service-UUID list. We do NOT filter on it (the scanner uses a name /
 * manufacturer-data heuristic) because some sensors strip the service
 * UUID to save advertising bytes.
 */
object TpmsDecoder {

    /**
     * Decode one advertising payload. [mfgData] is the manufacturer-
     * specific data starting at the 2-byte company ID (i.e. the bytes
     * INCLUDING the mfg ID). [nowMs] is the monotonic clock value to
     * stamp on the resulting reading. Returns null when [mfgData] does
     * not look like any known TPMS format at all (so the scanner can
     * skip the result -- the rider's earbuds shouldn't surface as a
     * "TPMS sensor with unknown bytes").
     */
    fun decode(mfgData: ByteArray, nowMs: Long): TpmsReading? {
        if (mfgData.size < 8) return null

        // The 2-byte company ID is LE on the wire.
        val mfgId = (mfgData[0].toInt() and 0xFF) or ((mfgData[1].toInt() and 0xFF) shl 8)

        // TomTom 0x0001 -- canonical layout used by the "BVM" branded
        // sensors and the BLE-TPMS ESP32 reference project.
        //   0-1  : mfg id 0x0001
        //   2-7  : 6-byte sensor MAC
        //   8-11 : pressure u32 LE in 0.001 bar (= 0.1 kPa)
        //   12-15: temperature u32 LE in 0.01 °C
        //   16   : battery %
        //   17   : alarm flag (0 = ok, 1 = leak)
        if (mfgId == 0x0001 && mfgData.size >= 18) {
            val pressureMillibar = u32LE(mfgData, 8)
            val tempCentiC = u32LE(mfgData, 12).toInt()
            val pressureKPa = pressureMillibar * 0.1f  // 0.001 bar = 0.1 kPa
            val tempC = tempCentiC / 100f
            val batt = mfgData[16].toInt() and 0xFF
            val alarm = if ((mfgData[17].toInt() and 0xFF) != 0) TpmsAlarm.LOW else TpmsAlarm.OK
            if (pressureKPa in 0f..1500f && tempC in -40f..120f && batt in 0..100) {
                return TpmsReading(
                    pressureKPa = pressureKPa,
                    temperatureC = tempC,
                    batteryPct = batt,
                    alarm = alarm,
                    lastSeenMs = nowMs,
                    rawHex = mfgData.toHex(),
                )
            }
        }

        // Generic FBB0-style layout used by Tymate / Sykik / a long tail
        // of OEM-rebrands. Single 8-byte payload AFTER the mfg ID:
        //   0    : temperature in °C (signed)
        //   1-2  : pressure u16 BE in 0.1 kPa (= 0.001 bar)
        //   3    : battery %
        //   4    : alarm (0 ok, 1 high, 2 low)
        //   5-7  : sensor address suffix
        // We accept anything with a plausible pressure + temperature.
        if (mfgData.size >= 8) {
            val tempC = mfgData[2].toInt().toByte().toInt().toFloat()
            val pressureKPa = (((mfgData[3].toInt() and 0xFF) shl 8) or
                (mfgData[4].toInt() and 0xFF)) * 0.1f
            val batt = mfgData[5].toInt() and 0xFF
            if (pressureKPa in 0f..1500f && tempC in -40f..120f && batt in 0..100) {
                val alarmByte = mfgData[6].toInt() and 0xFF
                val alarm = when (alarmByte) {
                    0 -> TpmsAlarm.OK
                    1 -> TpmsAlarm.HIGH
                    2 -> TpmsAlarm.LOW
                    else -> TpmsAlarm.UNKNOWN
                }
                return TpmsReading(
                    pressureKPa = pressureKPa,
                    temperatureC = tempC,
                    batteryPct = batt,
                    alarm = alarm,
                    lastSeenMs = nowMs,
                    rawHex = mfgData.toHex(),
                )
            }
        }

        // Manufacturer ID 0x00AC layout observed in our own capture
        // (sensor "5B611B"). Pressure VALUE is not in the advertising
        // payload on this sensor family -- byte 3 carries only an
        // alarm-state enum (0 LOW, 1 HIGH, 2 OK) and the actual
        // pressure reading requires a GATT connection. We still emit
        // a TpmsReading so the bind UI lists the sensor and the
        // alarm chip can light up, but pressureKPa stays null.
        //
        // Payload (17 bytes including the 2-byte mfg ID):
        //   0-1 : ac 00      (mfg id 0x00AC)
        //   2   : status byte (b0 idle, b1 = wake / just-updated)
        //   3   : alarm enum (0/1/2)
        //   4   : 0x4f marker
        //   5   : 0x0a marker -- always present
        //   6-8 : varying 3-byte field (not pressure -- doesn't map
        //         monotonically to known pressure values)
        //   9   : 0x28 -- likely battery * 2 (0x28 = 80, /2 = 40 %)
        //   10  : 0x28 -- end marker
        //   11..: MAC reversed
        if (mfgId == 0x00AC && mfgData.size >= 11) {
            val alarmByte = mfgData[5].toInt() and 0xFF
            val alarm = when (alarmByte) {
                0 -> TpmsAlarm.LOW
                1 -> TpmsAlarm.HIGH
                2 -> TpmsAlarm.OK
                else -> TpmsAlarm.UNKNOWN
            }
            // Byte 10 looks like battery -- on our captures it stayed
            // at 0x28 throughout, which would be 40 % if halved. Until
            // we have more captures across different batteries, surface
            // it as-is divided by 2 and clamp to a safe range.
            val battRaw = (mfgData[10].toInt() and 0xFF) / 2
            val batt = battRaw.coerceIn(0, 100)
            return TpmsReading(
                pressureKPa = null,
                temperatureC = null,
                batteryPct = batt,
                alarm = alarm,
                lastSeenMs = nowMs,
                rawHex = mfgData.toHex(),
            )
        }

        return null
    }

    /** Read u32 little-endian at [offset], returned as Long to avoid sign mess. */
    private fun u32LE(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02x".format(it) }
}
