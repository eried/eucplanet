package com.eried.eucplanet.service.hud

/**
 * Tiny helper for reading `debug.*` system properties without compiling
 * against the reflection-only `android.os.SystemProperties` class.
 *
 * `setprop debug.eucplanet.*` is only effective at runtime when the property
 * is set BEFORE the process is started (or before the relevant code path
 * runs). The harness is:
 *   adb shell setprop debug.eucplanet.hud.force true
 *   adb shell am force-stop com.eried.eucplanet
 *   adb shell am start -n com.eried.eucplanet/.MainActivity
 *
 * No effect on real devices; the props are never set unless a developer
 * deliberately runs the adb commands above.
 */
internal object HudDebug {
    fun read(name: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java).invoke(null, name) as? String
    } catch (_: Throwable) {
        null
    }
}
