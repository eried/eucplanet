package com.eried.eucplanet.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.repository.TripMeterFixes
import com.eried.eucplanet.data.repository.TripMeterRepository
import com.eried.eucplanet.data.store.TripMeterStore
import com.eried.eucplanet.ui.dashboard.TripMeterFeed
import com.eried.eucplanet.util.TripCsv
import okio.Path.Companion.toPath

/**
 * The trip meter on iOS: the shared repository, counting while a wheel is
 * connected as on Android, kept in Documents/datastore under Android's file
 * name. Built at launch (MainViewController) so it counts with the
 * dashboard closed, as WheelService builds it on Android.
 */
internal object IosTripMeter {
    val meter: TripMeterRepository by lazy {
        TripMeterRepository(
            store = TripMeterStore(
                PreferenceDataStoreFactory.createWithPath(
                    produceFile = { "${IosAppGraph.documents}/datastore/eucplanet_trip_meter.preferences_pb".toPath() },
                ),
            ),
            wheelRepositoryLazy = lazy { IosAppGraph.wheel },
            fixes = IosTripMeterFixes,
            settingsRepository = IosAppGraph.settings,
        )
    }

    val feed: TripMeterFeed = object : TripMeterFeed {
        override val state get() = meter.state
    }
}

/**
 * CoreLocation's fix; distance is haversine, where Android's Location is
 * ellipsoidal (well under 1% apart), as the trip recorder on iOS measures it.
 */
internal object IosTripMeterFixes : TripMeterFixes {
    override fun currentFix(): PhoneLocation? = IosLocation.fix.value
    override fun distanceMeters(from: PhoneLocation, to: PhoneLocation): Float =
        TripCsv.haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude).toFloat()
}
