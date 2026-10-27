package com.eried.eucplanet.di

import com.eried.eucplanet.nav.CurrentRouteStore
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.nav.OcmService
import com.eried.eucplanet.nav.PoiService
import com.eried.eucplanet.nav.RoutingService
import com.eried.eucplanet.share.PendingShareJoin
import com.eried.eucplanet.share.ShareSession
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/** Routing, navigation and live route sharing. */
val navModule = module {
    singleOf(::NavigationEngine)
    singleOf(::RoutingService)
    singleOf(::PoiService)
    singleOf(::OcmService)
    singleOf(::CurrentRouteStore)
    singleOf(::ShareSession)
    singleOf(::PendingShareJoin)
}
