package com.eried.eucplanet.ui.settings

import com.eried.eucplanet.data.model.ActionCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The settings search finds only what the phone draws. A row a platform
 * leaves out (SettingsHost.hiddenRows, the volume keys, the backup folder)
 * used to still come up in the search on iOS, landing the rider on nothing.
 * The generated index carries each text's row gates; these pin that it reads
 * them and that the filter drops what the phone leaves out.
 */
class SettingsSearchHiddenRowsTest {

    private fun entry(section: String, key: String) =
        SettingsSearchIndex.sections.getValue(section).first { it.res.key == key }

    private fun found(section: String, key: String, absent: Set<SettingsRow>) =
        SettingsSearchIndex.sections.getValue(section).any { it.res.key == key && it.shownWithout(absent) }

    /** What the iPhone leaves out (IosSettingsHost), with its host switches. */
    private val iphone = setOf(
        SettingsRow.BACK_ACTION, SettingsRow.KEEP_ALIVE,
        SettingsRow.PICTURE_IN_PICTURE, SettingsRow.HOME_WIDGET, SettingsRow.SHOW_OVER_LOCK_SCREEN,
        SettingsRow.ROTATION_LOCK, SettingsRow.VOICE_COMMANDS, SettingsRow.VOLUME_KEYS,
        SettingsRow.BACKUP_FOLDER, SettingsRow.VOICE_OUTPUT_CHANNEL,
    )

    @Test fun gatedRowsCarryTheirGate() {
        assertEquals(listOf(setOf(SettingsRow.BACK_ACTION)), entry("general", "back_button_action").gates)
        assertEquals(
            listOf(setOf(SettingsRow.BACK_ACTION, SettingsRow.KEEP_ALIVE)),
            entry("general", "section_application").gates,
        )
        assertTrue(listOf(setOf(SettingsRow.VOLUME_KEYS)) == entry("integration", "section_volume_keys").gates)
        assertEquals(listOf(setOf(SettingsRow.BACKUP_FOLDER)), entry("cloud", "cloud_choose_folder").gates)
        assertEquals(listOf(setOf(SettingsRow.VOICE_COMMANDS)), entry("voice", "voice_music_listening").gates)
        assertEquals(listOf(setOf(SettingsRow.VOICE_OUTPUT_CHANNEL)), entry("voice", "voice_output_channel_label").gates)
        // An ungated row has none.
        assertTrue(entry("voice", "voice_speech_speed").gates.isEmpty())
    }

    @Test fun androidFindsEverything() {
        for ((section, list) in SettingsSearchIndex.sections) {
            for (e in list) assertTrue(e.shownWithout(emptySet()), "${e.res.key} in $section")
        }
    }

    @Test fun theIphoneDoesNotFindWhatItLeavesOut() {
        assertFalse(found("general", "back_button_action", iphone))
        assertFalse(found("general", "section_application", iphone))
        assertFalse(found("integration", "section_volume_keys", iphone))
        assertFalse(found("integration", "volume_keys_enable", iphone))
        assertFalse(found("cloud", "cloud_choose_folder", iphone))
        assertFalse(found("voice", "voice_music_listening", iphone))
        assertFalse(found("voice", "voice_output_channel_label", iphone))
        // "While speaking" works there, through the audio session.
        assertTrue(found("voice", "voice_audio_focus_label", iphone))
        // What it does draw is still found.
        assertTrue(found("voice", "voice_speech_speed", iphone))
        assertTrue(found("cloud", "crews_pass_action", iphone))
        // The notification's quick actions are the Live Activity's buttons there.
        assertTrue(found("general", "notif_actions_enable", iphone))
        // The radar and the tyre caps run on the iPhone, so the search lands on them.
        assertTrue(found("integration", "section_radar", iphone))
        assertTrue(found("integration", "radar_pair_action", iphone))
        assertTrue(found("integration", "section_tpms", iphone))
        // So does the motor sound.
        assertTrue(found("motor", "engine_headphones_only", iphone))
    }

    @Test fun aHeadingStaysWhileAnyOfItsRowsShows() {
        assertTrue(found("general", "section_application", setOf(SettingsRow.BACK_ACTION)))
        assertFalse(found("general", "section_application", setOf(SettingsRow.BACK_ACTION, SettingsRow.KEEP_ALIVE)))
    }

    @Test fun listenIsOnlyOfferedWithSpokenCommands() {
        val all = ActionCatalog.all.map { it.key }
        assertTrue("VOICE_LISTEN" in ActionCatalog.pickableKeys(all, voiceCommands = true))
        val iphone = ActionCatalog.pickableKeys(all, voiceCommands = false)
        assertFalse("VOICE_LISTEN" in iphone)
        assertEquals(all.size - 1, iphone.size)
        // A Listen tile already placed draws as its pair; everything else as itself.
        assertEquals("VOICE_ANNOUNCE", ActionCatalog.tileKeyOn("VOICE_LISTEN", voiceCommands = false))
        assertEquals("VOICE_LISTEN", ActionCatalog.tileKeyOn("VOICE_LISTEN", voiceCommands = true))
        assertEquals("HORN", ActionCatalog.tileKeyOn("HORN", voiceCommands = false))
    }

    @Test fun volumeActionsAreOnlyOfferedWhereTheVolumeCanBeSet() {
        val all = ActionCatalog.all.map { it.key }
        val volume = setOf("PHONE_VOLUME_UP", "PHONE_VOLUME_DOWN", "PHONE_MUTE_TOGGLE")
        // Every guarded key is a real action, so a rename cannot slip past.
        assertEquals(volume, ActionCatalog.phoneVolumeKeys)
        assertTrue(all.containsAll(volume))
        assertTrue(ActionCatalog.pickableKeys(all, voiceCommands = true).containsAll(volume))
        val iphone = ActionCatalog.pickableKeys(all, voiceCommands = false, phoneVolume = false)
        assertTrue(iphone.none { it in volume || it == "VOICE_LISTEN" })
        assertEquals(all.size - volume.size - 1, iphone.size)
        // A volume tile synced from Android still draws as itself (it does nothing there).
        assertEquals("PHONE_MUTE_TOGGLE", ActionCatalog.tileKeyOn("PHONE_MUTE_TOGGLE", voiceCommands = false))
    }

    @Test fun musicPlayerActionsAreOnlyOfferedWhereThePlayerCanBeDriven() {
        val all = ActionCatalog.all.map { it.key }
        val player = setOf("MUSIC_START", "MEDIA_NEXT", "MEDIA_PREVIOUS")
        assertEquals(player, ActionCatalog.musicPlayerKeys)
        assertTrue(all.containsAll(player))
        assertTrue(ActionCatalog.pickableKeys(all, voiceCommands = true).containsAll(player))
        val iphone = ActionCatalog.pickableKeys(all, voiceCommands = true, musicPlayer = false)
        assertTrue(iphone.none { it in player })
        // Pausing works on every phone, so Pause and Play/Pause stay.
        assertTrue(iphone.containsAll(listOf("MEDIA_PAUSE", "MEDIA_PLAY_PAUSE")))
        assertEquals(all.size - player.size, iphone.size)
    }
}
