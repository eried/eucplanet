package com.eried.eucplanet.voice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.eried.eucplanet.R
import com.eried.eucplanet.util.AppNotifier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The door a headset button knocks on.
 *
 * A Bluetooth headset's voice button does not send a key event an app can
 * intercept. It raises voice recognition over the hands-free profile
 * (`AT+BVRA`), and Android turns that into `ACTION_VOICE_COMMAND`. Whoever
 * handles that intent is who the button reaches, which is why the app has to
 * declare it rather than listen for a key that never arrives. The headset's
 * volume buttons are no use either: those ride AVRCP absolute volume and are
 * handled inside the audio stack, never reaching an activity.
 *
 * It shows nothing. The activity exists to be started and to finish, leaving
 * the listening session and its tones as the whole interaction, because a
 * rider pressing a helmet button has their eyes on the road and a phone in a
 * pocket. Finishing immediately also means it never sits on top of the
 * dashboard, so the transcript and the tile the session drives stay visible
 * underneath if the phone is out.
 */
@AndroidEntryPoint
class VoiceTriggerActivity : ComponentActivity() {

    @Inject lateinit var voiceCommands: VoiceCommandController

    @Inject lateinit var appNotifier: AppNotifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Permission is checked inside listen(), which also raises the
        // dashboard warning and says out loud what is missing. Repeating that
        // here would be a second place to keep the wording right.
        lifecycleScope.launch {
            // notify = true: nothing on screen said this started. The tile
            // cue only exists for a rider looking at the dashboard, and this
            // entry point is for one who is not.
            voiceCommands.listen(notify = true)
        }
        finish()
    }

    companion object {
        /** For the diagnostics log, so a headset press is distinguishable. */
        const val SOURCE = "headset"
    }
}
