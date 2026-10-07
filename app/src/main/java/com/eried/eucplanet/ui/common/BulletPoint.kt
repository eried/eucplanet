package com.eried.eucplanet.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.ui.theme.appColors

/**
 * One "•" bullet with its text beside it.
 *
 * The app lists things in dialogs in several places (the upload consent list,
 * the constant-beep and advanced-reset confirmations, the lockdown warning) and
 * each had grown its own version, some baking the bullet into the string where
 * it could not be styled and would not hang correctly on a wrapped second line.
 * This is the one shape they all share.
 */
@Composable
fun BulletPoint(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.appColors.textPrimary,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = "•",
            color = MaterialTheme.appColors.primary,
            style = style,
            fontWeight = FontWeight.Bold,
        )
        Text(text = text, color = color, style = style)
    }
}
