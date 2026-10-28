package com.eried.eucplanet.platform

// A Kotlin port of Android's org.json (AOSP libcore, Apache 2.0): JSON,
// JSONObject, JSONArray, JSONStringer and JSONTokener, limited to what the
// expect declarations offer. Keys keep insertion order, numbers print as
// JSONObject.numberToString does, and the tokener is just as lenient, so a
// file written by either phone reads back the same on the other.

actual open class JSONException actual constructor(message: String?) : Exception(message)

private object NullValue {
    override fun equals(other: Any?): Boolean = other === this || other == null
    override fun hashCode(): Int = 0
    override fun toString(): String = "null"
}

actual val JSONNull: Any get() = NullValue

actual fun jsonQuote(value: String?): String {
    if (value == null) return "\"\""
    val s = Stringer()
    s.string(value)
    return s.out.toString()
}

private fun checkDouble(d: Double): Double {
    if (d.isInfinite() || d.isNaN()) throw JSONException("Forbidden numeric value: $d")
    return d
}

private fun toBoolean(value: Any?): Boolean? = when (value) {
    is Boolean -> value
    is String -> when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        else -> null
    }
    else -> null
}

private fun parseJavaDouble(s: String): Double? = s.trim().toDoubleOrNull()

private fun toDouble(value: Any?): Double? = when (value) {
    is Double -> value
    is Number -> value.toDouble()
    is String -> parseJavaDouble(value)
    else -> null
}

private fun toInteger(value: Any?): Int? = when (value) {
    is Int -> value
    is Number -> value.toInt()
    is String -> parseJavaDouble(value)?.toInt()
    else -> null
}

private fun toLong(value: Any?): Long? = when (value) {
    is Long -> value
    is Number -> value.toLong()
    is String -> parseJavaDouble(value)?.toLong()
    else -> null
}

private fun toStr(value: Any?): String? = when (value) {
    is String -> value
    null -> null
    else -> value.toString()
}

private fun typeName(v: Any): String = when (v) {
    is String -> "java.lang.String"
    is Int -> "java.lang.Integer"
    is Long -> "java.lang.Long"
    is Double -> "java.lang.Double"
    is Float -> "java.lang.Float"
    is Boolean -> "java.lang.Boolean"
    is JSONObject -> "org.json.JSONObject"
    is JSONArray -> "org.json.JSONArray"
    NullValue -> "org.json.JSONObject$1"
    else -> v::class.simpleName ?: "Object"
}

private fun typeMismatch(indexOrName: Any, actual: Any?, requiredType: String): JSONException =
    if (actual == null) JSONException("Value at $indexOrName is null.")
    else JSONException("Value $actual at $indexOrName of type ${typeName(actual)} cannot be converted to $requiredType")

private fun typeMismatch(actual: Any?, requiredType: String): JSONException =
    if (actual == null) JSONException("Value is null.")
    else JSONException("Value $actual of type ${typeName(actual)} cannot be converted to $requiredType")

internal fun numberToString(number: Number): String {
    val doubleValue = number.toDouble()
    checkDouble(doubleValue)
    // Number.equals(-0d): only a Double negative zero.
    if (number is Double && number == 0.0 && 1.0 / number < 0) return "-0"
    val longValue = number.toLong()
    if (doubleValue == longValue.toDouble()) return longValue.toString()
    return number.toString()
}

private fun wrap(o: Any?): Any? = when (o) {
    null -> NullValue
    is JSONArray, is JSONObject, NullValue -> o
    is Collection<*> -> JSONArray(o)
    is Array<*> -> JSONArray(o.toList())
    is Map<*, *> -> JSONObject(o)
    is Boolean, is Byte, is Char, is Double, is Float, is Int, is Long, is Short, is String -> o
    else -> o.toString()
}

