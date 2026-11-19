package com.eried.eucplanet.platform

import com.eried.eucplanet.util.AppLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The iPhone shows every language Android does. A language reaches it in
 * three steps, each a name of its own: iOS applies one of Info.plist's
 * CFBundleLocalizations, AppLanguages turns that into the locale the string
 * lookup is asked for, and the lookup needs a folder under exactly that name
 * (shared/compose-strings.gradle.kts). A language added to the app and
 * missed in one of them shows English on the iPhone without a word.
 */
class IosLanguagesTest {

    private fun repoFile(relative: String): File {
        var dir: File? = File(java.lang.System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("Could not locate '$relative' from ${java.lang.System.getProperty("user.dir")}")
    }

    private val localizations: List<String> =
        Regex("<key>CFBundleLocalizations</key>\\s*<array>(.*?)</array>", RegexOption.DOT_MATCHES_ALL)
            .find(repoFile("iosApp/Info.plist").readText())!!.groupValues[1]
            .let { Regex("<string>([^<]*)</string>").findAll(it).map { m -> m.groupValues[1] }.toList() }

    /** The folders the iPhone's lookup can find, as compose-strings.gradle.kts names them. */
    private val folders: Set<String> =
        repoFile("app/src/main/res").listFiles()!!
            .filter { it.isDirectory && File(it, "strings.xml").exists() }
            .flatMap {
                when (val name = it.name) {
                    "values-b+es+419" -> listOf("values-es-rXL")
                    "values-no" -> listOf("values-no", "values-nb")
                    "values-in" -> listOf("values-in", "values-id")
                    else -> listOf(name)
                }
            }.toSet()

    /** "es-XL" as the lookup's folder name, "values-es-rXL". */
    private fun folderOf(locale: String): String {
        val language = locale.substringBefore('-')
        val region = locale.substringAfter('-', "")
        return when {
            locale == "en" -> "values"
            region.isEmpty() -> "values-$language"
            else -> "values-$language-r$region"
        }
    }

    @Test
    fun everyAppLanguageIsAnIphoneLanguage() {
        val reached = localizations.map { AppLanguages.normalizeToSupportedTag(it) }
        assertEquals(AppLanguages.SUPPORTED.map { it.tag }.sorted(), reached.sorted())
    }

    @Test
    fun everyIphoneLanguageFindsItsStrings() {
        for (localization in localizations) {
            val folder = folderOf(AppLanguages.iosStringsLocale(localization))
            assertTrue("$localization looks in $folder, which is not written", folder in folders)
        }
    }

    @Test
    fun everyIphoneLanguageIsInTheXcodeProject() {
        val project = repoFile("iosApp/iosApp.xcodeproj/project.pbxproj").readText()
        val known = Regex("knownRegions = \\((.*?)\\);", RegexOption.DOT_MATCHES_ALL)
            .find(project)!!.groupValues[1]
            .split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() && it != "Base" }
        assertEquals(localizations.sorted(), known.sorted())
    }
}
