package com.eried.eucplanet.platform

import android.content.Context
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource

/**
 * Maps a shared string's key to the phone app's `R.string` id. The app
 * generates the table from the keys shared code names (app/string-ids.gradle.kts)
 * and installs it before anything is shown.
 */
object AndroidStrings {
    @Volatile
    private var lookup: (String) -> Int = { 0 }

    fun install(idOf: (String) -> Int) {
        lookup = idOf
    }

    fun id(res: StringResource): Int {
        val id = lookup(res.key)
        check(id != 0) { "No R.string.${res.key}: is AndroidStrings installed?" }
        return id
    }
}

@Composable
actual fun stringResource(res: StringResource): String =
    androidx.compose.ui.res.stringResource(AndroidStrings.id(res))

@Composable
actual fun stringResource(res: StringResource, vararg formatArgs: Any): String =
    androidx.compose.ui.res.stringResource(AndroidStrings.id(res), *formatArgs)

@Composable
actual fun stringResourceInLanguage(res: StringResource, languageTag: String): String {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(languageTag) {
        val tag = languageTag.replace("_", "-")
        val locale = java.util.Locale.forLanguageTag(tag)
        val cfg = android.content.res.Configuration(ctx.resources.configuration).apply {
            setLocale(locale)
        }
        ctx.createConfigurationContext(cfg).getString(AndroidStrings.id(res))
    }
}

@Composable
actual fun rememberStringReader(): (StringResource) -> String {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    return androidx.compose.runtime.remember(configuration) {
        { res -> resources.getString(AndroidStrings.id(res)) }
    }
}

/** `Context.getString` for a shared string. */
fun Context.getString(res: StringResource): String = getString(AndroidStrings.id(res))

fun Context.getString(res: StringResource, vararg formatArgs: Any): String =
    getString(AndroidStrings.id(res), *formatArgs)
