package com.eried.eucplanet.hud.net

/**
 * HUD-side mirror of the phone's `HudDebug`: tiny reflection-only reader
 * for `setprop debug.eucplanet.*` properties. No effect on production
 * HUDs (none of these props are ever set unless a developer runs the
 * matching `adb shell setprop` against the device).
 *
 * Used right now ONLY for the protocol-version overrides:
 *   adb shell setprop debug.eucplanet.proto.major 2
 *   adb shell setprop debug.eucplanet.proto.minor 5
 *   adb shell am force-stop com.eried.eucplanet.hud
 *   adb shell am start -n com.eried.eucplanet.hud/.HudActivity
 *
 * Setting these to a value different from the real PROTOCOL_MAJOR /
 * PROTOCOL_MINOR lets a tester provoke any of the five [VersionCompat]
 * outcomes (EXACT / *_BEHIND_MINOR / *_AHEAD_MINOR / *_BEHIND_MAJOR /
 * *_AHEAD_MAJOR) from BOTH ends without having to rebuild a deliberately
 * mismatched APK pair.
 */
internal object HudDebug {
    fun read(name: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java).invoke(null, name) as? String
    } catch (_: Throwable) {
        null
    }
}
