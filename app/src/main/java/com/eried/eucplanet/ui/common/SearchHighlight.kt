package com.eried.eucplanet.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.eried.eucplanet.ui.theme.appColors

val LocalSettingsSearchQuery = compositionLocalOf { "" }

/**
 * @param onFill the text sits on a filled control, a button: the accent behind
 *   a word vanishes on an accent button, so the match is inverted instead. The
 *   control's own ink becomes the band behind the word, and the word is black
 *   or white, whichever reads on that band. The ink always contrasts with the
 *   fill (a control rule), so the band shows on any button in any theme, and
 *   it is still a band, like the highlight everywhere else.
 */
@Composable
fun highlightMatches(text: String, query: String, onFill: Boolean = false): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(text)
    val style = if (onFill) {
        val ink = androidx.compose.material3.LocalContentColor.current
        SpanStyle(
            background = ink,
            color = if (ink.luminance() > 0.5f) Color.Black else Color.White,
        )
    } else SpanStyle(
        background = MaterialTheme.appColors.selection.copy(alpha = 0.55f),
        color = Color.Black,
    )
    return buildAnnotatedString {
        append(text)
        var start = 0
        while (true) {
            val idx = text.indexOf(q, start, ignoreCase = true)
            if (idx < 0) break
            addStyle(style, idx, idx + q.length)
            start = idx + q.length
        }
    }
}
