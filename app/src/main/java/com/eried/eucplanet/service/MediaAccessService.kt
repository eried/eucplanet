package com.eried.eucplanet.service

import android.service.notification.NotificationListenerService

/**
 * Exists only so the app can be granted notification access.
 *
 * Reading or posting notifications is not the point and this listens to
 * nothing: `MediaSessionManager.getActiveSessions()` refuses any caller that
 * is not an enabled notification listener, and that call is the only way to
 * reach another app's media session to set its playback rate. Pause and
 * resume need none of this, because a media KEY goes through
 * `AudioManager.dispatchMediaKeyEvent` with no permission at all; there is no
 * key for rate.
 *
 * The rider grants it in system settings, per
 * [com.eried.eucplanet.data.repository.AppHealthRepository.openNotificationAccessSettings],
 * and only when they switch the rate feature on.
 */
class MediaAccessService : NotificationListenerService()
