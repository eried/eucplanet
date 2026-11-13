package com.eried.eucplanet.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The iPhone's back: a swipe in from the left edge of the screen.
 *
 * Compose Multiplatform turns that swipe into a back event (its UIKit
 * navigation event input, always on for the start edge) and hands it to the
 * newest enabled handler, the way Android's system back reaches the newest
 * BackHandler. The dashboard registers none: a swipe there does nothing.
 *
 * Every iOS host and MainViewController's screen stack register through
 * here, so one place knows which API carries the gesture.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
@Composable
internal fun IosBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    androidx.compose.ui.backhandler.BackHandler(enabled = enabled, onBack = onBack)
}

/**
 * Lets the left-edge swipe close dialogs, sheets and popups too.
 *
 * Compose Multiplatform's dialogs listen for back with a handler that only
 * takes an instant back (a button press). A swipe is a progressing event, so
 * the dialog swallowed it and stayed open: on the iPhone, where the swipe is
 * the only back there is, a dialog could not be backed out of.
 *
 * This puts one handler in front of every other (overlay priority) that
 * takes the swipe and, once it completes, replays it as an instant back to
 * whatever is next in line: the open dialog's own dismiss, or the screen's
 * IosBackHandler. A swipe that is let go of early is cancelled and does
 * nothing, as on Android. Mounted once at the root of MainViewController.
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
@Composable
internal fun IosBackSwipeBridge() {
    val owner = androidx.compose.ui.backhandler.LocalCompatNavigationEventDispatcherOwner.current ?: return
    val dispatcher = owner.navigationEventDispatcher
    androidx.compose.runtime.DisposableEffect(dispatcher) {
        val input = androidx.navigationevent.DirectNavigationEventInput()
        dispatcher.addInput(input)
        val scope = kotlinx.coroutines.MainScope()
        val bridge = object : androidx.navigationevent.NavigationEventHandler<androidx.navigationevent.NavigationEventInfo>(
            initialInfo = androidx.navigationevent.NavigationEventInfo.None,
            isBackEnabled = true,
        ) {
            override fun onBackCompleted() {
                // After this dispatch ends: the replay is a new event, and this
                // handler steps aside for it so the next one in line gets it.
                scope.launch {
                    isBackEnabled = false
                    try {
                        input.backCompleted()
                    } finally {
                        isBackEnabled = true
                    }
                }
            }
        }
        dispatcher.addHandler(bridge, androidx.navigationevent.NavigationEventDispatcher.PRIORITY_OVERLAY)
        onDispose {
            bridge.remove()
            dispatcher.removeInput(input)
            scope.cancel()
        }
    }
}
