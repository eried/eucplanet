package com.eried.eucplanet.app

import com.eried.eucplanet.util.AppLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import platform.Foundation.NSArgumentDomain
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults

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

/**
 * Points the string lookup at the language iOS applied to the app.
 *
 * The lookup reads the first of the iPhone's preferred languages and matches
 * its language and region to a folder by exact name, so "es-419", "es-MX",
 * "en-AU" or "zh-Hant-HK" found nothing of their own and showed Spain's
 * Spanish, American English or simplified Chinese. iOS has already chosen
 * the right one of the app's languages for the rider's whole list; this
 * hands the lookup that choice, for this run only (the argument domain is
 * never saved), before the first text loads.
 */
internal object IosAppLanguage {
    private const val KEY = "AppleLanguages"

    fun install() {
        val applied = NSBundle.mainBundle.preferredLocalizations.firstOrNull() as? String ?: return
        val defaults = NSUserDefaults.standardUserDefaults
        val args = defaults.volatileDomainForName(NSArgumentDomain).toMutableMap()
        args[KEY] = listOf(AppLanguages.iosStringsLocale(applied))
        defaults.setVolatileDomain(args, NSArgumentDomain)
    }
}
