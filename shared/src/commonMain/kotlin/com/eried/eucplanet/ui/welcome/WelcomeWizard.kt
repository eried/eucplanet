package com.eried.eucplanet.ui.welcome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.welcome_tut_actions
import com.eried.eucplanet.resources.welcome_tut_bt
import com.eried.eucplanet.resources.welcome_tut_camera
import com.eried.eucplanet.resources.welcome_tut_map
import com.eried.eucplanet.resources.welcome_tut_metrics
import com.eried.eucplanet.resources.welcome_tut_outro_end
import com.eried.eucplanet.resources.welcome_tut_outro_title
import com.eried.eucplanet.resources.welcome_tut_speed
import com.eried.eucplanet.resources.welcome_tut_version
import com.eried.eucplanet.resources.welcome_tut_voice_desc
import com.eried.eucplanet.resources.welcome_tut_voice_title
import com.eried.eucplanet.resources.welcome_tut_welcome
import com.eried.eucplanet.ui.theme.appColors
import org.jetbrains.compose.resources.stringResource

/**
 * First-launch coachmark spotlight tour — dims the live dashboard and highlights
 * the real UI element for each step (Android's welcome-tutorial style), with an
 * intro (voice opt-in) and outro. Rendered OVER the dashboard so [CoachmarkTargets]
 * are populated.
 */
@Composable
internal fun WelcomeWizard(onVoiceOptIn: (Boolean) -> Unit, onFinish: () -> Unit, initialStep: Int = 0) {
    val c = MaterialTheme.appColors
    // Steps use Android's exact welcome_tut_* strings (reused from strings.xml +
    // translations) — targetKey to body text.
    val steps: List<Pair<String?, String>> = listOf(
        null to stringResource(Res.string.welcome_tut_welcome),
        "bluetooth" to stringResource(Res.string.welcome_tut_bt),
        "speed" to stringResource(Res.string.welcome_tut_speed, "Display"),
        "metrics" to stringResource(Res.string.welcome_tut_metrics, "Dashboard"),
        "actions" to stringResource(Res.string.welcome_tut_actions, "", "Action buttons"),
        "map" to stringResource(Res.string.welcome_tut_map),
        "studio" to stringResource(Res.string.welcome_tut_camera),
        "version" to stringResource(Res.string.welcome_tut_version),
        null to (stringResource(Res.string.welcome_tut_outro_title) + "\n\n" + stringResource(Res.string.welcome_tut_outro_end)),
    )
    var step by remember { mutableStateOf(initialStep.coerceIn(0, steps.lastIndex)) }
    var voiceOn by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val s = steps[step]
    val last = step == steps.lastIndex
    // Target rect translated into this overlay's local space.
    val targetWin = s.first?.let { CoachmarkTargets.bounds[it] }
    val target = targetWin?.let { Rect(it.left - origin.x, it.top - origin.y, it.right - origin.x, it.bottom - origin.y) }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInWindow().topLeft }
            .pointerInput(Unit) { detectTapGestures { } }, // swallow taps to the UI beneath
    ) {
        val hPx = constraints.maxHeight.toFloat()
        val pad = 10f

        // Scrim with a rounded cut-out over the target (4 rects around it).
        Canvas(Modifier.fillMaxSize()) {
            val scrim = Color(0xCC000000)
            if (target == null) {
                drawRect(scrim)
            } else {
                val l = (target.left - pad).coerceAtLeast(0f)
                val t = (target.top - pad).coerceAtLeast(0f)
                val r = (target.right + pad).coerceAtMost(size.width)
                val b = (target.bottom + pad).coerceAtMost(size.height)
                drawRect(scrim, Offset(0f, 0f), Size(size.width, t))
                drawRect(scrim, Offset(0f, b), Size(size.width, size.height - b))
                drawRect(scrim, Offset(0f, t), Size(l, b - t))
                drawRect(scrim, Offset(r, t), Size(size.width - r, b - t))
                drawRoundRect(c.primary, Offset(l, t), Size(r - l, b - t), CornerRadius(10f, 10f), style = Stroke(3f))
            }
        }

        // Tooltip on the opposite half from the target so it never covers it.
        val targetBelow = target != null && target.center.y < hPx / 2f
        val align = if (target == null) Alignment.Center else if (targetBelow) Alignment.BottomCenter else Alignment.TopCenter
        Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = align) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.dialog).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("${step + 1} / ${steps.size}", color = c.textDisabled, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Text(s.second, color = c.textPrimary, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)

                if (step == 0) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.welcome_tut_voice_title), color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(stringResource(Res.string.welcome_tut_voice_desc), color = c.textSecondary, fontSize = 11.sp)
                        }
                        Switch(checked = voiceOn, onCheckedChange = { voiceOn = it; onVoiceOptIn(it) })
                    }
                }

                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!last) Text("Skip", color = c.textDisabled, fontSize = 13.sp, modifier = Modifier.clickable { onFinish() })
                    Spacer(Modifier.weight(1f))
                    if (step > 0) Text("Back", color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.clickable { step-- }.padding(end = 16.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(c.primary).clickable { if (last) onFinish() else step++ }.padding(horizontal = 22.dp, vertical = 10.dp),
                    ) { Text(if (last) "Done" else "Next", color = c.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
