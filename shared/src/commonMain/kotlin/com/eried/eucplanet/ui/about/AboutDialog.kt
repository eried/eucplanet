package com.eried.eucplanet.ui.about

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.CrashLog
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.UrlOpener

/**
 * About dialog — a faithful port of Android's 3-tab About (Credits, License,
 * Crash logs) with the inferno-ring logo, version + links. Replaces the old
 * one-pager. Uses the shared [CrashLog] store + [UrlOpener].
 */
@Composable
internal fun AboutDialog(connected: Boolean, connectedTitle: String, onDismiss: () -> Unit) {
    val c = MaterialTheme.appColors
    var tab by remember { mutableStateOf(0) }
    var crashes by remember { mutableStateOf(CrashLog.list()) }
    var openCrash by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        Box(Modifier.clickable(enabled = false) {}) {
            Column(
                Modifier.fillMaxWidth(0.94f).clip(RoundedCornerShape(18.dp)).background(c.dialog).heightIn(max = 640.dp).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Header — inferno-ring logo (brand colours over a dark scrim: the
                // documented theme-token exception), name, version, links, tagline.
                Canvas(Modifier.size(60.dp)) {
                    val r = size.minDimension / 2f
                    val ctr = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(Color(0xFF0D0D0D), radius = r, center = ctr)
                    drawCircle(Color(0xFF0288D1), radius = r * 0.72f, center = ctr, style = Stroke(width = r * 0.24f))
                    drawCircle(Color(0xFF29B6F6), radius = r * 0.80f, center = ctr, style = Stroke(width = r * 0.05f))
                    drawCircle(Color(0xFFE1F5FE), radius = r * 0.34f, center = ctr)
                }
                Spacer(Modifier.height(8.dp))
                Text("EUC Planet", color = c.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("v0.1 · iOS", color = c.textSecondary, fontSize = 12.sp)
                Text("eucplanet.ried.no", color = c.primary, fontSize = 12.sp, modifier = Modifier.clickable { UrlOpener.open("https://eucplanet.ried.no") })
                Spacer(Modifier.height(4.dp))
                Text("A no-nonsense, open-source app for electric unicycles.", color = c.textSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))

                // Tabs.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TabChip(c, "Credits", tab == 0) { tab = 0 }
                    TabChip(c, "License", tab == 1) { tab = 1 }
                    TabChip(c, "Crash logs" + if (crashes.isNotEmpty()) " (${crashes.size})" else "", tab == 2) { tab = 2; crashes = CrashLog.list() }
                }
                Spacer(Modifier.height(10.dp))

                Column(Modifier.fillMaxWidth().heightIn(min = 200.dp).weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    when (tab) {
                        0 -> CreditsTab(c)
                        1 -> Text(AboutContent.LICENSE, color = c.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                        else -> CrashTab(c, crashes, onOpen = { openCrash = it }, onDeleteAll = { CrashLog.deleteAll(); crashes = CrashLog.list() })
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (connected) Text("Connected · $connectedTitle", color = c.textDisabled, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(c.primary).clickable { onDismiss() }.padding(horizontal = 28.dp, vertical = 10.dp)) {
                    Text("Close", color = c.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    // Crash viewer over the dialog.
    openCrash?.let { name ->
        CrashViewer(
            c, name, CrashLog.read(name),
            onDelete = { CrashLog.delete(name); crashes = CrashLog.list(); openCrash = null },
            onClose = { openCrash = null },
        )
    }
}

@Composable
private fun TabChip(c: AppThemeColors, label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (selected) c.primary else c.textSecondary, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clickable { onClick() }.padding(vertical = 4.dp, horizontal = 4.dp),
    )
}

@Composable
private fun CreditsTab(c: AppThemeColors) {
    Row {
        Text("Made by ", color = c.textSecondary, fontSize = 13.sp)
        Text("Erwin Ried", color = c.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { UrlOpener.open("https://ried.no") })
        Text(" in Norway", color = c.textSecondary, fontSize = 13.sp)
    }
    Spacer(Modifier.height(8.dp))
    Text(AboutContent.MADE_BY_BODY, color = c.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
    Spacer(Modifier.height(14.dp))
    Text("THANKS TO", color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    AboutContent.THANKS.forEach { CreditRow(c, it.name, it.reason) }
    Spacer(Modifier.height(14.dp))
    Text("RESOURCES & LIBRARIES", color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    AboutContent.RESOURCES.forEach { CreditRow(c, it.name, it.reason) }
}

@Composable
private fun CreditRow(c: AppThemeColors, name: String, reason: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(name, color = c.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Text(reason, color = c.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun CrashTab(c: AppThemeColors, crashes: List<String>, onOpen: (String) -> Unit, onDeleteAll: () -> Unit) {
    if (crashes.isEmpty()) {
        Text("No crashes recorded.", color = c.textDisabled, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
        Text("If the app ever crashes, a log lands here automatically.", color = c.textDisabled, fontSize = 11.sp)
        return
    }
    crashes.forEach { name ->
        Row(Modifier.fillMaxWidth().clickable { onOpen(name) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🐞", fontSize = 13.sp)
            Spacer(Modifier.size(8.dp))
            Text(name, color = c.primary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("›", color = c.textDisabled, fontSize = 14.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Delete all logs", color = c.statusDanger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onDeleteAll() }.padding(vertical = 4.dp))
}

@Composable
private fun CrashViewer(c: AppThemeColors, name: String, text: String, onDelete: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { onClose() }, contentAlignment = Alignment.Center) {
        Box(Modifier.clickable(enabled = false) {}) {
            Column(Modifier.fillMaxWidth(0.94f).clip(RoundedCornerShape(16.dp)).background(c.dialog).heightIn(max = 620.dp).padding(16.dp)) {
                Text(name, color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(text.ifBlank { "(empty)" }, color = c.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.surfaceVariant).clickable { onClose() }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text("Close", color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.surfaceVariant).clickable { onDelete() }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text("Delete", color = c.statusDanger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
