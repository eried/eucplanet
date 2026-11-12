package com.eried.eucplanet.hud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eried.eucplanet.hud.R
import com.eried.eucplanet.hud.update.HudAppUpdate

/**
 * Full-screen question over the HUD when the phone has sent a newer HUD app:
 * OK installs it, BACK drops it. Also carries the one line shown when an
 * install did not go through. The keys are read in the activity. A HUD has
 * no touch, but the app also runs on a phone or tablet used as one, and
 * there a tap is the only OK there is: the button installs, the line under
 * it skips, and a tap anywhere clears the failure line.
 */
@Composable
fun AppUpdatePrompt(accent: Color) {
    val pending by HudAppUpdate.pending.collectAsStateWithLifecycle()
    val problem by HudAppUpdate.problem.collectAsStateWithLifecycle()
    val p = pending
    if (p == null && problem == null) return
    val activity = LocalContext.current as? android.app.Activity
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xF2000000))
            // Takes every tap so none reaches the screen underneath, the
            // same way the activity keeps the keys.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (p == null) HudAppUpdate.dismissProblem()
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            if (p != null) {
                Text(
                    stringResource(R.string.hud_update_title),
                    color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.hud_update_version, p.versionName),
                    color = Color(0xFFB9C0C7), fontSize = 20.sp
                )
                Text(
                    stringResource(R.string.hud_update_ok),
                    color = accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { activity?.let { HudAppUpdate.install(it) } }
                        .border(2.dp, accent, RoundedCornerShape(8.dp))
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )
                Text(
                    stringResource(R.string.hud_update_skip),
                    color = Color(0xFF8D949C), fontSize = 16.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { HudAppUpdate.skip() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else {
                Text(
                    stringResource(R.string.hud_update_failed),
                    color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    problem.orEmpty(),
                    color = Color(0xFFB9C0C7), fontSize = 16.sp, textAlign = TextAlign.Center
                )
                Text(
                    stringResource(R.string.hud_update_dismiss),
                    color = Color(0xFF8D949C), fontSize = 16.sp
                )
            }
        }
    }
}
