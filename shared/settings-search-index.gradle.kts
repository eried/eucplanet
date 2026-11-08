// Builds the settings search index from the settings screen's own code.
//
// The search used to read a hand-written list of labels per section, and
// every button, and every row added after the list was written, was missing
// from it: "Crew" or "Scan" found nothing. This reads the code instead. From
// each section's content it follows the composables it calls and collects
// every text they show, with what kind of text it is and the sub-heading it
// sits under, so a new row or button is searchable the day it is added.
//
// A text inside a block only some phones draw (`if (SettingsRow.X !in
// hiddenRows)`, `if (host.supportsVolumeKeys)`) carries that gate, so a phone
// that leaves the row out does not find it either.
//
// Writes SettingsSearchIndex.kt into :shared's build/generated, naming the
// texts as Res.string so both phones read the same index; nothing is committed.
// SettingsSearchIndexTest pins what it must find.

abstract class SettingsSearchIndexTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val files = sources.files.filter { it.extension == "kt" }.map { it.name to it.readText() }
        val index = SettingsSearchScan.scan(files)
        val out = outputDir.get().asFile.resolve("com/eried/eucplanet/ui/settings")
        out.mkdirs()
        out.resolve("SettingsSearchIndex.kt").writeText(SettingsSearchScan.render(index))
    }
}

object SettingsSearchScan {
    /**
     * [placed]: seen where it shows (inside a button), not only named like one.
     * [folds]: the titles of the collapsible cards it sits in, outermost first,
     * so a card can say how many matches it hides while it is shut.
     */
    data class Hit(
        val id: String,
        val kind: String,
        val under: String?,
        val placed: Boolean = false,
        val folds: List<String> = emptyList(),
        /**
         * The [SettingsRow] gates it sits inside, outermost first. Each gate
         * is the rows any one of which, when shown, draws the block.
         */
        val gates: List<List<String>> = emptyList(),
    )

    /** One `if` the rows inside depend on: the block and the rows that draw it. */
    private class Gate(val span: IntRange, val rows: List<String>)

    private val ifCall = Regex("""\bif\s*\(""")
    private val rowTerm = Regex("""^SettingsRow\.(\w+)\s*!in\s*[\w.]*hiddenRows$""")
    // Host switches that stand for a row: the volume keys and the backup folder.
    private val hostFlags = mapOf("supportsVolumeKeys" to "VOLUME_KEYS", "supportsBackupFolder" to "BACKUP_FOLDER")
    private val flagTerm = Regex("""^(!?)\s*host\.(\w+)$""")

