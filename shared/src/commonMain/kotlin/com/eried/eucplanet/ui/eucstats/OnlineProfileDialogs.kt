package com.eried.eucplanet.ui.eucstats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.cloud.Countries
import com.eried.eucplanet.cloud.RiderProfile
import com.eried.eucplanet.cloud.flagEmoji
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.appColors

/**
 * EUC Stats onboarding — a faithful port of Android's 2-step join flow: a
 * consent step (what becomes public) then a profile step (display name, country,
 * optional avatar). Calls back [onRegister] which the app turns into a register
 * + persist. The avatar is a 256×256 PNG base64 from the platform photo picker.
 */
@Composable
internal fun OnlineOnboardingDialog(
    busy: Boolean,
    errorMsg: String?,
    onPickAvatar: (onResult: (String?) -> Unit) -> Unit,
    onRegister: (name: String, flag: String, avatarBase64: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.appColors
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var flag by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf<String?>(null) }

    DialogScrim(onDismiss) {
        DialogCard(c) {
            if (step == 0) {
                Text("Join the EUC Stats leaderboard", color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text("These become public on eucstats.ried.no:", color = c.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                listOf(
                    "Your display name + country flag",
                    "Your photo (if you add one)",
                    "Lifetime distance, top speed, trips, rank",
                    "Approximate (clustered) ride locations",
                ).forEach { Text("•  $it", color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 1.dp)) }
                Spacer(Modifier.height(6.dp))
                Text("Raw GPS tracks are never published.", color = c.textDisabled, fontSize = 11.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton("Cancel", c.surfaceVariant, c.textPrimary, Modifier.weight(1f)) { onDismiss() }
                    DialogButton("Agree & continue", c.primary, c.onPrimary, Modifier.weight(1f)) { step = 1 }
                }
            } else {
                Text("Your profile", color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                AvatarPickerRow(c, avatar != null) { onPickAvatar { avatar = it } }
                Spacer(Modifier.height(12.dp))
                FieldLabel(c, "Display name")
                OutlinedTextField(name, { name = it.take(24) }, Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                FieldLabel(c, "Country")
                CountrySelector(c, flag) { flag = it }
                if (errorMsg != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(errorMsg, color = c.statusDanger, fontSize = 12.sp)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton("Back", c.surfaceVariant, c.textPrimary, Modifier.weight(1f)) { step = 0 }
                    val ready = name.isNotBlank() && flag.isNotBlank() && !busy
                    DialogButton(if (busy) "Joining…" else "Register", if (ready) c.primary else c.surfaceVariant, if (ready) c.onPrimary else c.textDisabled, Modifier.weight(1f)) {
                        if (ready) onRegister(name.trim(), flag, avatar)
                    }
                }
            }
        }
    }
}

/**
 * Manage-profile dialog — edit display name / country / avatar (honouring the
 * server's per-field cooldowns), export data, or delete the account. Mirrors
 * Android's OnlineProfileDialog.
 */
@Composable
internal fun ManageProfileDialog(
    profile: RiderProfile?,
    busy: Boolean,
    errorMsg: String?,
    onPickAvatar: (onResult: (String?) -> Unit) -> Unit,
    onSave: (name: String?, flag: String?, avatarBase64: String?) -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.appColors
    var name by remember(profile) { mutableStateOf(profile?.displayName ?: "") }
    var flag by remember(profile) { mutableStateOf(profile?.flag ?: "") }
    var avatar by remember(profile) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val nameLocked = profile?.canChangeNameAfter != null
    val flagLocked = profile?.canChangeFlagAfter != null
    val avatarLocked = profile?.canChangeAvatarAfter != null
    val changed = (name != (profile?.displayName ?: "") && !nameLocked) ||
        (flag != (profile?.flag ?: "") && !flagLocked) ||
        (avatar != null && !avatarLocked)

    DialogScrim(onDismiss) {
        DialogCard(c) {
            Text("My profile", color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (profile == null) {
                Text("Loading…", color = c.textSecondary, fontSize = 13.sp)
            } else {
                AvatarPickerRow(c, avatar != null || profile.hasAvatar, enabled = !avatarLocked) { onPickAvatar { avatar = it } }
                if (avatarLocked) CooldownNote(c, "avatar", profile.canChangeAvatarAfter)
                Spacer(Modifier.height(12.dp))
                FieldLabel(c, "Display name")
                OutlinedTextField(name, { name = it.take(24) }, Modifier.fillMaxWidth(), singleLine = true, enabled = !nameLocked)
                if (nameLocked) CooldownNote(c, "name", profile.canChangeNameAfter)
                Spacer(Modifier.height(10.dp))
                FieldLabel(c, "Country")
                if (flagLocked) {
                    Text("${flagEmoji(flag)}  ${Countries.nameFor(flag) ?: flag}", color = c.textDisabled, fontSize = 14.sp)
                    CooldownNote(c, "country", profile.canChangeFlagAfter)
                } else {
                    CountrySelector(c, flag) { flag = it }
                }
                if (errorMsg != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(errorMsg, color = c.statusDanger, fontSize = 12.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton("Close", c.surfaceVariant, c.textPrimary, Modifier.weight(1f)) { onDismiss() }
                    val ready = changed && name.isNotBlank() && flag.isNotBlank() && !busy
                    DialogButton(if (busy) "Saving…" else "Save", if (ready) c.primary else c.surfaceVariant, if (ready) c.onPrimary else c.textDisabled, Modifier.weight(1f)) {
                        if (ready) onSave(
                            if (name != profile.displayName) name.trim() else null,
                            if (flag != profile.flag) flag else null,
                            avatar,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Export my data", color = c.primary, fontSize = 12.sp, modifier = Modifier.clickable(enabled = !busy) { onExport() })
                    if (confirmDelete) {
                        Text("Tap again to delete", color = c.statusDanger, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(enabled = !busy) { onDelete() })
                    } else {
                        Text("Delete account", color = c.statusDanger, fontSize = 12.sp, modifier = Modifier.clickable(enabled = !busy) { confirmDelete = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarPickerRow(c: AppThemeColors, hasPhoto: Boolean, enabled: Boolean = true, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(if (hasPhoto) c.primary else c.surfaceVariant).clickable(enabled = enabled) { onPick() },
            contentAlignment = Alignment.Center,
        ) { Text(if (hasPhoto) "✓" else "＋", color = if (hasPhoto) c.onPrimary else c.textSecondary, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Text(if (hasPhoto) "Photo selected. Tap to change" else "Tap to add a photo (optional)", color = c.textSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun CountrySelector(c: AppThemeColors, selectedCode: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).clickable { open = !open }.padding(12.dp),
    ) {
        Text(
            if (selectedCode.isBlank()) "Select country" else "${flagEmoji(selectedCode)}  ${Countries.nameFor(selectedCode) ?: selectedCode}",
            color = if (selectedCode.isBlank()) c.textDisabled else c.textPrimary, fontSize = 14.sp,
        )
    }
    if (open) {
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search", color = c.textDisabled) })
        Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
            Countries.all.filter { query.isBlank() || it.name.contains(query, true) || it.code.contains(query, true) }.forEach { country ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(country.code); open = false; query = "" }.padding(vertical = 9.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(country.flag, fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(country.name, color = c.textPrimary, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun CooldownNote(c: AppThemeColors, field: String, date: String?) {
    Text("You can change your $field again on ${date ?: "later"}.", color = c.textDisabled, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun DialogScrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xCC000000)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        // Inner box swallows clicks so tapping the card doesn't dismiss.
        Box(Modifier.clickable(enabled = false) {}) { content() }
    }
}

@Composable
private fun DialogCard(c: AppThemeColors, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth(0.92f).clip(RoundedCornerShape(16.dp)).background(c.appBackground)
            .heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(18.dp),
    ) { content() }
}

@Composable
private fun FieldLabel(c: AppThemeColors, text: String) {
    Text(text, color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun DialogButton(label: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(bg).clickable { onClick() }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
