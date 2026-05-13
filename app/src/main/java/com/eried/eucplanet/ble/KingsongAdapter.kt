package com.eried.eucplanet.ble

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
 * https://github.com/Wheellog/wheellog.android — GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
@Singleton
class KingsongAdapter @Inject constructor() : WheelAdapter {
    override val familyId = "kingsong"
    override val familyDisplayName = "KingSong"
    override val capabilities = WheelCapabilities.KINGSONG

    @Volatile private var detectedModel: KingsongModel? = null

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

    override fun pollRealtime(): ByteArray {
        val echo = pendingEcho
        if (echo != null) {
            pendingEcho = null
            return echo
        }
        return KingsongCommands.queryLimits()
    }

    override fun pollSettings(): ByteArray = KingsongCommands.queryLimits()

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
                if (telem != null) listOf(DecodeResult.Telemetry(telem)) else emptyList()
            }
            0xB9 -> {
                val trip = KingsongParser.parseTripFrame(rawBytes)
                if (trip != null) listOf(DecodeResult.Telemetry(trip)) else emptyList()
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
                val cpu = KingsongParser.parseCpuPwm(rawBytes)
                if (cpu != null) listOf(DecodeResult.Telemetry(cpu)) else emptyList()
            }
            0xF6 -> {
                val limit = KingsongParser.parseDynamicSpeedLimit(rawBytes)
                if (limit != null) listOf(DecodeResult.Telemetry(limit)) else emptyList()
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
