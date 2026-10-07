package com.eried.eucplanet.ui.charging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Open the charging monitor", handed from a charge alert to the nav graph.
 *
 * A notification can only start the activity, so the intent extra lands here
 * and MainActivity navigates once its graph exists. Process-scoped plain state:
 * a request that outlived the process would open the monitor on some unrelated
 * launch, hours after the pack finished.
 */
object ChargingMonitorLaunch {

    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    /** Read once, so coming back from another screen does not bounce the rider
     *  into the monitor again. */
    fun consume(): Boolean {
        val v = _pending.value
        _pending.value = false
        return v
    }
}
