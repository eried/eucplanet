package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.diagnostics.DiagnosticCommand
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KingSong wheel adapter. Recognises KS-* / S22 / S20 / S18 family wheels on
 * the HM-10 (0xFFE0 / 0xFFE1) BLE profile and routes their telemetry through
 * the shared [WheelAdapter] interface.
 *
 * Wire format and command set come from docs/protocols/kingsong.md. Inbound
 * frames are 20-byte fixed-length structures `AA 55 ... type 14 5A 5A`,
 * dispatched by the type byte at offset 16.
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik and contributors,
 * https://github.com/Wheellog/wheellog.android, GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
@Singleton
class KingsongAdapter @Inject constructor() : WheelAdapter {
    override val familyId = "kingsong"
    override val familyDisplayName = "KingSong"
    override val capabilities = WheelCapabilities.KINGSONG

    @Volatile private var detectedModel: KingsongModel? = null

    /**
     * Merged telemetry snapshot built up across the four-frame KingSong cycle
     * (0xA9 realtime + 0xB9 trip + 0xF5 PWM + 0xF6 speed limit). Each frame
     * carries only the fields it owns; without merging, every non-A9 frame
     * would emit a zero-filled WheelData and flicker the dashboard between
     * live values and dashes ~3x per second (FlyboyEUC KS-16X tester report).
     */
    @Volatile private var lastTelemetry: WheelData = WheelData()
    /** Latest temperature from the 0xA9 frame (board or generic sensor). */
    @Volatile private var lastTempA9: Float = 0f
    /** Latest temperature from the 0xB9 frame (second sensor, usually motor). */
    @Volatile private var lastTempB9: Float = 0f

    /**
     * Pending echo for a `0xA4` settings push. The KingSong handshake expects
     * the app to send the same 20 bytes back with the type byte rewritten to
     * `0x98`; we surface that as a queued packet picked up on the next poll
     * tick rather than wiring a synchronous-write path through the adapter.
     */
    @Volatile private var pendingEcho: ByteArray? = null

    override fun bleProfile(): BleProfile = BleProfile.HM10

    override fun notifyConnectingTo(deviceName: String?): DecodeResult.ModelName? {
        detectedModel = deviceName?.let { KingsongModel.fromReportedName(it) }
        return null
    }

    override fun initSequence(): List<ByteArray> = listOf(
        KingsongCommands.queryName(),
        KingsongCommands.queryLimits()
    )

    // KingSong is push-only: once notifications are enabled the wheel
    // continuously streams 0xA9 realtime + 0xB9 trip frames at its own
    // cadence. We must NOT periodically write 0x98 (queryLimits) to it;
    // WheelLog confirms the only outgoing 0x98 happens once during init when
    // local alarm values are still zero. Repeated 0x98 polls have been
    // observed to cause KS-16X to flash lights / chirp because some KS
    // firmwares interpret repeated alarm-limit reads as a re-configure
    // signal. Same push-only model as BegodeAdapter / VeteranAdapter.
    //
    // The only thing we ever want to send during the realtime loop is an
    // echo of an unsolicited 0xA4 settings frame that KingSong expects us
    // to bounce back; see [pendingEcho] / [onRawNotification].
    override fun pollRealtime(): ByteArray {
        val echo = pendingEcho
        if (echo != null) {
            pendingEcho = null
            return echo
        }
        return ByteArray(0)
    }

    override fun pollSettings(): ByteArray = ByteArray(0)

    override fun horn(): ByteArray = KingsongCommands.horn()
    override fun setLight(on: Boolean): ByteArray = KingsongCommands.setLight(on)

    /**
     * KingSong's `0x85` packet writes all four speed thresholds at once. We
     * treat [alarmKmh] as alarm 1 and slot the remaining alarms below the
     * tiltback ceiling so the user's single "alarm" slider produces a sane
     * three-stage chime ladder.
     */
    override fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray {
        val max = tiltbackKmh.toInt().coerceIn(0, 255)
        val a1 = alarmKmh.toInt().coerceIn(0, 255)
        val a2 = (a1 + ((max - a1) / 3)).coerceIn(a1, max)
        val a3 = (a1 + (2 * (max - a1) / 3)).coerceIn(a1, max)
        return KingsongCommands.setMaxSpeedAndAlarms(a1, a2, a3, max)
    }

