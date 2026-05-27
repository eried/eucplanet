using Toybox.Lang;
using Toybox.System;

//! Wire vocabulary shared with the phone-side bridge. Mirrors
//! `app/src/garminEnabled/kotlin/.../GarminProtocol.kt::GarminKeys` 1:1; keep
//! both files in lockstep when adding fields.
module Keys {
    const KIND = "k";
    const KIND_STATE = "state";
    const KIND_WAKE = "wake";
    const KIND_QUIT = "quit";
    const KIND_VIBRATE = "vibe";
    const VIBRATE_MS = "ms";

    const CONNECTED = "c";
    const SPEED = "s";
    const BATTERY = "b";
    const PHONE_BATT = "b2";
    const VOLTAGE = "v";
    const CURRENT = "i";
    const PWM = "p";
    const TEMP = "t";
    const TRIP_KM = "tr";
    const TORQUE = "tq";
    const LIGHT_ON = "l";
    const MAX_SPEED = "ms_g"; // collides with VIBRATE_MS otherwise; the phone sends "ms"
    const WHEEL_NAME = "n";
    const HAS_HORN = "ch";
    const HAS_LIGHT = "cl";
    const IMPERIAL = "im";
    const UNIT_SPEED = "us";
    const UNIT_DISTANCE = "ud";
    const UNIT_TEMP = "ut";
    const ACCENT = "ac";
    const TIMESTAMP = "ts";

    const GPS_SPEED = "gs";
    const GPS_SOURCE = "gsr";

    const OPT_KEEP_ON = "wko";
    const OPT_SHOW_WHEEL_BATT = "wsb";
    const OPT_SHOW_PHONE_BATT = "wpb";
    const OPT_SHOW_WATCH_BATT = "wwb";
    const OPT_PWM_DISPLAY = "wpd";
    const OPT_SHOW_SPEED_UNIT = "wsu";
    const OPT_PRIORITIZE_PWM = "wpp";
    const OPT_DIAL_ROTATION = "wrot";
    const OPT_GAUGE_BAND = "wgb";
    const OPT_GAUGE_ORANGE = "wgo";
    const OPT_GAUGE_RED = "wgr";

    const STEM1_CLICK = "s1c";
    const STEM1_HOLD = "s1h";
    const STEM2_CLICK = "s2c";
    const STEM2_HOLD = "s2h";

    const SCREEN1_CLICK = "b1c";
    const SCREEN1_HOLD = "b1h";
    const SCREEN2_CLICK = "b2c";
    const SCREEN2_HOLD = "b2h";

    const HAPTIC_ON_ACTION = "hap";

    const NAV_ACTIVE = "na";
    const NAV_ANGLE = "ng";
    const NAV_PRIMARY = "np";
    const NAV_DISTANCE = "nd";
    const NAV_ARRIVED = "nar";
}

//! Control intents sent from watch -> phone. Mirrors
//! `GarminControl` on the phone side.
module Control {
    const HORN = "horn";
    const LIGHT_ON = "light_on";
    const LIGHT_OFF = "light_off";
    const ACTION_PREFIX = "action:";
    const WATCH_INFO_PREFIX = "info:";
    const PAYLOAD_KEY = "cmd";
}

//! In-memory snapshot of the latest telemetry frame. Maps cleanly onto the
//! Wear OS `WatchState` data class so a port between surfaces is one-to-one.
//! Mutable fields are guarded by access through `WatchState.update` so the
//! View can `requestUpdate()` after every accepted frame.
class WatchSnapshot {
    public var connected as Lang.Boolean = false;
    public var phoneSynced as Lang.Boolean = false;
    public var wheelName as Lang.String = "";

    public var speedKmh as Lang.Float = 0.0;
    public var batteryPercent as Lang.Number = 0;
    public var phoneBatteryPercent as Lang.Number = 0;
    public var voltage as Lang.Float = 0.0;
    public var current as Lang.Float = 0.0;
    public var pwmPercent as Lang.Float = 0.0;
    public var temperatureC as Lang.Float = 0.0;
    public var tripKm as Lang.Float = 0.0;
    public var torque as Lang.Float = 0.0;
    public var lightOn as Lang.Boolean = false;
    public var maxSpeedKmh as Lang.Float = 30.0;
    public var hasHorn as Lang.Boolean = true;
    public var hasLight as Lang.Boolean = true;

