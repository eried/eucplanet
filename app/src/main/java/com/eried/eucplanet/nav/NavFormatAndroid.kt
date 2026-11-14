package com.eried.eucplanet.nav

import android.content.Context
import com.eried.eucplanet.platform.getString
import org.jetbrains.compose.resources.StringResource

/** [NavFormat]'s string lookup through a Context, for the Android-only callers. */
fun Context.navText(): (StringResource, List<Any>) -> String = { res, args ->
    if (args.isEmpty()) getString(res) else getString(res, *args.toTypedArray())
}

fun NavFormat.distance(context: Context, meters: Double, imperial: Boolean): String =
    distance(context.navText(), meters, imperial)

fun NavFormat.spokenDistance(context: Context, meters: Double, imperial: Boolean): String =
    spokenDistance(context.navText(), meters, imperial)
