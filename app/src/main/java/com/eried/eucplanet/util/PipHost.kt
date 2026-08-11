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
     * Set while a screen is on that must not be swapped out for the PIP face.
     *
     * This used to be the opposite - PIP was allowed only from the dashboard,
     * on the reasoning that shrinking a settings list into a floating window
     * helps nobody. That reasoning was wrong about this app: entering PIP
     * replaces the whole composition with [com.eried.eucplanet.ui.pip.PipSimple]
     * or PipDashboard, so the settings list is never what ends up in the little
     * window. All the gate achieved was to break PIP at the one moment anybody
     * tests it - right after switching it on, from the settings screen it was
     * switched on in.
     *
     * The Overlay Studio is the real exception, and the reason is concrete
     * rather than aesthetic: PIP drops the rest of the tree from composition,
     * which would take the camera and an in-progress recording with it.
     */
    @Volatile
    var suppressPip: Boolean = false
}
