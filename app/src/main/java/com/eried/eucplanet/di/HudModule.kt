package com.eried.eucplanet.di

import android.util.Log
import com.eried.eucplanet.hud.protocol.HudDiscovery
import com.eried.eucplanet.nav.offline.map.OfflineMapRenderer
import com.eried.eucplanet.service.hud.AndroidHudMdns
import com.eried.eucplanet.service.hud.AndroidHudNetwork
import com.eried.eucplanet.service.hud.AndroidHudPhone
import com.eried.eucplanet.service.hud.AndroidHudSockets
import com.eried.eucplanet.service.hud.HudAppSender
import com.eried.eucplanet.service.hud.HudBeacons
import com.eried.eucplanet.service.hud.HudCommandSink
import com.eried.eucplanet.service.hud.HudMdns
import com.eried.eucplanet.service.hud.HudNetwork
import com.eried.eucplanet.service.hud.HudPhone
import com.eried.eucplanet.service.hud.HudServer
import com.eried.eucplanet.service.hud.HudSockets
import com.eried.eucplanet.service.hud.HudSubnetProbe
import com.eried.eucplanet.service.hud.HudTileServer
import com.eried.eucplanet.service.hud.HudTiles
import com.eried.eucplanet.service.hud.HudUdpListener
import com.eried.eucplanet.service.overlay.PhoneHudWindow
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * The MotoEye HUD link and the on-phone HUD overlay. The link itself
 * ([HudServer], its sink and its subnet probe) is shared with the iPhone;
 * this module gives it the Android side of its ports.
 */
val hudModule = module {
    singleOf(::HudCommandSink)
    singleOf(::HudServer)
    singleOf(::HudSubnetProbe)
    singleOf(::HudUdpListener) bind HudBeacons::class
    singleOf(::PhoneHudWindow)

    singleOf(::AndroidHudSockets) bind HudSockets::class
    singleOf(::AndroidHudMdns) bind HudMdns::class
    singleOf(::AndroidHudNetwork) bind HudNetwork::class
    singleOf(::AndroidHudPhone) bind HudPhone::class
    // The HUD's map pictures, served while the link is on. For a layer the
    // phone answers as its own maps would; with none, the offline map.
    single<HudTiles> {
        val offlineMap = get<OfflineMapRenderer>()
        val context = androidContext()
        HudTileServer(
            HudDiscovery.TILE_PORT,
            { z, x, y, layer ->
                if (layer == null) offlineMap.png(z, x, y, 256)
                else com.eried.eucplanet.map.MapTileCache.layerTile(context, layer, z, x, y)
            },
            { Log.w("HudServer", it) },
        )
    }
    // Hands the HUD app to a HUD that lacks it or runs an older one. Android
    // only, so it sits beside the link rather than in it. The peer is read
    // lazily: the link's phone port holds this sender.
    single {
        val koin = getKoin()
        HudAppSender(get(), get<AndroidHudSockets>().http) { koin.get<HudServer>().peer.value }
    }
}
