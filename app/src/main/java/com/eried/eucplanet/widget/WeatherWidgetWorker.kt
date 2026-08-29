package com.eried.eucplanet.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.weather.WeatherRepository
import com.eried.eucplanet.weather.WeatherSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Fetches the forecast for the home screen widgets and writes what they draw.
 *
 * A widget provider is a broadcast receiver: ten seconds of life, no network
 * allowed to block it, and usually no app process around it. So nothing is
 * fetched there. This runs the fetch, scores the hours the same way the panel
 * does, and leaves a [WeatherSnapshot] behind for the launcher to paint.
 *
 * ## Where the coordinates come from
 *
 * A cold worker has no location flow to read, and asking for a fix in the
 * background needs a permission most riders will not have granted. So it takes
 * the cheap last-known fix if the system already has one, and otherwise reuses
 * the coordinates of the last successful fetch, which the app writes whenever
 * the rider opens the panel. A widget on a phone that never had location on
 * says it has nothing rather than guessing.
 */
@HiltWorker
class WeatherWidgetWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val weatherRepository: WeatherRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        // Nothing placed: the rider removed the last widget between the job
        // being scheduled and it running.
        if (!WeatherWidgetBase.anyPlaced(ctx)) {
            cancelPeriodic(ctx)
            return Result.success()
        }

        val settings = settingsRepository.get()
        val prev = WeatherSnapshot.load(ctx)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val where = locate(ctx, prev)
        if (where == null) {
            WeatherSnapshot.save(ctx, prev.copy(failed = !prev.hasData))
            WeatherWidgetBase.renderAll(ctx)
            return Result.success()
        }
        val (lat, lon) = where

        val w = settings.weather
        weatherRepository.ensureFresh(
            lat, lon,
            WeatherSource.byId(w.source),
            force = force,
            // Same rule as the panel: a short window asks for the finer steps.
            fine = w.windowHours <= 12,
        )
        val forecast = weatherRepository.forecast.value
        if (forecast == null || forecast.hours.isEmpty()) {
            WeatherSnapshot.save(ctx, prev.copy(failed = !prev.hasData))
            WeatherWidgetBase.renderAll(ctx)
            // A failed fetch is worth one retry: the rider may have tapped
            // refresh while walking through a dead spot.
            return if (force) Result.retry() else Result.success()
        }

        // Keep whatever place name the app last resolved: a background
        // fetch has no geocoder budget, and a widget that blanks its location
        // on every refresh looks broken.
        WeatherSnapshot.save(
            ctx,
            WeatherWidgetPublisher.build(ctx, forecast, settings, prev.place)
                .copy(lat = lat, lon = lon),
        )
        WeatherWidgetBase.renderAll(ctx)
        return Result.success()
    }

    /** A cheap fix if the system has one, else where we last fetched. */
    private fun locate(ctx: Context, prev: WeatherSnapshot): Pair<Double, Double>? {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val fix = try {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                    .asSequence()
                    .mapNotNull { p -> runCatching { lm?.getLastKnownLocation(p) }.getOrNull() }
                    .maxByOrNull { it.time }
            } catch (_: SecurityException) {
                null
            }
            if (fix != null) return fix.latitude to fix.longitude
        }
        return if (prev.lat != 0.0 || prev.lon != 0.0) prev.lat to prev.lon else null
    }

    companion object {
        private const val UNIQUE_ONE_SHOT = "weather_widget_refresh"
        private const val UNIQUE_PERIODIC = "weather_widget_periodic"
        private const val KEY_FORCE = "force"

        /**
         * Refresh now, and keep the periodic job in step with whether any
         * widget is placed.
         *
         * [force] comes from the rider tapping the refresh button: it bypasses
         * the repository's freshness check. Automatic calls do not, so a
         * launcher that re-inflates its widgets ten times on boot causes at
         * most one fetch.
         */
        fun requestRefresh(context: Context, force: Boolean) {
            if (!WeatherWidgetBase.anyPlaced(context)) {
                cancelPeriodic(context)
                return
            }
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork(
                UNIQUE_ONE_SHOT,
                // A forced refresh replaces a queued automatic one; an
                // automatic one never displaces a rider's tap.
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                    .setInputData(Data.Builder().putBoolean(KEY_FORCE, force).build())
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
            schedulePeriodic(context)
        }

        /** Hourly is as often as a forecast changes for a rider's purposes,
         *  and WorkManager will not run a periodic job more often than 15
         *  minutes anyway. */
        private fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WeatherWidgetWorker>(1, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build(),
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
        }
    }
}
