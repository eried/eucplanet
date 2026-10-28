package com.eried.eucplanet.di

import com.eried.eucplanet.nav.CurrentRouteStore
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.nav.OcmService
import com.eried.eucplanet.nav.PoiService
import com.eried.eucplanet.nav.RoutingService
import com.eried.eucplanet.nav.offline.OfflineRouter
import com.eried.eucplanet.nav.offline.OfflineTileStore
import com.eried.eucplanet.nav.offline.OfflineTiles
import com.eried.eucplanet.share.PendingShareJoin
import com.eried.eucplanet.share.ShareSession
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/** Routing, navigation and live route sharing. */
val navModule = module {
    singleOf(::NavigationEngine)
    singleOf(::RoutingService)
    single { OfflineTileStore.default(androidContext()) }
    single { OfflineRouter(engine = OfflineRouter.brouterEngine(androidContext(), get())) }
    singleOf(::OfflineTiles)
    singleOf(::PoiService)
    singleOf(::OcmService)
    singleOf(::CurrentRouteStore)
    singleOf(::ShareSession)
    singleOf(::PendingShareJoin)
}
