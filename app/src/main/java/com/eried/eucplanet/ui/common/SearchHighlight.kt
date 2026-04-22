package com.eried.eucplanet.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

val LocalSettingsSearchQuery = compositionLocalOf { "" }

@Composable
fun highlightMatches(text: String, query: String): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(text)
    val highlightBg = Color(0xFFFFEB3B).copy(alpha = 0.55f)
    val highlightFg = Color.Black
    return buildAnnotatedString {
        append(text)
        var start = 0
        while (true) {
            val idx = text.indexOf(q, start, ignoreCase = true)
            if (idx < 0) break
            addStyle(SpanStyle(background = highlightBg, color = highlightFg), idx, idx + q.length)
            start = idx + q.length
        }
    }
}
