package com.eried.eucplanet.app

import com.eried.eucplanet.platform.format
import com.eried.eucplanet.util.AppLanguages
import com.eried.eucplanet.voice.LanguageStrings
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.compose.resources.StringResource
import platform.Foundation.NSBundle
import platform.Foundation.NSLock
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The app's strings in any of its languages, read straight from the string
 * files in the app bundle.
 *
 * The resources library only answers in the phone's language, but the voice
 * speaks, and the spoken commands listen, in the language picked under Voice,
 * which can be another, as on Android. The files are the library's own
 * (`type|key|base64` lines, one per string), so this reads exactly the text
 * it would show. Read synchronously and kept: no coroutine to wait on, so no
 * way to hold up a screen that asks for the same string at the same moment.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalEncodingApi::class)
internal object IosLanguageStrings {
    private const val DIR = "compose-resources/composeResources/com.eried.eucplanet.resources"
    private val lock = NSLock()
    private val files = HashMap<String, Map<String, String>>()

    /** Strings in [languageTag] ("es_MX", "nb-NO"); blank means the app's applied language. */
    fun forTag(languageTag: String): LanguageStrings {
        val folder = folderFor(languageTag)
        return object : LanguageStrings {
            override fun text(res: StringResource, vararg args: Any): String {
                val raw = lookup(folder, res.key)
                return if (args.isEmpty()) raw else raw.format(*args)
            }
        }
    }

    /**
     * The folder holding [languageTag]'s strings, as shared/compose-strings.gradle.kts
     * names them. A language the app does not have reads English, as on Android.
     */
    fun folderFor(languageTag: String): String {
        val tag = languageTag.replace('_', '-').ifBlank {
            AppLanguages.iosStringsLocale(
                NSBundle.mainBundle.preferredLocalizations.firstOrNull() as? String ?: "en"
            ).replace("XL", "419")
        }
        val supported = AppLanguages.normalizeToSupportedTag(tag)
            .takeIf { t -> AppLanguages.SUPPORTED.any { it.tag == t } } ?: "en"
        val language = supported.substringBefore('-')
        val region = supported.substringAfter('-', "")
        return when {
            supported == "en" -> "values"
            region == "419" -> "values-$language-rXL"
            region.isEmpty() -> "values-$language"
            else -> "values-$language-r$region"
        }
    }

    private fun lookup(folder: String, key: String): String =
        load(folder)[key] ?: load("values")[key] ?: key

    private fun load(folder: String): Map<String, String> {
        lock.lock()
        try {
            return files.getOrPut(folder) { parse(read(folder)) }
        } finally {
            lock.unlock()
        }
    }

    private fun read(folder: String): String {
        val path = "${NSBundle.mainBundle.resourcePath}/$DIR/$folder/strings.commonMain.cvr"
        return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: ""
    }

    /** `string|key|base64` lines; plurals and arrays are left to the library. */
    fun parse(cvr: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in cvr.lineSequence()) {
            if (!line.startsWith("string|")) continue
            val second = line.indexOf('|', 7)
            if (second < 0) continue
            val key = line.substring(7, second)
            val text = runCatching { Base64.decode(line.substring(second + 1)).decodeToString() }.getOrNull()
                ?: continue
            out[key] = text
        }
        return out
    }
}