    public var speedUnit as Lang.String = "kmh";
    public var distanceUnit as Lang.String = "km";
    public var tempUnit as Lang.String = "C";
    public var accentKey as Lang.String = "default";

    public var keepScreenOn as Lang.Boolean = true;
    public var showWheelBattery as Lang.Boolean = true;
    public var showPhoneBattery as Lang.Boolean = true;
    public var showWatchBattery as Lang.Boolean = true;
    public var pwmDisplay as Lang.String = "BOTH";
    public var showSpeedUnit as Lang.Boolean = true;
    public var prioritizePwm as Lang.Boolean = false;
    public var dialRotationDeg as Lang.Number = 0;
    public var showGaugeBand as Lang.Boolean = false;
    public var gaugeOrangeThresholdPct as Lang.Number = 65;
    public var gaugeRedThresholdPct as Lang.Number = 85;

    public var stem1Click as Lang.String = "NONE";
    public var stem1Hold as Lang.String = "NONE";
    public var stem2Click as Lang.String = "NONE";
    public var stem2Hold as Lang.String = "NONE";
    public var screen1Click as Lang.String = "HORN";
    public var screen1Hold as Lang.String = "NONE";
    public var screen2Click as Lang.String = "LIGHT_TOGGLE";
    public var screen2Hold as Lang.String = "NONE";
    public var hapticOnAction as Lang.Boolean = false;

    public var gpsSpeedKmh as Lang.Float = -1.0; // -1 == not present
    public var gpsSource as Lang.String = "";

    public var navActive as Lang.Boolean = false;
    public var navAngle as Lang.Float = 0.0;
    public var navPrimary as Lang.String = "";
    public var navDistance as Lang.String = "";
    public var navArrived as Lang.Boolean = false;

    public var lastUpdateMs as Lang.Number = 0;
    //! Monotonic counter of successfully-parsed phone frames since app
    //! start. Rendered as "rx N" in the dial corner so the rider (and
    //! the developer) can see whether subsequent frames are arriving in
    //! real time — useful for diagnosing "settings don't update" symptoms.
    public var frameCount as Lang.Number = 0;

    function initialize() {}
}

//! Module-level singleton accessor so the Bridge listener, View, and any
//! background context can read the same snapshot without DI gymnastics.
//! Monkey C doesn't have static fields the way Kotlin does; this is the
//! idiomatic workaround.
module WatchState {
    var snapshot as WatchSnapshot = new WatchSnapshot();
    var listener as Lang.Method? = null;

