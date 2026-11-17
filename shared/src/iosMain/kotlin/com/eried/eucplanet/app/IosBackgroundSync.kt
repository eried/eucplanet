package com.eried.eucplanet.app

import com.eried.eucplanet.platform.Log
import kotlin.concurrent.Volatile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

/**
 * Dropbox and leaderboard uploads while the app is not open: Android's
 * periodic DropboxSyncWorker and EucStatsUploadWorker. iOS has no
 * WorkManager; the app asks for background time (BGTaskScheduler) each time
 * it leaves the screen, and iOS wakes it when it chooses, never sooner than
 * the pending-upload interval. Each wake runs one quiet Dropbox pass and one
 * leaderboard sweep, then asks for the next.
 *
 * Two requests, the same work: an app refresh (about 30 s, the common one)
 * and a processing task (minutes, needs a connection, usually while the phone
 * charges) for a backlog the short one cannot finish.
 *
 * What iOS does not do: it runs neither once the rider swipes the app away,
 * until they open it again, and it decides when, from how the app is used.
 * The identifiers are listed in Info.plist (BGTaskSchedulerPermittedIdentifiers).
 */
object IosBackgroundSync {
    private const val TAG = "IosBackgroundSync"
    const val REFRESH_ID = "com.eried.eucplanet.ios.sync.refresh"
    const val PROCESSING_ID = "com.eried.eucplanet.ios.sync.processing"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    // iOS ends the app on a request whose handler it does not hold.
    @Volatile
    private var registered = false

    /**
     * Registers the two handlers and asks again each time the app leaves the
     * screen. iOS takes handlers only before the launch finishes, so this is
     * called from the app's start (IosAppStart), never later.
     */
    fun start() {
        if (started) return
        started = true
        val scheduler = BGTaskScheduler.sharedScheduler
        registered = listOf(REFRESH_ID, PROCESSING_ID).map { id ->
            scheduler.registerForTaskWithIdentifier(id, usingQueue = null) { task ->
                if (task != null) handle(task)
            }
        }.all { it }
        if (!registered) Log.w(TAG, "The tasks are not in Info.plist, no background sync")
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue,
        ) { _ ->
            scope.launch { request() }
        }
    }

    /** iOS woke the app for [task]: ask for the next, do the work, report. */
    private fun handle(task: BGTask) {
        val job = scope.launch {
            request()
            val done = try {
                run("task ${task.identifier}")
            } catch (e: CancellationException) {
                false
            }
            task.setTaskCompletedWithSuccess(done)
        }
        // Out of time: the pass stops where it is and the next one carries on.
        task.expirationHandler = { job.cancel() }
    }

    /** One Dropbox pass and one leaderboard sweep, side by side. True when nothing is left waiting. */
    private suspend fun run(why: String): Boolean = coroutineScope {
        Log.i(TAG, "Background sync ($why)")
        val dropbox = async { IosDropboxSync.passNow() }
        val leaderboard = async { IosEucStats.sweepOnce() }
        val d = dropbox.await()
        val l = leaderboard.await()
        Log.i(TAG, "Background sync done: dropbox=${if (d) "done" else "retry"}, leaderboard=${if (l) "done" else "retry"}")
        d && l
    }

    /** Asks iOS for both tasks, or for neither when there is nothing to upload to. */
    private suspend fun request() {
        if (!registered) return
        val settings = IosAppGraph.settings.get()
        val scheduler = BGTaskScheduler.sharedScheduler
        if (settings.dropboxAccessToken.isBlank() && !IosEucStats.uploadsOn(settings)) {
            scheduler.cancelAllTaskRequests()
            return
        }
        // Android's floor for periodic work is 15 minutes; so is this one.
        val earliest = NSDate.dateWithTimeIntervalSinceNow(settings.pendingUploadIntervalMin.coerceAtLeast(15) * 60.0)
        submit(BGAppRefreshTaskRequest(REFRESH_ID).apply { earliestBeginDate = earliest })
        submit(
            BGProcessingTaskRequest(PROCESSING_ID).apply {
                earliestBeginDate = earliest
                requiresNetworkConnectivity = true
                requiresExternalPower = false
            }
        )
    }

    /** A request with the same identifier replaces the one waiting. */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun submit(request: BGTaskRequest) = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val ok = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)
        // The simulator, and Background App Refresh turned off in Settings, land here.
        if (!ok) Log.i(TAG, "${request.identifier} not scheduled: ${error.value?.localizedDescription}")
    }

    /**
     * EUC_BG_SYNC=<secs>: runs what a background task runs, [secs] after
     * launch, for the simulator, where iOS schedules none (docs/IOS_SYNC.md).
     */
    internal fun runForHook(secs: Long) {
        scope.launch {
            delay(secs * 1000)
            request()
            run("EUC_BG_SYNC")
        }
    }
}
