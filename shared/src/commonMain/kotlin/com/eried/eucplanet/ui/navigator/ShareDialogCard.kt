package com.eried.eucplanet.ui.navigator

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.ui.theme.appColors

/**
 * The shared dialog shell: a bordered card with a surfaceVariant header, the
 * same look the floating theme editor and the studio replay panel use.
 */
@Composable
fun ShareDialogCard(
    title: String,
    /** The header glyph. Defaults to what every share-side caller wants; the crews pairing
     *  card passes the scanner, because a card about pointing a camera at a code should not
     *  be headed by the share arrows. */
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Share,
    /** Lets the body scroll under a pinned header. The group view needs it: its
     *  QR is as wide as the dialog, so a long enough rider list would otherwise
     *  push Close and Leave off the bottom of the screen. */
    scrollable: Boolean = false,
    /** What the body is currently showing, when the card has tabs. The scroll
     *  position belongs to the tab, not to the card: one shared state carried
     *  a scroll down the QR tab straight into the rider list, which opened
     *  already scrolled past the rider's own row. */
    scrollKey: Any? = null,
    /** Drawn inside the header, directly under the title, on the header's own
     *  surface. The tab row is part of the card's chrome rather than a second
     *  strip stacked on it, so the two read as one block with one bottom edge. */
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        // Paired with usePlatformDefaultWidth = false at every call site. The
        // platform default is narrow enough that the three identity segments
        // get about 57dp of text each, which is what used to break "Anonymous"
        // across two lines; the shared control cannot fix a row it is not
        // given room for.
        //
        // The vertical margin is not cosmetic: a scrollable body makes the card
        // as tall as the window, and without it the Leave button sits flush
        // against the screen edge, under the gesture bar. safeDrawingPadding
        // adds whatever the system bars actually take on top of that.
        modifier = Modifier
            .fillMaxWidth()
            .safeDrawingPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.appColors.dialog,
            contentColor = MaterialTheme.appColors.textPrimary,
        ),
        border = BorderStroke(1.dp, MaterialTheme.appColors.outline),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Title and tabs share one surface and one bottom edge: the tabs
            // switch what the card shows, so they belong to its header rather
            // than to a second strip under it.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.appColors.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.appColors.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.appColors.textPrimary,
                    )
                }
                header?.invoke()
            }
            HorizontalDivider(color = MaterialTheme.appColors.divider)
            key(scrollKey) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (scrollable) Modifier.verticalScroll(rememberScrollState())
                            else Modifier
                        )
                        .padding(16.dp),
                    content = content,
                )
            }
        }
    }
}
