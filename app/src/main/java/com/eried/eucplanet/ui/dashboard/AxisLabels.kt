package com.eried.eucplanet.ui.dashboard

/**
 * The labels of a chart axis, one per grid line.
 *
 * Whole numbers are the default. A narrow range does not fit them: 2 to 5 in
 * four steps is 2, 2.75, 3.5, 4.25, 5, which printed whole reads 2, 3, 3, 4, 5
 * (Kotlin rounds half to even). When [format] would print the same label on
 * two lines, the axis takes one decimal, then two.
 */
fun axisLabels(values: List<Float>, format: (Float) -> String): List<String> {
    val plain = values.map(format)
    if (plain.distinct().size == plain.size) return plain
    val one = values.map { "%.1f".format(it) }
    if (one.distinct().size == one.size) return one
    return values.map { "%.2f".format(it) }
}
