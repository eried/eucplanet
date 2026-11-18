package com.eried.eucplanet.di

import com.eried.eucplanet.ble.BegodeAdapter
import com.eried.eucplanet.ble.BleConnectionManager
import com.eried.eucplanet.ble.BleScanner
import com.eried.eucplanet.ble.CompositeWheelAdapter
import com.eried.eucplanet.ble.InMotionV1Adapter
import com.eried.eucplanet.ble.InMotionV2Adapter
import com.eried.eucplanet.ble.KingsongAdapter
import com.eried.eucplanet.ble.NinebotAdapter
import com.eried.eucplanet.ble.VeteranAdapter
import com.eried.eucplanet.ble.WheelAdapter
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Binds the active [WheelAdapter] implementation to [CompositeWheelAdapter],
 * which holds one sub-adapter per BLE-protocol family (InMotion V2, InMotion
 * V1, KingSong, Begode/Gotway, Veteran, Ninebot) and routes by BLE-advertised
 * name on connect.
 */
val bleModule = module {
    singleOf(::InMotionV2Adapter)
    singleOf(::InMotionV1Adapter)
    singleOf(::KingsongAdapter)
    singleOf(::BegodeAdapter)
    singleOf(::VeteranAdapter)
    singleOf(::NinebotAdapter)
    singleOf(::CompositeWheelAdapter) { bind<WheelAdapter>() }
    singleOf(::BleScanner)
    singleOf(::BleConnectionManager)
}