    //! Replace the snapshot in-place and notify the registered listener (the
    //! View) so it can `requestUpdate()`. `dict` is the raw Dictionary the
    //! Bridge received from the phone.
    function update(dict as Lang.Dictionary) as Void {
        var s = snapshot;

        s.connected = boolean(dict, Keys.CONNECTED, false);
        s.phoneSynced = true;
        s.wheelName = string(dict, Keys.WHEEL_NAME, "");

        s.speedKmh = float(dict, Keys.SPEED, 0.0);
        s.batteryPercent = number(dict, Keys.BATTERY, 0);
        s.phoneBatteryPercent = number(dict, Keys.PHONE_BATT, 0);
        s.voltage = float(dict, Keys.VOLTAGE, 0.0);
        s.current = float(dict, Keys.CURRENT, 0.0);
        s.pwmPercent = float(dict, Keys.PWM, 0.0);
        s.temperatureC = float(dict, Keys.TEMP, 0.0);
        s.tripKm = float(dict, Keys.TRIP_KM, 0.0);
        s.torque = float(dict, Keys.TORQUE, 0.0);
        s.lightOn = boolean(dict, Keys.LIGHT_ON, false);
        // Phone serialises gauge max under the literal "ms" key on the wire.
        // Keys.MAX_SPEED is a Monkey C-side alias to avoid colliding with the
        // VIBRATE_MS constant; the actual dict key is "ms".
        s.maxSpeedKmh = float(dict, "ms", 30.0);
        s.hasHorn = boolean(dict, Keys.HAS_HORN, true);
        s.hasLight = boolean(dict, Keys.HAS_LIGHT, true);

        s.speedUnit = string(dict, Keys.UNIT_SPEED, "kmh");
        s.distanceUnit = string(dict, Keys.UNIT_DISTANCE, "km");
        s.tempUnit = string(dict, Keys.UNIT_TEMP, "C");
        s.accentKey = string(dict, Keys.ACCENT, "default");

        s.keepScreenOn = boolean(dict, Keys.OPT_KEEP_ON, true);
        s.showWheelBattery = boolean(dict, Keys.OPT_SHOW_WHEEL_BATT, true);
        s.showPhoneBattery = boolean(dict, Keys.OPT_SHOW_PHONE_BATT, true);
        s.showWatchBattery = boolean(dict, Keys.OPT_SHOW_WATCH_BATT, true);
        s.pwmDisplay = string(dict, Keys.OPT_PWM_DISPLAY, "BOTH");
        s.showSpeedUnit = boolean(dict, Keys.OPT_SHOW_SPEED_UNIT, true);
        s.prioritizePwm = boolean(dict, Keys.OPT_PRIORITIZE_PWM, false);
        s.dialRotationDeg = number(dict, Keys.OPT_DIAL_ROTATION, 0);
        s.showGaugeBand = boolean(dict, Keys.OPT_GAUGE_BAND, false);
        s.gaugeOrangeThresholdPct = number(dict, Keys.OPT_GAUGE_ORANGE, 65);
        s.gaugeRedThresholdPct = number(dict, Keys.OPT_GAUGE_RED, 85);

        s.stem1Click = string(dict, Keys.STEM1_CLICK, "NONE");
        s.stem1Hold = string(dict, Keys.STEM1_HOLD, "NONE");
        s.stem2Click = string(dict, Keys.STEM2_CLICK, "NONE");
        s.stem2Hold = string(dict, Keys.STEM2_HOLD, "NONE");
        s.screen1Click = string(dict, Keys.SCREEN1_CLICK, "HORN");
        s.screen1Hold = string(dict, Keys.SCREEN1_HOLD, "NONE");
        s.screen2Click = string(dict, Keys.SCREEN2_CLICK, "LIGHT_TOGGLE");
        s.screen2Hold = string(dict, Keys.SCREEN2_HOLD, "NONE");
        s.hapticOnAction = boolean(dict, Keys.HAPTIC_ON_ACTION, false);

        s.gpsSpeedKmh = float(dict, Keys.GPS_SPEED, -1.0);
        s.gpsSource = string(dict, Keys.GPS_SOURCE, "");

        s.navActive = boolean(dict, Keys.NAV_ACTIVE, false);
        s.navAngle = float(dict, Keys.NAV_ANGLE, 0.0);
        s.navPrimary = string(dict, Keys.NAV_PRIMARY, "");
        s.navDistance = string(dict, Keys.NAV_DISTANCE, "");
        s.navArrived = boolean(dict, Keys.NAV_ARRIVED, false);

        s.lastUpdateMs = System.getTimer();
        s.frameCount = s.frameCount + 1;

        if (listener != null) { listener.invoke(); }
    }

    //! Register a zero-arg callback (typically a View's `requestUpdate`).
    function setListener(cb as Lang.Method?) as Void {
        listener = cb;
    }

    // Defensive accessors: CIQ Dictionary returns null for missing keys, and
    // type-coerces poorly across Number/Float boundaries depending on the
    // serializer path. Read each field with a safe fallback so a partial
    // frame never crashes the View.

    function boolean(d as Lang.Dictionary, k as Lang.String, fallback as Lang.Boolean) as Lang.Boolean {
        var v = d.get(k);
        if (v == null) { return fallback; }
        if (v instanceof Lang.Boolean) { return v; }
        if (v instanceof Lang.Number) { return v != 0; }
        return fallback;
    }

    function number(d as Lang.Dictionary, k as Lang.String, fallback as Lang.Number) as Lang.Number {
        var v = d.get(k);
        if (v == null) { return fallback; }
        if (v instanceof Lang.Number) { return v; }
        if (v instanceof Lang.Float || v instanceof Lang.Double) { return v.toNumber(); }
        return fallback;
    }

    function float(d as Lang.Dictionary, k as Lang.String, fallback as Lang.Float) as Lang.Float {
        var v = d.get(k);
        if (v == null) { return fallback; }
        if (v instanceof Lang.Float || v instanceof Lang.Double) { return v.toFloat(); }
        if (v instanceof Lang.Number) { return v.toFloat(); }
        return fallback;
    }

    function string(d as Lang.Dictionary, k as Lang.String, fallback as Lang.String) as Lang.String {
        var v = d.get(k);
        if (v == null) { return fallback; }
        if (v instanceof Lang.String) { return v; }
        return v.toString();
    }
}