    /** Calls whose content is not on the page until the rider opens it. */
    private val hiddenCall = Regex(
        """\b(AlertDialog|BasicAlertDialog|Dialog|DatePickerDialog|ModalBottomSheet|DropdownMenu|ExposedDropdownMenu|Popup)\s*\("""
    )
    private val buttonCall = Regex("""\b(\w*Button)\s*\(""")
    // Any card that folds its content away, by name, so one added later is
    // covered the day it is written. Its title is the first text in its
    // arguments; what it hides is its trailing lambda.
    private val foldCall = Regex("""\b(\w*Collaps\w*)\s*\(""")
    private val anyText = Regex("""\b(?:Res|R)\.string\.(\w+)""")
    private val header = Regex("""SectionHeader\(\s*stringResource\(\s*(?:Res|R)\.string\.(\w+)""")
    // A label picked in a `when`, like a voice report's name, is shown too.
    private val text = Regex("""(?:stringResource\(|->)\s*(?:Res|R)\.string\.(\w+)""")
    private val call = Regex("""\b([A-Za-z]\w*)\s*\(""")
    private val funDecl = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")
    private val section = Regex("""SectionDef\(\s*"(\w+)"""")

    /** Shared dialog words, not something a rider searches a section for. */
    private val generic = Regex("""action_(cancel|ok|save|apply|close|done|delete|back|yes|no|restore|reset|overwrite)""")

    /** The bracket closing the one at [open], skipping strings, chars and comments. */
    fun close(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when {
                s.startsWith("//", i) -> { i = s.indexOf('\n', i).let { if (it < 0) s.length else it }; continue }
                s.startsWith("/*", i) -> { i = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }; continue }
                s.startsWith("\"\"\"", i) -> { i = skipString(s, i + 3, raw = true); continue }
                s[i] == '"' -> { i = skipString(s, i + 1, raw = false); continue }
                s[i] == '\'' -> { i = s.indexOf('\'', i + if (s[i + 1] == '\\') 3 else 2) + 1; continue }
                s[i] == '(' || s[i] == '{' || s[i] == '[' -> depth++
                s[i] == ')' || s[i] == '}' || s[i] == ']' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return s.length - 1
    }

    private fun skipString(s: String, from: Int, raw: Boolean): Int {
        var i = from
        while (i < s.length) {
            when {
                raw && s.startsWith("\"\"\"", i) -> return i + 3
                !raw && s[i] == '\\' -> i++
                !raw && s[i] == '"' -> return i + 1
                s.startsWith("\${", i) -> i = close(s, i + 1)
            }
            i++
        }
        return s.length
    }

    /** A call's whole extent: its arguments and a trailing lambda, if any. */
    private fun callSpan(s: String, paren: Int): IntRange {
        var end = close(s, paren)
        var j = end + 1
        while (j < s.length && s[j].isWhitespace()) j++
        if (j < s.length && s[j] == '{') end = close(s, j)
        return paren..end
    }

    private fun bodies(files: List<Pair<String, String>>): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        for ((_, src) in files) for (m in funDecl.findAll(src)) {
            // Only composables draw anything.
            val before = src.substring(maxOf(0, m.range.first - 200), m.range.first)
            if ("@Composable" !in before.substringAfterLast('}')) continue
            val paren = m.range.last
            var j = close(src, paren) + 1
            while (j < src.length && src[j] != '{' && src[j] != '=' && src[j] != '\n') j++
            // Only block bodies; a one-expression composable shows nothing of its own.
            if (j < src.length && src[j] == '{') {
                out.getOrPut(m.groupValues[1]) { mutableListOf() } += src.substring(j, close(src, j) + 1)
            }
        }
        return out
    }

