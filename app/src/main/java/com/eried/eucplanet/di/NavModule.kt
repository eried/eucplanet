package com.eried.eucplanet.di

import com.eried.eucplanet.nav.AndroidNavHttp
import com.eried.eucplanet.nav.AndroidNavStrings
import com.eried.eucplanet.nav.AndroidNavigationHost
import com.eried.eucplanet.nav.AndroidOfflineNav
import com.eried.eucplanet.nav.CurrentRouteStore
import com.eried.eucplanet.nav.NavHttp
import com.eried.eucplanet.nav.NavStrings
import com.eried.eucplanet.nav.NavigationHost
import com.eried.eucplanet.nav.OfflineNav
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.nav.OcmService
import com.eried.eucplanet.nav.PoiService
import com.eried.eucplanet.nav.RoutingService
import com.eried.eucplanet.nav.offline.AndroidBrouterProfiles
import com.eried.eucplanet.nav.offline.OfflineRouter
import com.eried.eucplanet.nav.offline.OfflineTileStore
import com.eried.eucplanet.nav.offline.OfflineTiles
import com.eried.eucplanet.nav.offline.WorkManagerTileJobs
import com.eried.eucplanet.nav.offline.JvmOfflineFiles
import com.eried.eucplanet.nav.offline.map.OfflineMapRenderer
import com.eried.eucplanet.share.PendingShareJoin
import com.eried.eucplanet.share.ShareSession
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** Routing, navigation and live route sharing. */
val navModule = module {
    singleOf(::NavigationEngine)
    single { AndroidNavigationHost(androidContext(), get()) } bind NavigationHost::class
    singleOf(::RoutingService)
    singleOf(::AndroidNavHttp) bind NavHttp::class
    single { AndroidNavStrings(androidContext()) } bind NavStrings::class
    single { AndroidOfflineNav(androidContext(), get(), get(), get()) } bind OfflineNav::class
    single { offlineTileStore(androidContext()) }
    single {
        val store = get<OfflineTileStore>()
        OfflineRouter(engine = OfflineRouter.brouterEngine(store.dir, AndroidBrouterProfiles(androidContext(), store)))
    }
    single {
        OfflineTiles(get(), get(), get(), WorkManagerTileJobs(androidContext()), "EUCPlanet/${com.eried.eucplanet.BuildConfig.VERSION_NAME}")
    }
    single { OfflineMapRenderer(get<OfflineTileStore>().dir) }
    singleOf(::PoiService)
    singleOf(::OcmService)
    singleOf(::CurrentRouteStore)
    singleOf(::ShareSession) { bind<com.eried.eucplanet.share.LiveShare>() }
    singleOf(::PendingShareJoin)
    single<com.eried.eucplanet.ui.navigator.RouteBuilderPorts> {
        com.eried.eucplanet.ui.navigator.AndroidRouteBuilderPorts(androidContext(), get(), get())
    }
}

/** App storage, not backed up and removed with the app; internal when
 *  there is no external storage. */
private fun offlineTileStore(ctx: android.content.Context): OfflineTileStore {
    val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    val dir = java.io.File(base, "offline/routing").apply { mkdirs() }
    return OfflineTileStore(dir.path, JvmOfflineFiles())
}
