package com.eried.eucplanet.share

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds a share link the rider opened from outside the app (an App Link on
 * https://eucplanet.ried.no/share#..., i.e. a tapped link or a scanned QR)
 * until the navigator can ask them whether to join that group.
 *
 * Singleton scope for the same reason as
 * [com.eried.eucplanet.data.repository.IncomingShareRepository]: the link is
 * read in MainActivity's intent handling and consumed by the nav-graph-scoped
 * RouteBuilderViewModel, which the activity has no handle on.
 */
@Singleton
class PendingShareJoin @Inject constructor() {

    private val _pending = MutableStateFlow<ShareLink?>(null)
    val pending: StateFlow<ShareLink?> = _pending.asStateFlow()

    fun offer(link: ShareLink) { _pending.value = link }

    fun clear() { _pending.value = null }
}