actual class JSONObject {
    internal val nameValuePairs: LinkedHashMap<String, Any?>

    actual constructor() {
        nameValuePairs = LinkedHashMap()
    }

    actual constructor(json: String) {
        val parsed = Tokener(json).nextValue()
        if (parsed is JSONObject) nameValuePairs = parsed.nameValuePairs
        else throw typeMismatch(parsed, "JSONObject")
    }

    actual constructor(copyFrom: Map<*, *>) {
        nameValuePairs = LinkedHashMap()
        for ((k, v) in copyFrom) {
            val key = k as? String ?: throw NullPointerException("key == null")
            nameValuePairs[key] = wrap(v)
        }
    }

    actual fun length(): Int = nameValuePairs.size
    actual fun has(name: String): Boolean = nameValuePairs.containsKey(name)
    actual fun isNull(name: String): Boolean {
        val v = nameValuePairs[name]
        return v == null || v === NullValue
    }
    actual fun opt(name: String): Any? = nameValuePairs[name]
    actual fun remove(name: String): Any? = nameValuePairs.remove(name)
    actual fun keys(): Iterator<String> = nameValuePairs.keys.iterator()
    actual fun names(): JSONArray? =
        if (nameValuePairs.isEmpty()) null else JSONArray(ArrayList(nameValuePairs.keys))

    actual fun put(name: String, value: Boolean): JSONObject = apply { nameValuePairs[name] = value }
    actual fun put(name: String, value: Double): JSONObject = apply { nameValuePairs[name] = checkDouble(value) }
    actual fun put(name: String, value: Int): JSONObject = apply { nameValuePairs[name] = value }
    actual fun put(name: String, value: Long): JSONObject = apply { nameValuePairs[name] = value }
    actual fun put(name: String, value: Any?): JSONObject {
        if (value == null) {
            nameValuePairs.remove(name)
            return this
        }
        if (value is Number) checkDouble(value.toDouble())
        nameValuePairs[name] = value
        return this
    }
    actual fun putOpt(name: String?, value: Any?): JSONObject {
        if (name == null || value == null) return this
        return put(name, value)
    }

    actual fun get(name: String): Any = nameValuePairs[name] ?: throw JSONException("No value for $name")
    actual fun getBoolean(name: String): Boolean = get(name).let { toBoolean(it) ?: throw typeMismatch(name, it, "boolean") }
    actual fun getDouble(name: String): Double = get(name).let { toDouble(it) ?: throw typeMismatch(name, it, "double") }
    actual fun getInt(name: String): Int = get(name).let { toInteger(it) ?: throw typeMismatch(name, it, "int") }
    actual fun getLong(name: String): Long = get(name).let { toLong(it) ?: throw typeMismatch(name, it, "long") }
    actual fun getString(name: String): String = get(name).let { toStr(it) ?: throw typeMismatch(name, it, "String") }
    actual fun getJSONArray(name: String): JSONArray = get(name).let { it as? JSONArray ?: throw typeMismatch(name, it, "JSONArray") }
    actual fun getJSONObject(name: String): JSONObject = get(name).let { it as? JSONObject ?: throw typeMismatch(name, it, "JSONObject") }

    actual fun optBoolean(name: String): Boolean = optBoolean(name, false)
    actual fun optBoolean(name: String, fallback: Boolean): Boolean = toBoolean(opt(name)) ?: fallback
    actual fun optDouble(name: String): Double = optDouble(name, Double.NaN)
    actual fun optDouble(name: String, fallback: Double): Double = toDouble(opt(name)) ?: fallback
    actual fun optInt(name: String): Int = optInt(name, 0)
    actual fun optInt(name: String, fallback: Int): Int = toInteger(opt(name)) ?: fallback
    actual fun optLong(name: String): Long = optLong(name, 0L)
    actual fun optLong(name: String, fallback: Long): Long = toLong(opt(name)) ?: fallback
    actual fun optString(name: String): String = optString(name, "")
    actual fun optString(name: String, fallback: String): String = toStr(opt(name)) ?: fallback
    actual fun optJSONArray(name: String): JSONArray? = opt(name) as? JSONArray
    actual fun optJSONObject(name: String): JSONObject? = opt(name) as? JSONObject

    internal fun writeTo(s: Stringer) {
        s.beginObject()
        for ((k, v) in nameValuePairs) s.key(k).value(v)
        s.endObject()
    }

    override fun toString(): String = runCatching { Stringer().also { writeTo(it) }.result() }.getOrDefault("")
    actual fun toString(indentSpaces: Int): String = Stringer(indentSpaces).also { writeTo(it) }.result()
}

