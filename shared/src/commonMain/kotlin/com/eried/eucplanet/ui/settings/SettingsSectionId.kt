package com.eried.eucplanet.ui.settings

/**
 * The canonical settings-section list, shared by the Android app and the iOS app
 * so the two can never silently drift. Declaration order **is** the on-screen
 * order on both platforms (each renders [entries] in order).
 *
 * Each platform maps an id to its own *title*, *icon* and *body*, and those stay
 * platform-specific on purpose: the section bodies are coupled to platform
 * infrastructure (Android uses Hilt view-models, Room, WorkManager and
 * `R.string`; iOS renders a v1 subset), and some sections diverge by nature —
 * e.g. [Watch] targets Wear OS / Garmin on Android but Apple Watch on iOS, and
 * [Integration] / [Navigator] are Android-only for now.
 *
 * What is shared and **compiler-enforced** is the *set and order* of sections:
 * both platforms render [entries] through an exhaustive `when`, so adding or
 * removing an id here is a build error on whichever side forgot to handle it —
 * never an "8 sections on iOS vs 13 on Android" mismatch that ships unnoticed.
 *
 * Consumed by:
 *  - iOS:     `shared/commonMain/.../ui/SettingsScreen.kt`
 *  - Android: `app/.../ui/settings/SettingsScreen.kt` (`val sections`)
 */
enum class SettingsSectionId {
    General,
    Dashboard,
    Display,
    Speed,
    Voice,
    Motor,
    Cloud,
    Alarms,
    Automations,
    Navigator,
    Location,
    Integration,
    Watch,
}
