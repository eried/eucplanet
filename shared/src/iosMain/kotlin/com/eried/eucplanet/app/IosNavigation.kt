package com.eried.eucplanet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.store.NavMarkerStore
import com.eried.eucplanet.nav.NavStrings
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.nav.NavigationHost
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.toByteArray
import com.eried.eucplanet.platform.toNSData
import com.eried.eucplanet.ui.dashboard.ActiveRoute
import com.eried.eucplanet.ui.dashboard.DashboardNavigation
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.StringResource
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * The navigation engine on the iPhone: CoreLocation's fixes ([IosLocation]),
 * the "navigating" GPS tier that keeps them coming with the phone locked
 * (the location background mode trips already use), and one thread of its own.
 * Android's is AndroidNavigationHost, over TripRepository and WheelService.
 */
internal object IosNavigationHost : NavigationHost {
    @OptIn(ExperimentalCoroutinesApi::class)
    override val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    override val fixes: Flow<PhoneLocation?> = IosLocation.fix

    override fun currentFix(): PhoneLocation? = IosLocation.fix.value

    override fun startLocationUpdates() = IosLocation.start()

    // Android brings WheelService to the foreground; on the iPhone the app
    // stays running in the background on its location updates, which the
    // navigating tier keeps on (setNavigating), so only the stream is asked for.
    override fun startNavigationService() = IosLocation.start()

    override fun setNavigating(on: Boolean) = IosGpsPower.setNavigating(on)
}

/** Strings for the engine and the router, outside composition, in the phone's language. */
internal object IosNavStrings : NavStrings {
    override fun text(res: StringResource, vararg args: Any): String = IosPhoneDevice.text(res, *args)
}

/** The leg being ridden, for the dashboard's map marker, as Android's AndroidActiveRoute. */
internal class IosActiveRoute(nav: NavigationEngine) : ActiveRoute {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val activeWaypoints: StateFlow<List<Waypoint>?> = nav.activeLeg.map { it?.waypoints }
        .stateIn(scope, SharingStarted.Eagerly, nav.activeLeg.value?.waypoints)
}

/** The dashboard's navigation overlay, over the shared view model, as AndroidDashboardHost builds it. */
@Composable
internal fun rememberIosDashboardNavigation(): DashboardNavigation {
    val vm = rememberViewModel { IosAppGraph.navigationOverlayViewModel() }
    return remember(vm) {
        object : DashboardNavigation {
            override val navState = vm.navState
            override val currentRoute = vm.currentRoute
            override fun requestPopup() = vm.requestPopup()
            override fun endNavigation() = vm.endNavigation()
            override fun startCurrentRoute() = vm.startCurrentRoute()
        }
    }
}

/**
 * The rider's marker photo as one PNG in Application Support, in a folder
 * excluded from backup, as Android keeps it in noBackupFilesDir: it survives
 * updates, and a new phone starts without it.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosMarkerFile : NavMarkerStore.MarkerFile {
    private val path: String by lazy {
        val base = NSFileManager.defaultManager.URLForDirectory(
            NSApplicationSupportDirectory, NSUserDomainMask, null, true, null,
        ) as NSURL
        val dir = base.URLByAppendingPathComponent("navigator", isDirectory = true)!!
        NSFileManager.defaultManager.createDirectoryAtURL(dir, true, null, null)
        if (!dir.setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null)) {
            Log.w("NavMarker", "could not exclude the marker folder from backup")
        }
        "${dir.path!!}/${NavMarkerStore.MARKER_FILE}"
    }

    override fun read(): ByteArray? = NSData.dataWithContentsOfFile(path)?.toByteArray()

    override fun write(bytes: ByteArray) {
        check(bytes.toNSData().writeToFile(path, atomically = true)) { "marker not written" }
    }

    override fun delete() {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
}