    override fun setVolume(percent: Int): ByteArray? = null
    override fun setDRL(on: Boolean): ByteArray? = null
    override fun setLock(locked: Boolean): ByteArray? = null

    override fun requestAuthKey(): ByteArray? = null
    override fun verifyAuth(encryptedKey: ByteArray): ByteArray? = null

    override fun onRawNotification(rawBytes: ByteArray): List<DecodeResult> {
        if (rawBytes.size < 20) return emptyList()
        if (rawBytes[0] != 0xAA.toByte() || rawBytes[1] != 0x55.toByte()) return emptyList()

        val type = rawBytes[16].toInt() and 0xFF
        return when (type) {
            0xA9 -> {
                // Surface the realtime body so the Service Mode Inspect tab can
                // show it the same way it does V14 / P6 / Veteran. Use the
                // 14-byte payload at offsets 2..15 so the bytes match the
                // labelled offsets in docs/protocols/kingsong.md section 4.1.
                val body = rawBytes.copyOfRange(2, 16)
                DiagnosticsLogger.note(
                    "KingSong realtime len=${body.size} body=${body.joinToString(" ") { "%02x".format(it) }}"
                )
                val telem = KingsongParser.parseLiveTelemetry(rawBytes, detectedModel)
                    ?: return emptyList()
                lastTempA9 = telem.maxTemperature
                val combinedTemps = listOf(lastTempA9, lastTempB9).filter { it != 0f }
                lastTelemetry = telem.copy(
                    // Carry forward fields A9 doesn't ship so the dashboard
                    // doesn't flicker between live values and zeros while the
                    // F5 / F6 / B9 frames cycle through.
                    pwm = lastTelemetry.pwm,
                    tripDistance = lastTelemetry.tripDistance,
                    dynamicSpeedLimit = lastTelemetry.dynamicSpeedLimit,
                    temperatures = combinedTemps,
                    maxTemperature = combinedTemps.maxOrNull() ?: 0f,
                    // lightOn lives on B9 (echoed at byte 10). A9 is fresh
                    // from parseLiveTelemetry which defaults lightOn=false,
                    // so without this carry-forward every A9 frame (3x/sec)
                    // resets lightOn -> false, then the next B9 sets it
                    // back -> true, oscillating at frame interleave rate
                    // and spamming the TTS "lights on/off" announcement.
                    lightOn = lastTelemetry.lightOn
                )
                listOf(DecodeResult.Telemetry(lastTelemetry))
            }
            0xB9 -> {
                val trip = KingsongParser.parseTripFrame(rawBytes) ?: return emptyList()
                lastTempB9 = trip.maxTemperature
                val combinedTemps = listOf(lastTempA9, lastTempB9).filter { it != 0f }
                val echoedLight = KingsongParser.parseLightOn(rawBytes)
                lastTelemetry = lastTelemetry.copy(
                    tripDistance = trip.tripDistance,
                    dynamicSpeedLimit = trip.dynamicSpeedLimit,
                    temperatures = combinedTemps,
                    maxTemperature = combinedTemps.maxOrNull() ?: 0f,
                    lightOn = echoedLight ?: lastTelemetry.lightOn,
                    timestamp = trip.timestamp
                )
                listOf(DecodeResult.Telemetry(lastTelemetry))
            }
            0xBB -> {
                val name = KingsongParser.parseModelName(rawBytes) ?: return emptyList()
                val resolved = KingsongModel.fromReportedName(name)
                if (resolved != null) detectedModel = resolved
                val results = mutableListOf<DecodeResult>(
                    DecodeResult.ModelName(name, resolved)
                )
                KingsongParser.parseFirmwareVersion(rawBytes)?.let { fw ->
                    results += DecodeResult.Firmware(
                        display = "FW $fw",
                        mainBoard = fw,
                        driverBoard = "",
                        ble = ""
                    )
                }
                results
            }
            0xB3 -> {
                val serial = KingsongParser.parseSerial(rawBytes) ?: return emptyList()
                listOf(DecodeResult.ModelName(serial, detectedModel))
            }
            0xF5 -> {
                val cpu = KingsongParser.parseCpuPwm(rawBytes) ?: return emptyList()
                lastTelemetry = lastTelemetry.copy(pwm = cpu.pwm, timestamp = cpu.timestamp)
                listOf(DecodeResult.Telemetry(lastTelemetry))
            }
            0xF6 -> {
                val limit = KingsongParser.parseDynamicSpeedLimit(rawBytes) ?: return emptyList()
                lastTelemetry = lastTelemetry.copy(
                    dynamicSpeedLimit = limit.dynamicSpeedLimit,
                    timestamp = limit.timestamp
                )
                listOf(DecodeResult.Telemetry(lastTelemetry))
            }
            0xA4, 0xB5 -> {
                val settings = KingsongParser.parseAlarmsAndMaxSpeed(rawBytes) ?: return emptyList()
                if (KingsongParser.requiresAlarmEcho(rawBytes)) {
                    pendingEcho = KingsongParser.buildAlarmEcho(rawBytes)
                }
                listOf(DecodeResult.Settings(settings))
            }
            else -> emptyList()
        }
    }

