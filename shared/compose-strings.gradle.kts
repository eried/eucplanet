// Builds the iOS app's Compose string resources from the phone app's
// strings.xml files, so the 23 locales live in one place and every key and
// translation reaches iOS the moment it lands on Android.
//
// Android keeps reading its own res/values*/strings.xml through aapt2, as it
// always did. Compose resources read the text as written and only undo \n, \t
// and \uXXXX, so each string is first processed the way aapt2 processes it
// (quotes, whitespace, escapes, styling tags dropped) and then written in the
// form Compose reads back to the same text. tools/check-ios-strings.py
// compares every string in every locale with what aapt2 put in the APK.
//
// Writes into build/generated; nothing is committed.

abstract class ComposeStringsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stringFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for (file in stringFiles.files.sortedBy { it.path }) {
            val qualifier = ComposeStrings.qualifier(file.parentFile.name) ?: continue
            val dir = out.resolve(qualifier)
            dir.mkdirs()
            dir.resolve("strings.xml").writeText(ComposeStrings.convert(file))
        }
    }
}

object ComposeStrings {
    /**
     * Compose takes a language and a two-letter region only. Android's
     * `b+es+419` (Latin American Spanish) has no such form, so it is written
     * under the unassigned code XL; the iOS side picks XL for es-419 and for
     * every Latin American country (IosStrings.kt).
     */
    fun qualifier(androidDir: String): String? = when {
        androidDir == "values" -> "values"
        androidDir == "values-b+es+419" -> "values-es-rXL"
        Regex("""values-[a-z]{2,3}(-r[A-Z]{2})?""").matches(androidDir) -> androidDir
        else -> null
    }

    fun convert(file: java.io.File): String {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(file)
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
        val root = doc.documentElement
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? org.w3c.dom.Element ?: continue
            val name = el.getAttribute("name")
            when (el.tagName) {
                "string" -> sb.append("    <string name=\"").append(name).append("\">")
                    .append(encode(aapt(el))).append("</string>\n")
                "plurals" -> {
                    sb.append("    <plurals name=\"").append(name).append("\">\n")
                    children(el, "item").forEach { item ->
                        sb.append("        <item quantity=\"").append(item.getAttribute("quantity")).append("\">")
                            .append(encode(aapt(item))).append("</item>\n")
                    }
                    sb.append("    </plurals>\n")
                }
                "string-array" -> {
                    sb.append("    <string-array name=\"").append(name).append("\">\n")
                    children(el, "item").forEach { item ->
                        sb.append("        <item>").append(encode(aapt(item))).append("</item>\n")
                    }
                    sb.append("    </string-array>\n")
                }
            }
        }
        sb.append("</resources>\n")
        return sb.toString()
    }

    private fun children(el: org.w3c.dom.Element, tag: String): List<org.w3c.dom.Element> {
        val out = mutableListOf<org.w3c.dom.Element>()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val c = nodes.item(i) as? org.w3c.dom.Element ?: continue
            if (c.tagName == tag) out += c
        }
        return out
    }

    /**
     * The text `Resources.getString` returns, following aapt2's
     * StringBuilder::AppendText: unquoted whitespace runs become one space,
     * a double quote toggles quoting and is dropped, backslash escapes are
     * resolved, styling tags keep only their text, and the result is trimmed
     * of the unquoted space at either end.
     */
    fun aapt(el: org.w3c.dom.Element): String {
        val raw = el.textContent
        val out = StringBuilder()
        // Positions in [out] of spaces that came from collapsing unquoted
        // whitespace; only those are trimmed at the ends.
        val soft = HashSet<Int>()
        var quote = false
        var lastWasSpace = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i++]
            if (!quote && c.isAsciiSpace()) {
                if (!lastWasSpace) {
                    soft += out.length
                    out.append(' ')
                    lastWasSpace = true
                }
                continue
            }
            lastWasSpace = false
            when {
                c == '\\' && i < raw.length -> {
                    when (val e = raw[i++]) {
                        't' -> out.append('\t')
                        'n' -> out.append('\n')
                        'u' -> {
                            var code = 0
                            var digits = 0
                            while (digits < 4 && i < raw.length && raw[i].isHex()) {
                                code = code * 16 + Character.digit(raw[i], 16)
                                i++
                                digits++
                            }
                            out.append(code.toChar())
                        }
                        else -> out.append(e)
                    }
                }
                c == '\\' -> Unit
                c == '"' -> quote = !quote
                else -> out.append(c)
            }
        }
        var start = 0
        while (start in soft) start++
        var end = out.length
        while (end > start && (end - 1) in soft) end--
        return out.substring(start, end)
    }

    private fun Char.isAsciiSpace() = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000b' || this == '\u000c'
    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Text Compose's converter reads back as [s]: it undoes \\, \n, \t and \uXXXX only. */
    fun encode(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\t' -> sb.append("\\t")
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}

val composeStrings = tasks.register<ComposeStringsTask>("generateComposeStrings") {
    stringFiles.from(fileTree(rootProject.file("app/src/main/res")) { include("values*/strings.xml") })
    outputDir.set(layout.buildDirectory.dir("generated/composeStrings"))
}
