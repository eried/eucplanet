package com.eried.eucplanet.di

import com.eried.eucplanet.nav.AndroidNavHttp
import com.eried.eucplanet.nav.AndroidNavStrings
import com.eried.eucplanet.nav.AndroidOfflineNav
import com.eried.eucplanet.nav.CurrentRouteStore
import com.eried.eucplanet.nav.NavHttp
import com.eried.eucplanet.nav.NavStrings
import com.eried.eucplanet.nav.OfflineNav
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.nav.OcmService
import com.eried.eucplanet.nav.PoiService
import com.eried.eucplanet.nav.RoutingService
import com.eried.eucplanet.nav.offline.OfflineRouter
import com.eried.eucplanet.nav.offline.OfflineTileStore
import com.eried.eucplanet.nav.offline.OfflineTiles
import com.eried.eucplanet.nav.offline.map.OfflineMapRenderer
import com.eried.eucplanet.share.PendingShareJoin
import com.eried.eucplanet.share.ShareSession
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** Routing, navigation and live route sharing. */
val navModule = module {
    singleOf(::NavigationEngine)
    singleOf(::RoutingService)
    singleOf(::AndroidNavHttp) bind NavHttp::class
    single { AndroidNavStrings(androidContext()) } bind NavStrings::class
    single { AndroidOfflineNav(androidContext(), get(), get(), get()) } bind OfflineNav::class
    single { OfflineTileStore.default(androidContext()) }
    single { OfflineRouter(engine = OfflineRouter.brouterEngine(androidContext(), get())) }
    singleOf(::OfflineTiles)
    single { OfflineMapRenderer(get<OfflineTileStore>().dir) }
    singleOf(::PoiService)
    singleOf(::OcmService)
    singleOf(::CurrentRouteStore)
    singleOf(::ShareSession)
    singleOf(::PendingShareJoin)
}
