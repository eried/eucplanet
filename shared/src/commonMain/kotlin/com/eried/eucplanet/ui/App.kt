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
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.BuiltInThemes
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import com.eried.eucplanet.ui.theme.appColors

/**
 * First shared Compose Multiplatform screen — renders identically on Android and
 * iOS, now using the REAL EUC Planet theme tokens. A mock dashboard for now; the
 * real DashboardScreen migrates here next.
 */
@Composable
fun App() {
    EucPlanetTheme(colors = BuiltInThemes.dark.colors) {
        val c = MaterialTheme.appColors
        Surface(Modifier.fillMaxSize(), color = c.appBackground) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("EUC Planet", color = c.primary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("shared Compose · real theme · on iOS", color = c.textPrimary, fontSize = 14.sp)
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(c, "SPEED", "32.4", "km/h", c.textPrimary)
                    Tile(c, "BATTERY", "78", "%", c.metricBattery)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(c, "VOLTAGE", "94.1", "V", c.metricVoltage)
                    Tile(c, "TEMP", "41", "°C", c.metricTemp)
                }
                Spacer(Modifier.height(28.dp))
                Text(SharedBootstrap.selfTest(), color = c.textSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Tile(c: AppThemeColors, label: String, value: String, unit: String, valueColor: Color) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.tileBackground)
            .padding(horizontal = 26.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(value, color = valueColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(unit, color = c.textSecondary, fontSize = 12.sp)
    }
}
