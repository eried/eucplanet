package com.eried.eucplanet.platform

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Keeps Android-only and JVM-only APIs out of commonMain.
 *
 * On a Mac the iOS compile catches all of these. Everywhere else (Windows, the
 * Linux CI) commonMain is only ever compiled for Android, where `java.*` and
 * `android.*` resolve fine, so a slip would land unnoticed and break the next
 * iOS build. This test runs in `./gradlew test` on every host.
 *
 * The fix for each hit is in docs/IOS_SYNC.md: use the facade in
 * `com.eried.eucplanet.platform`, or put the code behind an interface or
 * expect/actual with the Android code in androidMain.
 */
class CommonMainGuardTest {

    private val commonMain = File("src/commonMain/kotlin")

    /** Imports that only exist on Android or the JVM. */
    private val forbiddenImports = listOf(
        "android." to "Android framework API",
        "java." to "JVM API, use a com.eried.eucplanet.platform facade",
        "javax." to "JVM API (javax.inject: register the class in a Koin module instead)",
        "dagger." to "Hilt/Dagger is Android-only, use Koin",
        "okhttp3." to "OkHttp is JVM-only, use the Ktor client",
        "org.json." to "org.json is Android-only, use kotlinx.serialization",
        "com.google.android." to "Google Play services are Android-only",
        "androidx.activity." to "Android-only",
        "androidx.core." to "Android-only",
        "androidx.hilt." to "Android-only",
        "androidx.work." to "Android-only",
        "androidx.compose.ui.platform.LocalContext" to "Android-only, inject what the Context was for",
        "androidx.compose.ui.viewinterop.AndroidView" to "Android-only, put the view behind an expect composable",
        "androidx.compose.ui.res." to "Android resources, use Compose resources (Res.*)",
        "com.eried.eucplanet.R" to "Android resources, use Compose resources (Res.*)",
        "kotlin.jvm.Volatile" to "use kotlin.concurrent.Volatile",
    )

    /** Calls that compile on the JVM without an import but not in common code. */
    private val needsImport = listOf(
        Regex("""(?<![\w.])System\.""") to "com.eried.eucplanet.platform.System",
        Regex("""(?<![\w.])Charsets\.""") to "com.eried.eucplanet.platform.Charsets",
        Regex("""(?<![\w.])Log\.[dviwe]\(""") to "com.eried.eucplanet.platform.Log",
        Regex("""@Volatile\b""") to "kotlin.concurrent.Volatile",
        Regex("""(?<![\w.])synchronized\(""") to "kotlinx.atomicfu.locks.synchronized",
        // String literals are blanked to "" before matching, so this is "%x".format(...).
        Regex("\"\"\\.format\\(|(?<![\\w.])String\\.format\\(") to "com.eried.eucplanet.platform.format",
    )

    /** Fully qualified uses that need no import at all. */
    private val forbiddenInline = listOf(
        Regex("""(?<![\w.])(java|javax|android)\.[a-z]+\.""") to "fully qualified JVM/Android API",
        Regex("""(?<![\w.])R\.(string|plurals|drawable|raw|array|color|xml|font)\.""") to "Android resources, use Res.*",
        Regex("""(?<![\w.])Thread(\.|\()""") to "JVM threads, use coroutines",
        Regex("""@Synchronized\b""") to "JVM-only, use kotlinx.atomicfu.locks",
    )

    @Test
    fun commonMainUsesOnlyMultiplatformApis() {
        val problems = mutableListOf<String>()
        commonMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val rel = file.relativeTo(commonMain).path
            val text = file.readText()
            val imports = Regex("""^import\s+([\w.]+)""", RegexOption.MULTILINE)
                .findAll(text).map { it.groupValues[1] }.toList()
            for (imp in imports) {
                forbiddenImports.firstOrNull { (prefix, _) -> imp == prefix.trimEnd('.') || imp.startsWith(prefix) }
                    ?.let { (_, why) -> problems += "$rel: import $imp ($why)" }
            }
            val code = stripCommentsAndStrings(text)
            code.lines().forEachIndexed { i, line ->
                if (line.trimStart().startsWith("import ") || line.trimStart().startsWith("package ")) return@forEachIndexed
                for ((re, why) in forbiddenInline) {
                    if (re.containsMatchIn(line)) problems += "$rel:${i + 1}: $why: ${line.trim()}"
                }
                for ((re, imp) in needsImport) {
                    if (re.containsMatchIn(line) && imp !in imports && !definesItself(rel, imp)) {
                        problems += "$rel:${i + 1}: needs import $imp: ${line.trim()}"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail("Android/JVM-only API in commonMain (see docs/IOS_SYNC.md):\n" + problems.joinToString("\n"))
        }
    }

    /** The facades live in the platform package, so their own files need no import. */
    private fun definesItself(rel: String, imp: String): Boolean =
        imp.startsWith("com.eried.eucplanet.platform.") &&
            rel.replace(File.separatorChar, '/').startsWith("com/eried/eucplanet/platform/")

    /** Blanks out comments and string/char literals, keeping line numbers. */
    private fun stripCommentsAndStrings(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                src.startsWith("//", i) -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                src.startsWith("/*", i) -> {
                    val end = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                    src.substring(i, end).forEach { if (it == '\n') out.append('\n') }
                    i = end
                }
                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }
                    out.append("\"\"")
                    src.substring(i, end).forEach { if (it == '\n') out.append('\n') }
                    i = end
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < src.length && src[j] != c && src[j] != '\n') {
                        if (src[j] == '\\') j++
                        j++
                    }
                    out.append(c).append(c)
                    i = j + 1
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
