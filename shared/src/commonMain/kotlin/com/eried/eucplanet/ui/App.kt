package com.eried.eucplanet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.SharedBootstrap

/**
 * First shared Compose Multiplatform screen — renders identically on Android and
 * iOS. A mock dashboard for now; the real DashboardScreen migrates here next.
 */
@Composable
fun App() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101418)) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("EUC Planet", color = Color(0xFF3DDC84), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("shared Compose UI · running on iOS", color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile("SPEED", "32.4", "km/h")
                    Tile("BATTERY", "78", "%")
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile("VOLTAGE", "94.1", "V")
                    Tile("TEMP", "41", "°C")
                }
                Spacer(Modifier.height(28.dp))
                Text(SharedBootstrap.selfTest(), color = Color(0xFF8899AA), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1B2127))
            .padding(horizontal = 26.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color(0xFF8899AA), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(value, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(unit, color = Color(0xFF8899AA), fontSize = 12.sp)
    }
}
