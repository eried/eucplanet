package com.eried.eucplanet.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.theme.appColors

/**
 * The one button that resolves a warning, wherever the warning is shown.
 *
 * There is a Fix on the dashboard and a Fix inside Settings, and they used to
 * be different controls: a filled button in the dialog, a bare TextButton in
 * the settings rows. The bare one sat on the same colour as the surface behind
 * it and read as a label rather than something to press, which is a poor way to
 * present the only way out of a broken feature.
 *
 * Filled with the accent rather than the tonal fill, which is the mistake this
 * replaced: the tonal token falls back to surfaceVariant, the same colour the
 * warning card uses, so the button dissolved into the card it sat on. The
 * accent cannot collide with a surface, and its on-colour is set explicitly so
 * the label stays legible in the dark, light and custom themes alike.
 */
@Composable
fun FixButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
) {
    val c = MaterialTheme.appColors
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.primary,
            contentColor = c.onPrimary,
        ),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(text ?: androidx.compose.ui.res.stringResource(R.string.warnings_fix_button))
    }
}
