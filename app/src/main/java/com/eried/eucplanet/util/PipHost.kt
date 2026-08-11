package com.eried.eucplanet.util

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The two facts the activity and the UI have to agree on for picture-in-picture.
 *
 * A plain object rather than a view model: the activity learns about PIP in a
 * lifecycle callback and the UI has to react in composition, and the dashboard
 * has to tell the activity it is on screen before the rider swipes away. A
 * shared singleton is the smallest thing that spans both, in the same spirit as
 * [AppForeground].
 */
object PipHost {

    /** True while the activity is showing inside the PIP window. */
    val inPip = MutableStateFlow(false)

    /**
     * True while the dashboard is the visible screen.
     *
     * onUserLeaveHint fires wherever the rider happens to be, and shrinking a
     * settings list into a floating window helps nobody. Set by the dashboard
     * while it is composed.
     */
    @Volatile
    var dashboardVisible: Boolean = false
}
