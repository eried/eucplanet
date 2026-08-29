package com.eried.eucplanet.ui.settings.eucstats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.data.eucstats.RiderCard
import com.eried.eucplanet.ui.theme.appColors

/**
 * Who the rider is on the leaderboard: their picture, their name with their
 * flag, their country. One composable, so the settings screen and the live
 * share dialog cannot drift into two different-looking profile cards.
 *
 * Deliberately only the identity, never the stats: the settings screen draws
 * its distance and rank lines under this row, and the share dialog has no
 * business showing them while the rider is picking how to appear.
 *
 * The avatar is 48 dp, the size a list row carries. It is the real photo when
 * the server has one and the rider's initial on the accent otherwise, so the
 * row is the same height either way and never collapses while the picture
 * loads.
 */
@Composable
fun LeaderboardProfileCard(card: RiderCard, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        val initial = card.displayName?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        RemoteAvatar(
            url = card.avatarUrl,
            modifier = Modifier.size(48.dp).clip(CircleShape),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.appColors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.appColors.onPrimary,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val nameAndFlag = buildString {
                if (!card.displayName.isNullOrBlank()) append(card.displayName)
                if (!card.flag.isNullOrBlank()) {
                    if (isNotEmpty()) append("  ")
                    // Show the flag emoji (e.g. NO becomes the Norwegian flag)
                    // instead of the raw code.
                    append(flagEmoji(card.flag).ifEmpty { card.flag })
                }
            }
            if (nameAndFlag.isNotEmpty()) {
                Text(
                    nameAndFlag,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.appColors.textPrimary,
                )
            }
            if (!card.country.isNullOrBlank()) {
                Text(
                    card.country,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.textSecondary,
                )
            }
        }
    }
}
