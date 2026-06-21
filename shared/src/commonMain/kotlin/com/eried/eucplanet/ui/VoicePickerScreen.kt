package com.eried.eucplanet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.audio.availableTtsVoices
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.appColors

/**
 * System-voice picker — the iOS half of Android's Voice "voice type" selector.
 * Lists the device's installed TTS voices (from [availableTtsVoices], i.e.
 * `AVSpeechSynthesisVoice.speechVoices()`) grouped by language; tapping one
 * persists its identifier and the running TTS uses it immediately.
 */
@Composable
internal fun VoicePickerScreen(
    currentVoiceId: String,
    onSelect: (String) -> Unit,
    onPreview: (String) -> Unit,
    onBack: () -> Unit,
) {
    val c = MaterialTheme.appColors
    val grouped = remember {
        availableTtsVoices().sortedWith(compareBy({ it.language }, { it.name })).groupBy { it.language }
    }
    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        ScreenTopBar(c, "Voice", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
            VoiceRow(c, "System default", null, currentVoiceId.isBlank(), { onPreview("") }) { onSelect("") }
            if (grouped.isEmpty()) {
                Text("No additional system voices available on this device.", color = c.textDisabled, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
            grouped.forEach { (language, voices) ->
                Text(language.uppercase(), color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                voices.forEach { v ->
                    VoiceRow(c, v.name, v.language, currentVoiceId == v.id, { onPreview(v.id) }) { onSelect(v.id) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun VoiceRow(c: AppThemeColors, name: String, subtitle: String?, selected: Boolean, onPreview: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceVariant)
            .clickable { onClick() }.padding(start = 14.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = c.textSecondary, fontSize = 11.sp)
        }
        // Preview: tap the speaker icon to hear a sample spoken in this voice.
        Icon(
            Icons.Filled.VolumeUp, contentDescription = "Preview voice", tint = c.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPreview() }.padding(8.dp).size(20.dp),
        )
        if (selected) {
            Spacer(Modifier.size(6.dp))
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = c.primary, modifier = Modifier.size(20.dp))
        }
    }
}
