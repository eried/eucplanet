package com.eried.eucplanet.ble

/**
 * BLE GATT profile a wheel adapter binds to. Each protocol family advertises a
 * different combination: InMotion V2 uses Nordic UART, InMotion V1 uses the
 * proprietary InMotion service, KingSong uses 0xFFE0/FFE1, Gotway/Veteran also
 * use 0xFFE0/FFE1 (disambiguated by first packet bytes after connect).
 *
 * The connection manager reads this from the active adapter on service discovery.
 *
 * UUIDs are lowercase-canonical strings — commonMain has no `java.util.UUID`, so
 * the platform BLE transport converts them to its native UUID / CBUUID type.
 * [writeType] is platform-independent (see [BleWriteType]).
 */
data class BleProfile(
    val serviceUuid: String,
    /** Characteristic the adapter writes commands to. */
    val writeCharacteristic: String,
    /** Characteristic the wheel sends notifications on. */
    val notifyCharacteristic: String,
    /** GATT write type for outbound commands on this profile. */
    val writeType: BleWriteType = BleWriteType.DEFAULT
) {
    companion object {
        /** Nordic UART used by the InMotion V2 family (V11/V12/V13/V14). */
        val NORDIC_UART = BleProfile(
            serviceUuid = "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            writeCharacteristic = "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
            notifyCharacteristic = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
        )

        /** Garmin Varia rear-view radar (notify-only `…3203`). The transport
         *  requires a write char, so we point it at the notify char too — radar
         *  is read-only and we never write to it. */
        val VARIA_RADAR = BleProfile(
            serviceUuid = "6a4e3200-667b-11e3-949a-0800200c9a66",
            writeCharacteristic = "6a4e3203-667b-11e3-949a-0800200c9a66",
            notifyCharacteristic = "6a4e3203-667b-11e3-949a-0800200c9a66"
        )

        /**
         * HM-10 / JNHuaMao profile shared by KingSong, Begode/Gotway and
         * Veteran wheels. Same service+characteristic UUIDs across all three
         * brands; the wheel is identified post-connect by sniffing the first
         * frame's magic bytes (`AA 55` = KingSong, `55 AA` = Begode,
         * `DC 5A 5C` = Veteran). Writes use NO_RESPONSE to match WheelLog.
         */
        val HM10 = BleProfile(
            serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
            writeCharacteristic = "0000ffe1-0000-1000-8000-00805f9b34fb",
            notifyCharacteristic = "0000ffe1-0000-1000-8000-00805f9b34fb",
            writeType = BleWriteType.NO_RESPONSE
        )

        /**
         * InMotion V1 (V5 / V8 / V10 / L6 / R-series / V3): proprietary 0xFFEx
         * profile split across two services. Notify 0xFFE4 under service 0xFFE0;
         * write 0xFFE9 under service 0xFFE5. See docs/protocols/inmotion_v1.md.
         */
        val INMOTION_V1 = BleProfile(
            serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
            writeCharacteristic = "0000ffe9-0000-1000-8000-00805f9b34fb",
            notifyCharacteristic = "0000ffe4-0000-1000-8000-00805f9b34fb"
        )
    }
}
