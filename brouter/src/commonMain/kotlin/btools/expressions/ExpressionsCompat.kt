package btools.expressions

/*
 * java.lang.String helpers the expression parser relies on.
 * Not upstream BRouter: added by EUC Planet when the Java became Kotlin
 * Multiplatform, so the profile and lookups parsing stays identical.
 */

/** String.trim(): strips the chars at or below ' ' at both ends. */
internal fun jTrim(s: String): String = s.trim { it <= ' ' }

/** String.split(regex) for a single literal char: trailing empty strings are removed. */
internal fun jSplit(s: String, delim: Char): Array<String> {
  if (s.indexOf(delim) < 0) return arrayOf(s)
  val parts = s.split(delim)
  var n = parts.size
  while (n > 0 && parts[n - 1].isEmpty()) n--
  return Array(n) { parts[it] }
}

/** The lines BufferedReader.readLine returns, in order: ends are \n, \r or \r\n. */
internal fun jReadLines(text: String): MutableList<String> {
  val out = ArrayList<String>()
  val n = text.length
  var start = 0
  var i = 0
  while (i < n) {
    val c = text[i]
    if (c == '\n' || c == '\r') {
      out.add(text.substring(start, i))
      if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
      i++
      start = i
    } else {
      i++
    }
  }
  if (start < n) out.add(text.substring(start))
  return out
}
