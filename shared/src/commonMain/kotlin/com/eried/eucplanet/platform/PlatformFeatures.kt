package com.eried.eucplanet.platform

import com.eried.eucplanet.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * A feature one phone has and another does not have yet: the iPhone app is
 * still catching up with Android. Each phone lists what it lacks in
 * [missingPlatformFeatures]; Android lacks nothing, so there every entry is
 * [available] and nothing changes.
 *
 * Where a feature is missing, its entry points stay on screen in a muted
 * colour and a tap says it is not on this phone yet, so a rider sees what is
 * coming instead of a button that does nothing. What a phone will never have
 * (volume keys, Android Auto, the watches, the HUD) is not listed here; the
 * hosts leave those out entirely (SettingsHost.hiddenSections and hiddenRows).
 *
 * [settingsSection] is the SectionDef key of a whole settings section the
 * feature owns, [actionKey] the ActionCatalog key of a dashboard tile that
 * opens it, and [title] the name its placeholder row shows.
 */
enum class PlatformFeature(
    val title: StringResource,
    val settingsSection: String? = null,
    val actionKey: String? = null,
) {
    NAVIGATION(Res.string.nav_setting_params, settingsSection = "navigator", actionKey = "OPEN_NAVIGATION"),
    GPS_SENSORS(Res.string.section_external_gps, settingsSection = "location"),
    RADAR(Res.string.section_radar),
    TPMS(Res.string.section_tpms),
    CAMERA_STUDIO(Res.string.action_chip_open_studio, actionKey = "OPEN_STUDIO"),
    MOTOR_SOUND(Res.string.section_engine_sound, settingsSection = "motor"),
    HOME_WIDGETS(Res.string.widget_section);

    companion object {
        fun forAction(key: String): PlatformFeature? = entries.firstOrNull { it.actionKey == key }
        fun forSection(key: String): PlatformFeature? = entries.firstOrNull { it.settingsSection == key }
    }
}

/** What this phone does not have yet. Empty on Android. */
expect val missingPlatformFeatures: Set<PlatformFeature>

/** False while this phone does not have the feature yet. */
val PlatformFeature.available: Boolean get() = this !in missingPlatformFeatures
