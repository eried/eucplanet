package com.eried.eucplanet.hud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eried.eucplanet.hud.R
import com.eried.eucplanet.hud.update.HudAppUpdate

/**
 * The one line shown over the HUD when an app sent by the phone did not
 * install, with Android's reason. The HUD asks nothing of its own about an
 * update: the rider asked for it on the phone and Android's prompt asks
 * here. Any key closes the line, read in the activity, and so does a tap
 * where the HUD is a phone or tablet.
 */
@Composable
fun AppUpdatePrompt() {
    val problem by HudAppUpdate.problem.collectAsStateWithLifecycle()
    val why = problem ?: return
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xF2000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                HudAppUpdate.dismissProblem()
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                stringResource(R.string.hud_update_failed),
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold
            )
            Text(why, color = Color(0xFFB9C0C7), fontSize = 16.sp, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.hud_update_dismiss),
                color = Color(0xFF8D949C), fontSize = 16.sp
            )
        }
    }
}
