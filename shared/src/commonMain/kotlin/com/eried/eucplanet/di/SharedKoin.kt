package com.eried.eucplanet.di

import com.eried.eucplanet.ble.BegodeAdapter
import com.eried.eucplanet.ble.CompositeWheelAdapter
import com.eried.eucplanet.ble.InMotionV1Adapter
import com.eried.eucplanet.ble.InMotionV2Adapter
import com.eried.eucplanet.ble.KingsongAdapter
import com.eried.eucplanet.ble.NinebotAdapter
import com.eried.eucplanet.ble.VeteranAdapter
import com.eried.eucplanet.ble.WheelAdapter
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** One instance per wheel family, routed by [CompositeWheelAdapter]. */
val wheelAdapterModule = module {
    single { InMotionV2Adapter() }
    single { InMotionV1Adapter() }
    single { KingsongAdapter() }
    single { BegodeAdapter() }
    single { VeteranAdapter() }
    single { NinebotAdapter() }
    single { CompositeWheelAdapter(get(), get(), get(), get(), get(), get()) }
    single<WheelAdapter> { get<CompositeWheelAdapter>() }
}

/**
 * The shared object graph. It is its own Koin context, built on first use,
 * so neither app has to start it in a particular order. On Android, Hilt
 * hands these singletons out through SharedModule until Hilt is gone.
 */
object SharedKoin {
    val koin: Koin by lazy {
        koinApplication { modules(wheelAdapterModule) }.koin
    }
}
