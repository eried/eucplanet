using Toybox.Lang;
using Toybox.WatchUi;

//! Unit conversion for the watch dial. Same coverage as the phone's
//! `util/Units.kt`: speed (kmh/mph/ms/kn), distance (km/mi/m/ft/mil),
//! temperature (C/F/K). Conversions match the phone bit-for-bit so the
//! number on the wrist equals the number on the phone dashboard.
module Units {
    function convertSpeedFromKmh(kmh as Lang.Float, unit as Lang.String) as Lang.Float {
        if (unit.equals("mph")) { return kmh * 0.6213712; }
        if (unit.equals("ms"))  { return kmh / 3.6; }
        if (unit.equals("kn"))  { return kmh * 0.5399568; }
        return kmh; // default "kmh"
    }

    function speedUnitLabel(unit as Lang.String) as Lang.String {
        if (unit.equals("mph")) { return WatchUi.loadResource(Rez.Strings.SpeedUnitMph); }
        if (unit.equals("ms"))  { return WatchUi.loadResource(Rez.Strings.SpeedUnitMs); }
        if (unit.equals("kn"))  { return WatchUi.loadResource(Rez.Strings.SpeedUnitKn); }
        return WatchUi.loadResource(Rez.Strings.SpeedUnitKmh);
    }

    function convertDistanceFromKm(km as Lang.Float, unit as Lang.String) as Lang.Float {
        if (unit.equals("mi"))  { return km * 0.6213712; }
        if (unit.equals("m"))   { return km * 1000.0; }
        if (unit.equals("ft"))  { return km * 3280.84; }
        if (unit.equals("mil")) { return km * 0.5399568; }
        return km;
    }

    function convertTempFromC(c as Lang.Float, unit as Lang.String) as Lang.Float {
        if (unit.equals("F")) { return (c * 9.0 / 5.0) + 32.0; }
        if (unit.equals("K")) { return c + 273.15; }
        return c;
    }
}
