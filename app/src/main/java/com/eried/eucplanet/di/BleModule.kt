package com.eried.eucplanet.di

import com.eried.eucplanet.ble.BleConnectionManager
import com.eried.eucplanet.ble.BleScanner
import com.eried.eucplanet.ble.CompositeWheelAdapter
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * The Android BLE link. The wheel decoders, [CompositeWheelAdapter] and its
 * per-family adapters, come from the shared [wheelAdapterModule].
 */
val bleModule = module {
    singleOf(::BleScanner)
    singleOf(::BleConnectionManager)
}
