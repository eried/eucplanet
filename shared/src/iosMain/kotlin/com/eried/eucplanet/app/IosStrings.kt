package com.eried.eucplanet.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource

/**
 * A string for code outside composition, loaded off the main thread.
 *
 * The resources library runs the first load of a string on the dispatcher of
 * whoever asks first, and a composable asking for the same string blocks the
 * main thread until that load ends. A coroutine on the main dispatcher that
 * asked first (the ride Live Activity does, for its unit and button labels)
 * then waits for a main thread that is waiting for it, and the screen that
 * was opening stays blank for good: Settings on Voice or Wheel parameters,
 * about one launch in five with a wheel connected. Loading on Default keeps
 * the two apart. Every iPhone file reads strings through these.
 */
internal suspend fun getString(res: StringResource): String =
    withContext(Dispatchers.Default) { org.jetbrains.compose.resources.getString(res) }

internal suspend fun getString(res: StringResource, vararg args: Any): String =
    withContext(Dispatchers.Default) { org.jetbrains.compose.resources.getString(res, *args) }