    /** Where [i] lands after any whitespace. */
    private fun skipSpace(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    /**
     * The extent of the statement or block starting at [at]: a `{...}` block,
     * or one call with its trailing lambda. Null for anything else.
     */
    private fun statementAt(s: String, at: Int): IntRange? {
        if (at >= s.length) return null
        if (s[at] == '{') return at..close(s, at)
        val m = Regex("""^[\w.]+\s*\(""").find(s.substring(at, minOf(s.length, at + 200))) ?: return null
        return at..callSpan(s, at + m.range.last).last
    }

    /**
     * The row gates in [body]. A condition is read only when every term is a
     * row or host switch this file knows, so anything else gates nothing. A
     * negated host switch (`if (!host.supportsBackupFolder) {} else ...`)
     * gates its else chain instead.
     */
    private fun gates(body: String): List<Gate> = ifCall.findAll(body).mapNotNull { m ->
        val paren = m.range.last
        val condEnd = close(body, paren)
        val terms = body.substring(paren + 1, condEnd).split("||").map { it.trim().replace(Regex("""\s+"""), " ") }
        val rows = terms.map { t -> rowTerm.find(t)?.groupValues?.get(1) }
        if (rows.all { it != null }) {
            val span = statementAt(body, skipSpace(body, condEnd + 1)) ?: return@mapNotNull null
            return@mapNotNull Gate(span, rows.filterNotNull())
        }
        val flag = terms.singleOrNull()?.let { flagTerm.find(it) } ?: return@mapNotNull null
        val row = hostFlags[flag.groupValues[2]] ?: return@mapNotNull null
        val then = statementAt(body, skipSpace(body, condEnd + 1)) ?: return@mapNotNull null
        if (flag.groupValues[1].isEmpty()) return@mapNotNull Gate(then, listOf(row))
        // Negated: the else chain is what needs the switch.
        var end = then.last
        var j = skipSpace(body, end + 1)
        if (!body.startsWith("else", j)) return@mapNotNull null
        val elseAt = j
        while (body.startsWith("else", j)) {
            j = skipSpace(body, j + 4)
            if (body.startsWith("if", j)) {
                val p = body.indexOf('(', j)
                j = skipSpace(body, close(body, p) + 1)
            }
            end = (statementAt(body, j) ?: break).last
            j = skipSpace(body, end + 1)
        }
        Gate(elseAt..end, listOf(row))
    }.toList()

    private fun kindOf(id: String, inButton: Boolean): String = when {
        inButton -> "BUTTON"
        Regex("""_(desc|caption|hint|help|body|explain|detail|subtitle|note|info|summary)(_\w+)?$""").containsMatchIn(id) -> "DESCRIPTION"
        id.startsWith("section_") || id.startsWith("tab_") || id.endsWith("_title") || id.endsWith("_section") -> "TITLE"
        id.endsWith("_action") || id.endsWith("_button") -> "BUTTON"
        else -> "LABEL"
    }

    private fun strength(kind: String) = listOf("DESCRIPTION", "LABEL", "BUTTON", "TITLE").indexOf(kind)

    fun scan(files: List<Pair<String, String>>): Map<String, List<Hit>> {
        val fns = bodies(files)
        val screen = files.first { it.first == "SettingsScreen.kt" }.second
        val result = LinkedHashMap<String, List<Hit>>()
        for (m in section.findAll(screen)) {
            val paren = m.range.first + "SectionDef".length
            var j = close(screen, paren) + 1
            while (screen[j].isWhitespace()) j++
            if (screen[j] != '{') continue
            val hits = LinkedHashMap<String, Hit>()
            walk(screen.substring(j, close(screen, j) + 1), null, fns, HashSet(), hits)
            result[m.groupValues[1]] = hits.values.toList()
        }
        // A word every section shows is no help in choosing one.
        val seen = result.values.flatMap { list -> list.map { it.id }.distinct() }.groupingBy { it }.eachCount()
        return result.mapValues { (_, list) -> list.filter { (seen[it.id] ?: 0) < 4 && !generic.matches(it.id) } }
    }

    /**
     * The cards in [body]: what each one hides, and its title. Everything
     * after the title is hidden, the other arguments (an info line) included.
     */
    private fun folds(body: String): List<Pair<IntRange, String>> = foldCall.findAll(body).mapNotNull { m ->
        val paren = m.range.last
        val argsEnd = close(body, paren)
        val title = anyText.find(body, paren)?.takeIf { it.range.first < argsEnd } ?: return@mapNotNull null
        var j = argsEnd + 1
        while (j < body.length && body[j].isWhitespace()) j++
        if (j >= body.length || body[j] != '{') return@mapNotNull null
        (title.range.last + 1..close(body, j)) to title.groupValues[1]
    }.toList()

    private fun walk(
        body: String,
        startUnder: String?,
        fns: Map<String, List<String>>,
        visited: MutableSet<String>,
        hits: MutableMap<String, Hit>,
        startFolds: List<String> = emptyList(),
        startGates: List<List<String>> = emptyList(),
    ) {
        val cards = folds(body)
        fun foldsAt(at: Int) =
            startFolds + cards.filter { at in it.first }.sortedBy { it.first.first }.map { it.second }
        val gated = gates(body)
        fun gatesAt(at: Int) =
            startGates + gated.filter { at in it.span }.sortedBy { it.span.first }.map { it.rows }
        val hidden = hiddenCall.findAll(body).map { callSpan(body, it.range.last) }.toList()
        val buttons = buttonCall.findAll(body)
            .filter { b -> listOf("Radio", "Segmented", "Icon").none { it in b.groupValues[1] } }
            .map { callSpan(body, it.range.last) }.toList()
        fun inside(spans: List<IntRange>, at: Int) = spans.any { at in it }

        data class Event(val at: Int, val kind: Int, val value: String)
        val events = (header.findAll(body).map { Event(it.range.first, 0, it.groupValues[1]) } +
            text.findAll(body).map { Event(it.range.first, 1, it.groupValues[1]) } +
            call.findAll(body).filter { it.groupValues[1] in fns }.map { Event(it.range.first, 2, it.groupValues[1]) })
            .filter { !inside(hidden, it.at) }
            .sortedWith(compareBy({ it.at }, { it.kind }))

        var under = startUnder
        val headerAt = HashSet<Int>()
        for (e in events) when (e.kind) {
            0 -> {
                under = e.value
                hits.putIfAbsent(e.value, Hit(e.value, "TITLE", null, folds = foldsAt(e.at), gates = gatesAt(e.at)))
                headerAt += body.indexOf("stringResource", e.at)
            }
            1 -> if (e.at !in headerAt) {
                // A text read in more than one place counts where it shows the
                // most: the button it labels, not a line that only reads it.
                val inButton = inside(buttons, e.at)
                val hit = Hit(
                    e.value, kindOf(e.value, inButton), under, placed = inButton,
                    folds = foldsAt(e.at), gates = gatesAt(e.at),
                )
                val had = hits[e.value]
                val better = had == null || strength(hit.kind) > strength(had.kind) ||
                    (strength(hit.kind) == strength(had.kind) && hit.placed && !had.placed)
                // Shown anywhere without a gate is shown: keep only the gates both share.
                val shared = if (had == null) hit.gates else hit.gates.filter { it in had.gates }
                if (better) hits[e.value] = hit.copy(gates = shared)
                else if (had != null && shared != had.gates) hits[e.value] = had.copy(gates = shared)
            }
            else -> if (visited.add(e.value)) {
                for (b in fns.getValue(e.value)) walk(b, under, fns, visited, hits, foldsAt(e.at), gatesAt(e.at))
            }
        }
    }

    fun render(index: Map<String, List<Hit>>): String = buildString {
        appendLine("// Generated by shared/settings-search-index.gradle.kts. Do not edit.")
        appendLine("package com.eried.eucplanet.ui.settings")
        appendLine()
        appendLine("import com.eried.eucplanet.resources.*")
        appendLine("import org.jetbrains.compose.resources.StringResource")
        appendLine()
        appendLine("object SettingsSearchIndex {")
        appendLine("    enum class Kind { TITLE, BUTTON, LABEL, DESCRIPTION }")
        appendLine()
        appendLine("    /**")
        appendLine("     * A text one section shows: what it is, the sub-heading (null for none) it")
        appendLine("     * sits under, the titles of the cards it is folded into, outermost first, and")
        appendLine("     * the row gates it sits inside: it shows only when each gate has a row shown.")
        appendLine("     */")
        appendLine("    class Entry(")
        appendLine("        val res: StringResource,")
        appendLine("        val kind: Kind,")
        appendLine("        val under: StringResource?,")
        appendLine("        val folds: List<StringResource> = emptyList(),")
        appendLine("        val gates: List<Set<SettingsRow>> = emptyList(),")
        appendLine("    )")
        appendLine()
        appendLine("    /** Every section's texts, by section key, in the order they appear. */")
        appendLine("    val sections: Map<String, List<Entry>> by lazy {")
        appendLine("        mapOf(")
        for (key in index.keys) appendLine("            \"$key\" to ${fnName(key)}(),")
        appendLine("        )")
        appendLine("    }")
        // One function per section keeps each well under the JVM method size limit.
        for ((key, hits) in index) {
            appendLine()
            appendLine("    private fun ${fnName(key)}() = listOf<Entry>(")
            for (h in hits) {
                val under = h.under?.let { "Res.string.$it" } ?: "null"
                val folds = if (h.folds.isEmpty() && h.gates.isEmpty()) "" else
                    ", listOf(" + h.folds.joinToString { "Res.string.$it" } + ")"
                val gates = if (h.gates.isEmpty()) "" else
                    ", listOf(" + h.gates.joinToString { g -> "setOf(" + g.joinToString { "SettingsRow.$it" } + ")" } + ")"
                appendLine("        Entry(Res.string.${h.id}, Kind.${h.kind}, $under$folds$gates),")
            }
            appendLine("    )")
        }
        appendLine("}")
    }

    private fun fnName(key: String) = "section" + key.replaceFirstChar { it.uppercase() }
}

// Wired into commonMain by shared/build.gradle.kts.
tasks.register<SettingsSearchIndexTask>("generateSettingsSearchIndex") {
    sources.from(fileTree("src/commonMain/kotlin/com/eried/eucplanet/ui") { include("**/*.kt") })
    // Settings content still in :app (R.string there, Res.string here).
    sources.from(fileTree(rootProject.file("app/src/main/java/com/eried/eucplanet/ui")) { include("**/*.kt") })
    outputDir.set(layout.buildDirectory.dir("generated/settingsSearch"))
}