    override fun onDisconnect() {
        detectedModel = null
        pendingEcho = null
        lastTelemetry = WheelData()
        lastTempA9 = 0f
        lastTempB9 = 0f
    }

    override fun inspectMessageTypes(): List<String> = listOf("KingSong realtime")

    /**
     * Service Mode catalogue for the KingSong family. Research-grade: a mix of
     * safe queries (so the user can dump identity, settings, and a BMS page
     * without writing anything) and writes that exercise documented control
     * paths (horn, light modes, pedal hardness, max-speed + alarms). Labels
     * are short and opcode-derived so a user can report "T98 returned alarms
     * = 25/35/45/55" and we know which packet that maps to.
     *
     * The catalogue intentionally omits power-off (`0x40`) and gyro
     * calibration (`0x89`): both are easy to fat-finger and have real-world
     * consequences if the user is mid-ride or the wheel is upright.
     *
     * Per docs/protocols/kingsong.md section 8 KingSong has no documented
     * lock command in the public protocol, so no lock toggle is offered.
     */
    override fun getDiagnosticCommands(): List<DiagnosticCommand> {
        val LIGHT = DiagnosticCommand.Category.LIGHT
        val MODE = DiagnosticCommand.Category.MODE
        val QUERY = DiagnosticCommand.Category.QUERY
        val HORN = DiagnosticCommand.Category.HORN

        return listOf(
            // --- Read-only queries: tap to inspect what the wheel returns ---
            DiagnosticCommand("Q98", "Read max-speed and alarm thresholds",
                KingsongCommands.queryLimits(), QUERY),
            DiagnosticCommand("Q9B", "Read model name and firmware version",
                KingsongCommands.queryName(), QUERY),
            DiagnosticCommand("Q63", "Read 17-char serial number",
                KingsongCommands.querySerial(), QUERY),
            DiagnosticCommand("QE1", "Read BMS1 serial",
                KingsongCommands.bmsQuery(KingsongCommands.Type.BMS1_SERIAL_REQ), QUERY),

            // --- Horn ---
            DiagnosticCommand("T88", "Beep the horn once",
                KingsongCommands.horn(), HORN),

            // --- Light: cycle off / on / auto ---
            DiagnosticCommand("T73_12", "Turn headlight off",
                KingsongCommands.setLightMode(0), LIGHT),
            DiagnosticCommand("T73_13", "Turn headlight on",
                KingsongCommands.setLightMode(1), LIGHT),
            DiagnosticCommand("T73_14", "Set headlight to auto mode",
                KingsongCommands.setLightMode(2), LIGHT),

            // --- Pedal hardness: flip between the documented extremes ---
            DiagnosticCommand("T87_00", "Set pedals to soft mode",
                KingsongCommands.setPedalMode(0), MODE),
            DiagnosticCommand("T87_02", "Set pedals to hard mode",
                KingsongCommands.setPedalMode(2), MODE),

            // --- Max-speed write at two safe values ---
            // Alarms slot below the tiltback ceiling, matching setMaxSpeed().
            DiagnosticCommand("T85_45", "Set tiltback 45 km/h, alarms 30/35/40",
                KingsongCommands.setMaxSpeedAndAlarms(30, 35, 40, 45), MODE),
            DiagnosticCommand("T85_60", "Set tiltback 60 km/h, alarms 40/46/53",
                KingsongCommands.setMaxSpeedAndAlarms(40, 46, 53, 60), MODE),
        )
    }
}
