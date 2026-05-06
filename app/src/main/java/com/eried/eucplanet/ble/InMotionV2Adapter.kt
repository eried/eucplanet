package com.eried.eucplanet.ble

import javax.inject.Inject
import javax.inject.Singleton

/**
 * WheelAdapter for the InMotion V2 protocol family — V11, V12HS/HT/PRO/S, V13, V14.
 *
 * Phase 1 wraps the existing [InMotionV2Commands] / [InMotionV2Parser] / [InMotionV2Protocol]
 * objects without changing their behavior. Phase 2 will fold model-conditional logic
 * (V11 vs V12 vs V14 parsers, beep vs sound horn, short vs extended max-speed packet)
 * into this class once those models actually need it.
 *
 * The decode dispatch mirrors the legacy WheelRepository.handlePacket() switch
 * exactly so the refactor is provably no-op for V14.
 */
@Singleton
class InMotionV2Adapter @Inject constructor() : WheelAdapter {

    override val familyId: String = "inmotion_v2"
    override val capabilities: WheelCapabilities = WheelCapabilities.INMOTION_V2

    /**
     * Detected model from the wheel's MainInfo response. Set the first time
     * [decode] sees a CarType packet on each connection. Read by per-model
     * command dispatch (Phase 3+) — Phase 2 just exposes it for the UI.
     *
     * Volatile because decode runs on the BLE coroutine and reads happen from
     * the main thread. Cleared to null by the repository on disconnect.
     */
    @Volatile var detectedModel: InMotionV2Model? = null
        private set

    override fun initSequence(): List<ByteArray> = listOf(
        InMotionV2Commands.getCarType(),
        InMotionV2Commands.getSerialNumber(),
        InMotionV2Commands.getVersions(),
        InMotionV2Commands.getCurrentSettings(),
        InMotionV2Commands.getUselessData(),
        InMotionV2Commands.getStatistics()
    )

    override fun pollRealtime(): ByteArray = InMotionV2Commands.getRealTimeData()
    override fun pollSettings(): ByteArray = InMotionV2Commands.getCurrentSettings()

    override fun horn(): ByteArray = InMotionV2Commands.horn()
    override fun setLight(on: Boolean): ByteArray = InMotionV2Commands.setLight(on)
    override fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray =
        InMotionV2Commands.setMaxSpeedV14(tiltbackKmh, alarmKmh)
    override fun setVolume(percent: Int): ByteArray = InMotionV2Commands.setVolume(percent)
    override fun setDRL(on: Boolean): ByteArray = InMotionV2Commands.setDRL(on)
    override fun setLock(locked: Boolean): ByteArray = InMotionV2Commands.setLock(locked)

    override fun requestAuthKey(): ByteArray = InMotionV2Commands.requestAuthKey()
    override fun verifyAuth(encryptedKey: ByteArray): ByteArray =
        InMotionV2Commands.verifyAuth(encryptedKey)

    override fun decode(command: Byte, data: ByteArray): DecodeResult {
        return when (command.toInt() and 0x7F) {
            0x02 -> decodeMainInfoOrAuth(data)
            0x04 -> InMotionV2Parser.parseTelemetry(data)?.let { DecodeResult.Telemetry(it) } ?: DecodeResult.Unknown
            0x11 -> InMotionV2Parser.parseTotalStats(data)?.let { DecodeResult.TotalDistance(it.totalDistanceKm) } ?: DecodeResult.Unknown
            0x20 -> InMotionV2Parser.parseSettings(data)?.let { DecodeResult.Settings(it) } ?: DecodeResult.Unknown
            else -> DecodeResult.Unknown
        }
    }

    /**
     * Command 0x02 carries both MainInfo subtypes (carType / firmware / serial)
     * and auth responses (routing 0x80). The first byte distinguishes them.
     */
    private fun decodeMainInfoOrAuth(data: ByteArray): DecodeResult {
        if (data.isEmpty()) return DecodeResult.Unknown
        return when (data[0].toInt() and 0xFF) {
            0x01 -> {
                // Car type
                val info = InMotionV2Parser.parseCarType(data.copyOfRange(1, data.size))
                if (info != null) {
                    detectedModel = info.model
                    DecodeResult.ModelName(info.modelName, info.model)
                } else DecodeResult.Unknown
            }
            0x06 -> {
                // Firmware versions
                val fw = InMotionV2Parser.parseVersions(data.copyOfRange(1, data.size))
                if (fw != null) DecodeResult.Firmware(
                    display = fw.displayString,
                    mainBoard = fw.mainBoardVersion,
                    driverBoard = fw.driverBoardVersion,
                    ble = fw.bleVersion
                ) else DecodeResult.Unknown
            }
            0x80 -> {
                // Auth response: data = [0x80, sub_cmd, payload...]
                if (data.size < 2) return DecodeResult.Unknown
                when (data[1].toInt() and 0xFF) {
                    0x02 -> {
                        // Auth key: 16 bytes encrypted password starting at data[2]
                        if (data.size >= 18) DecodeResult.AuthKey(data.copyOfRange(2, 18))
                        else DecodeResult.Unknown
                    }
                    0x82 -> {
                        // Auth verify result: data[2] == 0x01 means success
                        DecodeResult.AuthConfirm(data.size >= 3 && data[2].toInt() == 0x01)
                    }
                    else -> DecodeResult.Unknown
                }
            }
            else -> DecodeResult.Unknown
        }
    }
}
