package com.eried.eucplanet.platform

/**
 * `java.util.Formatter` rules for the conversions shared code uses: `%s %S %d
 * %x %X %o %f %e %E %c %b %% %n`, explicit and relative argument indexes, the
 * flags `- # + space 0 , (`, width and precision. Floats round HALF_UP on the
 * shortest decimal form, as the JVM does (`"%.1f".format(0.25)` is "0.3"), and
 * a Float is widened to Double first (`"%.1f".format(-12.45f)` is "-12.4").
 */
object JavaFormat {
    data class Symbols(val decimal: Char, val grouping: Char) {
        companion object {
            val US = Symbols('.', ',')
        }
    }

    private val SPEC = Regex("""%(\d+\$|<)?([-#+ 0,(]*)(\d+)?(\.\d+)?([a-zA-Z%])""")

    fun format(symbols: Symbols, format: String, vararg args: Any?): String {
        val sb = StringBuilder()
        var last = 0
        var ordinary = 0
        var previous = -1
        for (m in SPEC.findAll(format)) {
            val between = format.substring(last, m.range.first)
            if ('%' in between) throw IllegalArgumentException("Unknown format conversion in '$format'")
            sb.append(between)
            last = m.range.last + 1
            val (indexPart, flags, widthPart, precisionPart, conv) = m.destructured
            val width = widthPart.toIntOrNull() ?: -1
            val precision = if (precisionPart.isEmpty()) -1 else precisionPart.substring(1).toInt()
            when (conv) {
                "%" -> { sb.append(pad("%", flags, width)); continue }
                "n" -> { sb.append('\n'); continue }
            }
            val index = when {
                indexPart == "<" -> previous
                indexPart.isNotEmpty() -> indexPart.dropLast(1).toInt() - 1
                else -> ordinary++
            }
            if (index < 0 || index >= args.size) throw IllegalArgumentException("Format specifier '${m.value}' has no argument")
            previous = index
            sb.append(convert(symbols, conv[0], flags, width, precision, args[index]))
        }
        val tail = format.substring(last)
        if ('%' in tail) throw IllegalArgumentException("Unknown format conversion in '$format'")
        sb.append(tail)
        return sb.toString()
    }

    private fun convert(sym: Symbols, conv: Char, flags: String, width: Int, precision: Int, arg: Any?): String =
        when (conv) {
            's', 'S' -> {
                var s = arg?.toString() ?: "null"
                if (precision >= 0 && precision < s.length) s = s.substring(0, precision)
                pad(if (conv == 'S') s.uppercase() else s, flags, width)
            }
            'b', 'B' -> {
                val s = when (arg) { null -> "false"; is Boolean -> arg.toString(); else -> "true" }
                pad(if (conv == 'B') s.uppercase() else s, flags, width)
            }
            'c', 'C' -> {
                val s = when (arg) {
                    null -> "null"
                    is Char -> arg.toString()
                    is Byte, is Short, is Int -> (arg as Number).toInt().toChar().toString()
                    else -> throw IllegalArgumentException("%c does not take ${arg::class.simpleName}")
                }
                pad(if (conv == 'C') s.uppercase() else s, flags, width)
            }
            'd' -> if (arg == null) pad("null", flags, width) else integer(sym, flags, width, signed(arg))
            'x', 'X', 'o' -> if (arg == null) pad("null", flags, width) else {
                val radix = if (conv == 'o') 8 else 16
                var digits = unsigned(arg, radix)
                if ('#' in flags) digits = (if (radix == 16) "0x" else "0") + digits
                if ('0' in flags && width > digits.length) {
                    val prefix = if ('#' in flags && radix == 16) 2 else 0
                    digits = digits.substring(0, prefix) + "0".repeat(width - digits.length) + digits.substring(prefix)
                }
                val s = pad(digits, flags.replace("0", ""), width)
                if (conv == 'X') s.uppercase() else s
            }
            'f', 'e', 'E' -> if (arg == null) pad("null", flags, width) else {
                val s = floating(sym, conv.lowercaseChar(), flags, width, if (precision < 0) 6 else precision, arg)
                if (conv == 'E') s.uppercase() else s
            }
            else -> throw IllegalArgumentException("Unknown format conversion '$conv'")
        }

    private fun signed(arg: Any): Long = when (arg) {
        is Byte -> arg.toLong()
        is Short -> arg.toLong()
        is Int -> arg.toLong()
        is Long -> arg
        else -> throw IllegalArgumentException("%d does not take ${arg::class.simpleName}")
    }

    private fun unsigned(arg: Any, radix: Int): String = when (arg) {
        is Byte -> (arg.toInt() and 0xFF).toString(radix)
        is Short -> (arg.toInt() and 0xFFFF).toString(radix)
        is Int -> (arg.toLong() and 0xFFFFFFFFL).toString(radix)
        is Long -> arg.toULong().toString(radix)
        else -> throw IllegalArgumentException("%x does not take ${arg::class.simpleName}")
    }

    private fun integer(sym: Symbols, flags: String, width: Int, value: Long): String {
        val negative = value < 0
        var magnitude = if (value == Long.MIN_VALUE) "9223372036854775808" else kotlin.math.abs(value).toString()
        if (',' in flags) magnitude = group(magnitude, sym.grouping)
        return signAndPad(magnitude, negative, flags, width)
    }

