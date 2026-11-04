package com.eried.eucplanet.di

import com.eried.eucplanet.service.hud.HudCommandSink
import com.eried.eucplanet.service.hud.HudServer
import com.eried.eucplanet.service.hud.HudSubnetProbe
import com.eried.eucplanet.service.hud.HudUdpListener
import com.eried.eucplanet.service.overlay.PhoneHudWindow
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/** The MotoEye HUD link and the on-phone HUD overlay. */
val hudModule = module {
    singleOf(::HudCommandSink)
    singleOf(::HudServer)
    singleOf(::HudSubnetProbe)
    singleOf(::HudUdpListener)
    singleOf(::PhoneHudWindow)
}
