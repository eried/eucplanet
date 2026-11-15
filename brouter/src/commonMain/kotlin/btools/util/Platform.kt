package btools.util

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * The few platform calls BRouter makes, so the router itself is common code.
 * Not upstream BRouter: added by EUC Planet when the Java became Kotlin
 * Multiplatform (see README.md). The JVM side is java.io, the iOS side POSIX.
 */

/** java.io.IOException on the JVM, a plain exception on iOS. */
expect open class IOException(message: String?) : Exception

/** A file read at any offset: a routing segment. */
interface RandomReadFile {
    fun length(): Long

    /** Fills [len] bytes of [buf] from [off] with the file's bytes at [pos];
     *  throws [IOException] when the file ends first. */
    fun readFully(pos: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size)

    fun close()
}

/** The file calls BRouter makes, on a path. */
expect object BFiles {
    /** True for an existing regular file. */
    fun isFile(path: String): Boolean

    /** The size in bytes, 0 when missing. */
    fun length(path: String): Long

    /** Milliseconds since 1970, 0 when missing. */
    fun lastModified(path: String): Long

    /** The whole file; throws [IOException] when it cannot be read. */
    fun readBytes(path: String): ByteArray

    /** The file opened for reading; throws [IOException] when it cannot be. */
    fun openRandom(path: String): RandomReadFile
}

/** Milliseconds since 1970, System.currentTimeMillis on the JVM. */
expect fun currentTimeMillis(): Long

/** [child] inside the folder [dir], as java.io.File(dir, child) names it. */
fun joinPath(dir: String, child: String): String =
    if (dir.isEmpty()) child else if (dir.endsWith("/")) dir + child else "$dir/$child"

/** The folder holding [path], or null at the top, as File.getParent. */
fun parentPath(path: String): String? {
    val p = path.trimEnd('/')
    val i = p.lastIndexOf('/')
    return when {
        i < 0 -> null
        i == 0 -> "/"
        else -> p.substring(0, i)
    }
}

/** The last name in [path], as File.getName. */
fun fileName(path: String): String = path.substring(path.lastIndexOf('/') + 1)

/** java.lang.Math.round(double): ties go up, NaN is 0. */
fun jround(d: Double): Long = if (d.isNaN()) 0L else d.roundToLong()

/** java.lang.Math.round(float): ties go up, NaN is 0. */
fun jround(f: Float): Int = if (f.isNaN()) 0 else f.roundToInt()

/** java.lang.Math.toDegrees. */
fun toDegrees(rad: Double): Double = rad * 57.29577951308232

/** Float.parseFloat: surrounding whitespace allowed, and Java's message on
 *  failure, so a profile error reads the same on every platform. */
fun jParseFloat(s: String): Float =
    s.trim().toFloatOrNull() ?: throw NumberFormatException(javaNumberMessage(s))

/** Double.parseDouble, like [jParseFloat]. */
fun jParseDouble(s: String): Double =
    s.trim().toDoubleOrNull() ?: throw NumberFormatException(javaNumberMessage(s))

private fun javaNumberMessage(s: String): String =
    if (s.trim().isEmpty()) "empty String" else "For input string: \"$s\""

/** Character.isWhitespace for the characters a profile holds. */
fun jIsWhitespace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' ||
        c == '\u001C' || c == '\u001D' || c == '\u001E' || c == '\u001F' ||
        (c != ' ' && c != ' ' && c != ' ' && c.isWhitespace())

/** java.util.StringTokenizer(s, delims): the non-empty runs between delimiters. */
fun tokenize(s: String, delims: String): MutableList<String> {
    val out = ArrayList<String>()
    var start = -1
    for (i in s.indices) {
        if (delims.indexOf(s[i]) >= 0) {
            if (start >= 0) {
                out.add(s.substring(start, i))
                start = -1
            }
        } else if (start < 0) {
            start = i
        }
    }
    if (start >= 0) out.add(s.substring(start))
    return out
}
