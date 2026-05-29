package com.eried.eucplanet.hud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.hud.R

/**
 * Modal overlay that lets the rider type the phone's IP and port using only
 * the four-way DPAD on the HUD remote.
 *
 * Five fields: oct1 / oct2 / oct3 / oct4 / port. LEFT/RIGHT changes focus,
 * UP/DOWN bumps the focused value by 1, CENTER saves, ESC cancels. The
 * Activity routes keys here while [HudUiController.editor] is non-null --
 * the screen carousel does not see them.
 *
 * Visual goals: high-contrast on a black background (transflective HUD
 * panel), large digits so the rider doesn't have to squint to verify the IP.
 * Focused field is outlined; the other fields stay legible but recede.
 */
@Composable
fun ManualPeerOverlay(state: ManualPeerEditState) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xEE000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = textRes(R.string.hud_manual_peer_title),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OctetCell(state.oct1, focused = state.focus == 0)
                Dot()
                OctetCell(state.oct2, focused = state.focus == 1)
                Dot()
                OctetCell(state.oct3, focused = state.focus == 2)
                Dot()
                OctetCell(state.oct4, focused = state.focus == 3)
                Text(" : ", color = Color.White, fontSize = 28.sp)
                PortCell(state.port, focused = state.focus == 4)
            }

            Text(
                text = textRes(R.string.hud_manual_peer_help),
                color = Color(0xFFB0B0B0),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Text(
                text = textRes(R.string.hud_manual_peer_clear_hint),
                color = Color(0xFF808080),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

@Composable
private fun OctetCell(value: Int, focused: Boolean) {
    Cell(text = value.toString().padStart(3, ' '), focused = focused, widthDp = 64)
}

@Composable
private fun PortCell(value: Int, focused: Boolean) {
    Cell(text = value.toString().padStart(5, ' '), focused = focused, widthDp = 96)
}

@Composable
private fun Cell(text: String, focused: Boolean, widthDp: Int) {
    val border = if (focused) Color(0xFF00C853) else Color(0xFF303030)
    val bg = if (focused) Color(0xFF0F2A14) else Color(0xFF101010)
    Box(
        modifier = Modifier
            .width(widthDp.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(2.dp, border, RoundedCornerShape(6.dp))
            .padding(PaddingValues(horizontal = 6.dp, vertical = 8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 26.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun Dot() {
    Text(".", color = Color.White, fontSize = 28.sp, modifier = Modifier.padding(horizontal = 2.dp))
}

@Composable
private fun textRes(id: Int): String =
    androidx.compose.ui.platform.LocalContext.current.getString(id)
