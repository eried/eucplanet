package com.eried.eucplanet.hud.protocol

/**
 * Tiny reflection-only reader for `setprop debug.eucplanet.*` properties.
 * Lives in the shared `hud-protocol` module so the phone (`:app`) and the
 * HUD (`:hud`) reach for the same implementation instead of keeping twin
 * copies that drift.
 *
 * The properties only take effect when set BEFORE the process is started
 * (or before the relevant code path runs). The harness looks like:
 *   adb shell setprop debug.eucplanet.<key> <value>
 *   adb shell am force-stop <package>
 *   adb shell am start -n <package>/<Activity>
 *
 * No effect on real devices; nothing reads these unless a developer runs
 * the matching adb commands above.
 *
 * Current consumers:
 *  - app HudServer reads `debug.eucplanet.hud.peer`, `.hud.force`,
 *    `.demo` for emulator HUD-pairing without UI input.
 *  - hud HudServer reads `debug.eucplanet.proto.major` / `.proto.minor`
 *    to provoke every VersionCompat outcome without rebuilding a
 *    mismatched APK pair.
 */
object HudDebug {
    fun read(name: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java).invoke(null, name) as? String
    } catch (_: Throwable) {
        null
    }
}