actual class JSONArray {
    internal val values: MutableList<Any?>

    actual constructor() {
        values = ArrayList()
    }

    actual constructor(json: String) {
        val parsed = Tokener(json).nextValue()
        if (parsed is JSONArray) values = parsed.values
        else throw typeMismatch(parsed, "JSONArray")
    }

    actual constructor(copyFrom: Collection<*>) {
        values = ArrayList()
        for (o in copyFrom) values.add(wrap(o))
    }

    actual fun length(): Int = values.size
    actual fun isNull(index: Int): Boolean {
        val v = opt(index)
        return v == null || v === NullValue
    }
    actual fun opt(index: Int): Any? = if (index < 0 || index >= values.size) null else values[index]
    actual fun remove(index: Int): Any? = if (index < 0 || index >= values.size) null else values.removeAt(index)

    actual fun put(value: Boolean): JSONArray = apply { values.add(value) }
    actual fun put(value: Double): JSONArray = apply { values.add(checkDouble(value)) }
    actual fun put(value: Int): JSONArray = apply { values.add(value) }
    actual fun put(value: Long): JSONArray = apply { values.add(value) }
    actual fun put(value: Any?): JSONArray = apply { values.add(value) }

    actual fun get(index: Int): Any {
        if (index < 0 || index >= values.size) {
            throw JSONException("Index $index out of range [0..${values.size})")
        }
        return values[index] ?: throw JSONException("Value at $index is null.")
    }
    actual fun getBoolean(index: Int): Boolean = get(index).let { toBoolean(it) ?: throw typeMismatch(index, it, "boolean") }
    actual fun getDouble(index: Int): Double = get(index).let { toDouble(it) ?: throw typeMismatch(index, it, "double") }
    actual fun getInt(index: Int): Int = get(index).let { toInteger(it) ?: throw typeMismatch(index, it, "int") }
    actual fun getLong(index: Int): Long = get(index).let { toLong(it) ?: throw typeMismatch(index, it, "long") }
    actual fun getString(index: Int): String = get(index).let { toStr(it) ?: throw typeMismatch(index, it, "String") }
    actual fun getJSONArray(index: Int): JSONArray = get(index).let { it as? JSONArray ?: throw typeMismatch(index, it, "JSONArray") }
    actual fun getJSONObject(index: Int): JSONObject = get(index).let { it as? JSONObject ?: throw typeMismatch(index, it, "JSONObject") }

    actual fun optBoolean(index: Int): Boolean = optBoolean(index, false)
    actual fun optBoolean(index: Int, fallback: Boolean): Boolean = toBoolean(opt(index)) ?: fallback
    actual fun optDouble(index: Int): Double = optDouble(index, Double.NaN)
    actual fun optDouble(index: Int, fallback: Double): Double = toDouble(opt(index)) ?: fallback
    actual fun optInt(index: Int): Int = optInt(index, 0)
    actual fun optInt(index: Int, fallback: Int): Int = toInteger(opt(index)) ?: fallback
    actual fun optLong(index: Int): Long = optLong(index, 0L)
    actual fun optLong(index: Int, fallback: Long): Long = toLong(opt(index)) ?: fallback
    actual fun optString(index: Int): String = optString(index, "")
    actual fun optString(index: Int, fallback: String): String = toStr(opt(index)) ?: fallback
    actual fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray
    actual fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject

    internal fun writeTo(s: Stringer) {
        s.beginArray()
        for (v in values) s.value(v)
        s.endArray()
    }

    override fun toString(): String = runCatching { Stringer().also { writeTo(it) }.result() }.getOrDefault("")
    actual fun toString(indentSpaces: Int): String = Stringer(indentSpaces).also { writeTo(it) }.result()
}

/** AOSP JSONStringer. */
internal class Stringer(indentSpaces: Int? = null) {
    private enum class Scope { EMPTY_ARRAY, NONEMPTY_ARRAY, EMPTY_OBJECT, DANGLING_KEY, NONEMPTY_OBJECT }

    val out = StringBuilder()
    private val stack = ArrayList<Scope>()
    private val indent: String? = indentSpaces?.let { " ".repeat(it) }