    private fun floating(sym: Symbols, conv: Char, flags: String, width: Int, precision: Int, arg: Any): String {
        val (text, negative, nonFinite) = when (arg) {
            is Float, is Double -> (arg as Number).toDouble().let { d ->
                Triple(d.toString(), d < 0.0 || (d == 0.0 && 1.0 / d < 0.0), !d.isFinite())
            }
            else -> throw IllegalArgumentException("%$conv does not take ${arg::class.simpleName}")
        }
        if (nonFinite) {
            val body = if (text.contains("NaN")) "NaN" else "Infinity"
            val signed = when {
                body == "NaN" -> body
                negative -> if ('(' in flags) "($body)" else "-$body"
                '+' in flags -> "+$body"
                ' ' in flags -> " $body"
                else -> body
            }
            return pad(signed, flags.replace("0", ""), width)
        }
        val (digits, exp) = decimalDigits(text.removePrefix("-"))
        val body = if (conv == 'f') fixed(digits, exp, precision, sym, ',' in flags)
        else scientific(digits, exp, precision, sym)
        return signAndPad(body, negative, flags, width)
    }

    /** Digits without leading zeros and the decimal exponent: value = 0.d1d2d3... * 10^exp. */
    private fun decimalDigits(text: String): Pair<String, Int> {
        val eAt = text.indexOfFirst { it == 'E' || it == 'e' }
        val mantissa = if (eAt >= 0) text.substring(0, eAt) else text
        val exp10 = if (eAt >= 0) text.substring(eAt + 1).toInt() else 0
        val dot = mantissa.indexOf('.')
        val intPart = if (dot >= 0) mantissa.substring(0, dot) else mantissa
        val fracPart = if (dot >= 0) mantissa.substring(dot + 1) else ""
        val all = intPart + fracPart
        val firstNonZero = all.indexOfFirst { it != '0' }
        if (firstNonZero < 0) return "0" to 1
        val digits = all.substring(firstNonZero).trimEnd('0').ifEmpty { "0" }
        val exp = intPart.length - firstNonZero + exp10
        return digits to exp
    }

    /** Rounds [digits] (value 0.digits * 10^exp) HALF_UP keeping [keep] digits; returns new digits and exp. */
    private fun roundHalfUp(digits: String, exp: Int, keep: Int): Pair<String, Int> {
        if (keep < 0) return "0" to exp
        if (digits.length <= keep) return digits to exp
        val head = digits.substring(0, keep).toCharArray()
        if (digits[keep] < '5') return head.concatToString().ifEmpty { "0" } to exp
        var i = head.size - 1
        while (i >= 0) {
            if (head[i] == '9') { head[i] = '0'; i-- } else { head[i] = head[i] + 1; break }
        }
        return if (i < 0) ("1" + head.concatToString()) to (exp + 1) else head.concatToString() to exp
    }

    private fun fixed(digits0: String, exp0: Int, precision: Int, sym: Symbols, grouping: Boolean): String {
        val keep = exp0 + precision
        val (digits, exp) = if (keep < 0) "0" to exp0 else roundHalfUp(digits0, exp0, keep)
        val roundedToZero = keep < 0 || (keep == 0 && digits == "0")
        val intDigits: String
        val fracDigits: String
        if (roundedToZero) {
            intDigits = "0"; fracDigits = "0".repeat(precision)
        } else if (exp <= 0) {
            intDigits = "0"
            fracDigits = ("0".repeat(-exp) + digits).padEnd(precision, '0').take(precision)
        } else {
            val padded = digits.padEnd(exp + precision, '0')
            intDigits = padded.substring(0, exp)
            fracDigits = padded.substring(exp, exp + precision)
        }
        val i = if (grouping) group(intDigits, sym.grouping) else intDigits
        return if (precision == 0) i else i + sym.decimal + fracDigits
    }

    private fun scientific(digits0: String, exp0: Int, precision: Int, sym: Symbols): String {
        if (digits0 == "0") return "0" + (if (precision > 0) sym.decimal + "0".repeat(precision) else "") + "e+00"
        val (digits, exp) = roundHalfUp(digits0, exp0, precision + 1)
        val padded = digits.padEnd(precision + 1, '0')
        val e = exp - 1
        val mant = padded.substring(0, 1) + (if (precision > 0) sym.decimal + padded.substring(1, precision + 1) else "")
        val ev = kotlin.math.abs(e).toString().padStart(2, '0')
        return mant + "e" + (if (e < 0) "-" else "+") + ev
    }

    private fun group(intDigits: String, sep: Char): String {
        val sb = StringBuilder()
        val first = intDigits.length % 3
        for ((n, c) in intDigits.withIndex()) {
            if (n != 0 && (n - first) % 3 == 0) sb.append(sep)
            sb.append(c)
        }
        return sb.toString()
    }

    private fun signAndPad(magnitude: String, negative: Boolean, flags: String, width: Int): String {
        val sign = when {
            negative && '(' in flags -> "("
            negative -> "-"
            '+' in flags -> "+"
            ' ' in flags -> " "
            else -> ""
        }
        val close = if (negative && '(' in flags) ")" else ""
        if ('0' in flags && '-' !in flags && width > 0) {
            val fill = width - sign.length - magnitude.length - close.length
            if (fill > 0) return sign + "0".repeat(fill) + magnitude + close
        }
        return pad(sign + magnitude + close, flags, width)
    }

    private fun pad(s: String, flags: String, width: Int): String = when {
        width <= s.length -> s
        '-' in flags -> s.padEnd(width)
        else -> s.padStart(width)
    }
}
