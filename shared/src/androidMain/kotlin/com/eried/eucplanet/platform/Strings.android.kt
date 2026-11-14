package com.eried.eucplanet.platform

import android.content.Context
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource

/**
 * Maps a shared string's key to the phone app's `R.string` id. The app
 * generates the table from the keys shared code names (app/string-ids.gradle.kts)
 * and installs it before anything is shown.
 */
object AndroidStrings {
    @Volatile
    private var lookup: (String) -> Int = { 0 }
    @Volatile
    private var pluralLookup: (String) -> Int = { 0 }
    @Volatile
    private var arrayLookup: (String) -> Int = { 0 }

    /** [pluralIdOf] and [arrayIdOf] give the R.plurals and R.array ids the same way. */
    fun install(
        idOf: (String) -> Int,
        pluralIdOf: (String) -> Int = { 0 },
        arrayIdOf: (String) -> Int = { 0 },
    ) {
        lookup = idOf
        pluralLookup = pluralIdOf
        arrayLookup = arrayIdOf
    }

    fun id(res: StringResource): Int {
        val id = lookup(res.key)
        check(id != 0) { "No R.string.${res.key}: is AndroidStrings installed?" }
        return id
    }

    fun id(res: PluralStringResource): Int {
        val id = pluralLookup(res.key)
        check(id != 0) { "No R.plurals.${res.key}: is AndroidStrings installed?" }
        return id
    }

    fun id(res: StringArrayResource): Int {
        val id = arrayLookup(res.key)
        check(id != 0) { "No R.array.${res.key}: is AndroidStrings installed?" }
        return id
    }
}

@Composable
actual fun pluralStringResource(res: PluralStringResource, quantity: Int, vararg formatArgs: Any): String =
    androidx.compose.ui.res.pluralStringResource(AndroidStrings.id(res), quantity, *formatArgs)

@Composable
actual fun stringArrayResource(res: StringArrayResource): List<String> =
    androidx.compose.ui.res.stringArrayResource(AndroidStrings.id(res)).toList()

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

@Composable
actual fun rememberStringFormatter(): (StringResource, List<Any>) -> String {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    return androidx.compose.runtime.remember(configuration) {
        { res, args -> resources.getString(AndroidStrings.id(res), *args.toTypedArray()) }
    }
}

/** `Context.getString` for a shared string. */
fun Context.getString(res: StringResource): String = getString(AndroidStrings.id(res))

fun Context.getString(res: StringResource, vararg formatArgs: Any): String =
    getString(AndroidStrings.id(res), *formatArgs)