    fun result(): String = out.toString()

    fun beginArray(): Stringer = open(Scope.EMPTY_ARRAY, "[")
    fun endArray(): Stringer = close(Scope.EMPTY_ARRAY, Scope.NONEMPTY_ARRAY, "]")
    fun beginObject(): Stringer = open(Scope.EMPTY_OBJECT, "{")
    fun endObject(): Stringer = close(Scope.EMPTY_OBJECT, Scope.NONEMPTY_OBJECT, "}")

    private fun open(empty: Scope, openBracket: String): Stringer {
        if (stack.isEmpty() && out.isNotEmpty()) throw JSONException("Nesting problem: multiple top-level roots")
        beforeValue()
        stack.add(empty)
        out.append(openBracket)
        return this
    }

    private fun close(empty: Scope, nonempty: Scope, closeBracket: String): Stringer {
        val context = peek()
        if (context != nonempty && context != empty) throw JSONException("Nesting problem")
        stack.removeAt(stack.size - 1)
        if (context == nonempty) newline()
        out.append(closeBracket)
        return this
    }

    private fun peek(): Scope {
        if (stack.isEmpty()) throw JSONException("Nesting problem")
        return stack[stack.size - 1]
    }

    private fun replaceTop(topOfStack: Scope) {
        stack[stack.size - 1] = topOfStack
    }

    fun value(value: Any?): Stringer {
        if (stack.isEmpty()) throw JSONException("Nesting problem")
        when (value) {
            is JSONArray -> { value.writeTo(this); return this }
            is JSONObject -> { value.writeTo(this); return this }
        }
        beforeValue()
        when (value) {
            null, is Boolean, NullValue -> out.append(value.toString())
            is Number -> out.append(numberToString(value))
            else -> string(value.toString())
        }
        return this
    }

    fun string(value: String) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"', '\\', '/' -> out.append('\\').append(c)
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\u000c' -> out.append("\\f")
                else -> if (c.code <= 0x1F) {
                    out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    private fun newline() {
        if (indent == null) return
        out.append("\n")
        repeat(stack.size) { out.append(indent) }
    }

    fun key(name: String): Stringer {
        beforeKey()
        string(name)
        return this
    }

    private fun beforeKey() {
        val context = peek()
        if (context == Scope.NONEMPTY_OBJECT) out.append(',')
        else if (context != Scope.EMPTY_OBJECT) throw JSONException("Nesting problem")
        newline()
        replaceTop(Scope.DANGLING_KEY)
    }

    private fun beforeValue() {
        if (stack.isEmpty()) return
        when (peek()) {
            Scope.EMPTY_ARRAY -> { replaceTop(Scope.NONEMPTY_ARRAY); newline() }
            Scope.NONEMPTY_ARRAY -> { out.append(','); newline() }
            Scope.DANGLING_KEY -> { out.append(if (indent == null) ":" else ": "); replaceTop(Scope.NONEMPTY_OBJECT) }
            else -> throw JSONException("Nesting problem")
        }
    }
}

/** AOSP JSONTokener, comments, single quotes and unquoted names included. */
private class Tokener(input: String) {
    private val input: String = if (input.startsWith("﻿")) input.substring(1) else input
    private var pos = 0

    fun nextValue(): Any {
        val c = nextCleanInternal()
        return when (c) {
            -1 -> throw syntaxError("End of input")
            '{'.code -> readObject()
            '['.code -> readArray()
            '\''.code, '"'.code -> nextString(c.toChar())
            else -> { pos--; readLiteral() }
        }
    }

    private fun nextCleanInternal(): Int {
        while (pos < input.length) {
            val c = input[pos++]
            when (c) {
                '\t', ' ', '\n', '\r' -> continue
                '/' -> {
                    if (pos == input.length) return c.code
                    when (input[pos]) {
                        '*' -> {
                            pos++
                            val commentEnd = input.indexOf("*/", pos)
                            if (commentEnd == -1) throw syntaxError("Unterminated comment")
                            pos = commentEnd + 2
                            continue
                        }
                        '/' -> { pos++; skipToEndOfLine(); continue }
                        else -> return c.code
                    }
                }
                '#' -> { skipToEndOfLine(); continue }
                else -> return c.code
            }
        }
        return -1
    }

