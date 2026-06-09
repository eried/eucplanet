package com.eried.eucplanet.ble

/**
 * GATT write type for outbound commands, platform-independent. The BLE transport
 * maps this to the native write constant (Android
 * `BluetoothGattCharacteristic.WRITE_TYPE_*`, iOS `CBCharacteristicWriteType`).
 *
 * HM-10 modules (KingSong / Begode / Veteran) need [NO_RESPONSE] to match
 * WheelLog — they don't reliably ACK with-response writes. Nordic-UART families
 * (InMotion V2 / V1) keep the safer [DEFAULT].
 */
enum class BleWriteType { DEFAULT, NO_RESPONSE }
