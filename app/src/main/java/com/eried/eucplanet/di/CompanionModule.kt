package com.eried.eucplanet.di

import com.eried.eucplanet.amazfit.AmazfitBridge
import com.eried.eucplanet.amazfit.AmazfitInbox
import com.eried.eucplanet.garmin.GarminBridge
import com.eried.eucplanet.wear.WatchVibrator
import com.eried.eucplanet.wear.WearBridge
import com.eried.eucplanet.wear.WearMapBridge
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/** The watch companions: Wear OS, Garmin and Amazfit. */
val companionModule = module {
    singleOf(::WearBridge)
    singleOf(::WearMapBridge)
    singleOf(::WatchVibrator)
    singleOf(::GarminBridge)
    singleOf(::AmazfitBridge)
    singleOf(::AmazfitInbox)
}