    private fun skipToEndOfLine() {
        while (pos < input.length) {
            val c = input[pos]
            if (c == '\r' || c == '\n') { pos++; break }
            pos++
        }
    }

    private fun nextString(quote: Char): String {
        var builder: StringBuilder? = null
        var start = pos
        while (pos < input.length) {
            val c = input[pos++]
            if (c == quote) {
                if (builder == null) return input.substring(start, pos - 1)
                builder.append(input, start, pos - 1)
                return builder.toString()
            }
            if (c == '\\') {
                if (pos == input.length) throw syntaxError("Unterminated escape sequence")
                if (builder == null) builder = StringBuilder()
                builder.append(input, start, pos - 1)
                builder.append(readEscapeCharacter())
                start = pos
            }
        }
        throw syntaxError("Unterminated string")
    }

    private fun readEscapeCharacter(): Char {
        val escaped = input[pos++]
        return when (escaped) {
            'u' -> {
                if (pos + 4 > input.length) throw syntaxError("Unterminated escape sequence")
                val hex = input.substring(pos, pos + 4)
                pos += 4
                hex.toIntOrNull(16)?.toChar() ?: throw syntaxError("Invalid escape sequence: $hex")
            }
            't' -> '\t'
            'b' -> '\b'
            'n' -> '\n'
            'r' -> '\r'
            'f' -> '\u000c'
            else -> escaped
        }
    }

    private fun readLiteral(): Any {
        val literal = nextToInternal("{}[]/\\:,=;# \t\u000c")
        if (literal.isEmpty()) throw syntaxError("Expected literal value")
        if (literal.equals("null", ignoreCase = true)) return NullValue
        if (literal.equals("true", ignoreCase = true)) return true
        if (literal.equals("false", ignoreCase = true)) return false
        if (literal.indexOf('.') == -1) {
            var base = 10
            var number = literal
            if (number.startsWith("0x") || number.startsWith("0X")) {
                number = number.substring(2)
                base = 16
            } else if (number.startsWith("0") && number.length > 1) {
                number = number.substring(1)
                base = 8
            }
            number.toLongOrNull(base)?.let { v ->
                return if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v
            }
        }
        parseJavaDouble(literal)?.let { return it }
        return literal
    }

    private fun nextToInternal(excluded: String): String {
        val start = pos
        while (pos < input.length) {
            val c = input[pos]
            if (c == '\r' || c == '\n' || excluded.indexOf(c) != -1) return input.substring(start, pos)
            pos++
        }
        return input.substring(start)
    }

    private fun readObject(): JSONObject {
        val result = JSONObject()
        val first = nextCleanInternal()
        if (first == '}'.code) return result
        if (first != -1) pos--
        while (true) {
            val name = nextValue()
            if (name !is String) {
                throw syntaxError("Names must be strings, but $name is of type ${typeName(name)}")
            }
            val separator = nextCleanInternal()
            if (separator != ':'.code && separator != '='.code) throw syntaxError("Expected ':' after $name")
            if (pos < input.length && input[pos] == '>') pos++
            result.put(name, nextValue())
            when (nextCleanInternal()) {
                '}'.code -> return result
                ';'.code, ','.code -> continue
                else -> throw syntaxError("Unterminated object")
            }
        }
    }

    private fun readArray(): JSONArray {
        val result = JSONArray()
        var hasTrailingSeparator = false
        while (true) {
            when (nextCleanInternal()) {
                -1 -> throw syntaxError("Unterminated array")
                ']'.code -> {
                    if (hasTrailingSeparator) result.put(null as Any?)
                    return result
                }
                ','.code, ';'.code -> {
                    result.put(null as Any?)
                    hasTrailingSeparator = true
                    continue
                }
                else -> pos--
            }
            result.put(nextValue())
            when (nextCleanInternal()) {
                ']'.code -> return result
                ','.code, ';'.code -> { hasTrailingSeparator = true; continue }
                else -> throw syntaxError("Unterminated array")
            }
        }
    }

    private fun syntaxError(message: String) = JSONException("$message at character $pos of $input")
}
