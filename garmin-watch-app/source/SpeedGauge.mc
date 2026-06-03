using Toybox.Graphics;
using Toybox.Lang;
using Toybox.Math;

//! Port of wear/.../SpeedGauge.kt. 260-degree arc with the opening at the
//! BOTTOM, optional safety band on the outside, speed numeral + unit label
//! centered inside. Layout mirrors the Wear OS dial 1:1; visual diffs flow
//! either direction.
module SpeedGauge {

    const COLOR_TRACK = 0x2A2A2A;
    const COLOR_DIM = 0x9AA0A6;
    const COLOR_SAFE = 0x2ECC40;
    const COLOR_WARN = 0xFFB400;
    const COLOR_DANGER = 0xE74C3C;

    //! Draws the gauge centered on the Dc. Speed text + unit are drawn last
    //! so they win over the arc.
    //! Older CIQ runtimes (3.1.6 era — Fenix 5/5s/5x) cap method arity
    //! at 9, so the snapshot is passed in whole instead of unpacked into
    //! individual params. Reads every field it needs from `s` directly.
    function draw(
        dc as Graphics.Dc,
        s as WatchSnapshot
    ) as Void {
        var speedKmh = s.speedKmh;
        var maxSpeedKmh = s.maxSpeedKmh;
        var speedUnit = s.speedUnit;
        var showColorBand = s.showGaugeBand;
        var orangeThresholdPct = s.gaugeOrangeThresholdPct;
        var redThresholdPct = s.gaugeRedThresholdPct;
        var gpsSpeedKmh = s.gpsSpeedKmh;
        var showSpeedUnit = s.showSpeedUnit;
        var prioritizePwm = s.prioritizePwm;
        var w = dc.getWidth();
        var h = dc.getHeight();
        var dim = w < h ? w : h;
        var cx = w / 2;
        var cy = (h * 50) / 100;

        var arcThickness = (dim * 6) / 100;
        var arcInset = (dim * 5) / 100;
        var arcRadius = (dim / 2) - arcThickness - arcInset;

        // Wear OS Canvas uses 0°=East, clockwise-positive, startAngle=140°,
        // sweep=260°. That traces from lower-left, UP through the top, to
        // lower-right — opening at the bottom (the 100° gap between angles
        // 40° and 140° in Wear coords).
        //
        // CIQ uses 0°=East, counter-clockwise-positive (math convention).
        // Same visual arc → start at CIQ 220° (=Wear 140°, lower-left),
        // end at CIQ 320° (=Wear 40°, lower-right), going CLOCKWISE on
        // screen which in CIQ angle terms means decreasing angle, i.e.
        // ARC_CLOCKWISE.
        var ARC_DIR = Graphics.ARC_CLOCKWISE;
        var START = 220;
        var END = 320;
        var SWEEP = 360 - (END - START); // 260°, going the long way through the top

        var maxSafe = maxSpeedKmh > 0.0 ? maxSpeedKmh : 1.0;
        var frac = speedKmh / maxSafe;
        if (frac < 0) { frac = 0.0; }
        if (frac > 1.0) { frac = 1.0; }

        var orangeFrac = clamp01(orangeThresholdPct / 100.0, 0.05, 0.95);
        var redFrac = clamp01(redThresholdPct / 100.0, orangeFrac + 0.04, 0.95);

        var speedColor;
        if (showColorBand && frac >= redFrac) {
            speedColor = COLOR_DANGER;
        } else if (showColorBand && frac >= orangeFrac) {
            speedColor = COLOR_WARN;
        } else {
            speedColor = COLOR_SAFE;
        }

        // Track arc (dark grey background).
        dc.setPenWidth(arcThickness);
        dc.setColor(COLOR_TRACK, Graphics.COLOR_TRANSPARENT);
        dc.drawArc(cx, cy, arcRadius, ARC_DIR, START, END);

        // Thin safety band on the OUTSIDE of the arc. Three coloured
        // sections: safe (green) / warn (orange) / danger (red). Only drawn
        // when the rider has the band enabled in phone settings.
        if (showColorBand) {
            var bandThickness = (arcThickness * 25) / 100;
            if (bandThickness < 2) { bandThickness = 2; }
            var bandRadius = arcRadius + ((arcThickness * 60) / 100) + (bandThickness / 2);
            dc.setPenWidth(bandThickness);

            // Translate fractions along the sweep into CIQ angles, decreasing
            // from START down through 0°, wrapping to 360°, ending at END.
            var orangeAngle = wrapDeg(START - (SWEEP * orangeFrac).toNumber());
            var redAngle    = wrapDeg(START - (SWEEP * redFrac).toNumber());

            dc.setColor(COLOR_SAFE, Graphics.COLOR_TRANSPARENT);
            dc.drawArc(cx, cy, bandRadius, ARC_DIR, START, orangeAngle);
            dc.setColor(COLOR_WARN, Graphics.COLOR_TRANSPARENT);
            dc.drawArc(cx, cy, bandRadius, ARC_DIR, orangeAngle, redAngle);
            dc.setColor(COLOR_DANGER, Graphics.COLOR_TRANSPARENT);
            dc.drawArc(cx, cy, bandRadius, ARC_DIR, redAngle, END);
        }

        // Speed arc on top of the track.
        if (frac > 0.005) {
            dc.setPenWidth(arcThickness);
            dc.setColor(speedColor, Graphics.COLOR_TRANSPARENT);
            var speedEnd = wrapDeg(START - (SWEEP * frac).toNumber());
            dc.drawArc(cx, cy, arcRadius, ARC_DIR, START, speedEnd);
        }

        // GPS extra-speed dot. Skipped when -1 (sentinel for "not present").
        if (gpsSpeedKmh >= 0.0) {
            var gpsFrac = gpsSpeedKmh / maxSafe;
            if (gpsFrac > 1.0) { gpsFrac = 1.0; }
            var gpsAngle = wrapDeg(START - (SWEEP * gpsFrac).toNumber());
            var rad = Math.toRadians(gpsAngle);
            var dotR = (arcThickness * 45) / 100;
            var dx = cx + (arcRadius * Math.cos(rad)).toNumber();
            var dy = cy - (arcRadius * Math.sin(rad)).toNumber();
            dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(dx, dy, (dotR * 145) / 100);
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(dx, dy, dotR);
        }

        // Speed numeral + unit. Speed sits with its vertical center at ~40%
        // of the gauge height; unit label hugs the speed's right side at a
        // smaller size, baseline-aligned to look anchored rather than free-
        // floating. Matches the Wear OS dial layout 1:1.
        var displaySpeed = Units.convertSpeedFromKmh(speedKmh, speedUnit).toNumber();
        var speedText = displaySpeed.format("%d");

        // Pick a font that fits the inner area. FONT_NUMBER_THAI_HOT is
        // the SDK's largest numeral font (huge) and overflows everywhere;
        // FONT_NUMBER_HOT is the right "speedometer" size on 416-px
        // watches. When the rider has [prioritizePwm] on, drop two font
        // tiers (HOT → MILD) so the speed becomes a small caption and
        // PWM becomes the focal element below — mirrors the Wear OS
        // dial's prioritize-PWM size swap.
        var speedFont = prioritizePwm
            ? Graphics.FONT_NUMBER_MILD
            : Graphics.FONT_NUMBER_HOT;
        var innerW = (arcRadius * 2) - arcThickness * 2;
        var textW = dc.getTextWidthInPixels(speedText, speedFont);
        if (!prioritizePwm && textW > (innerW * 70) / 100) {
            speedFont = Graphics.FONT_NUMBER_MEDIUM;
            textW = dc.getTextWidthInPixels(speedText, speedFont);
        }
        var fontH = Graphics.getFontHeight(speedFont);

        // Speed Y position. On a round watch (dim = h = w) we want it
        // around 33% of view height (matches the Wear OS dial). On a
        // rectangular bike computer (dim = w < h) we anchor to the gauge
        // center instead so the speed tracks the smaller arc rather than
        // floating near the top of the screen.
        var speedY;
        if (w == h) {
            speedY = (h * 33) / 100;
        } else {
            speedY = cy - (arcRadius * 45) / 100;
        }

        // Speed text drawn with top-aligned anchor (CIQ default) plus
        // VCENTER for clean vertical centering. Anchor the right edge at
        // a fixed point so the unit label can sit to its right at a
        // predictable X.
        var speedRightX = cx + (textW / 2);
        dc.setColor(speedColor, Graphics.COLOR_TRANSPARENT);
        dc.drawText(speedRightX, speedY,
                    speedFont, speedText,
                    Graphics.TEXT_JUSTIFY_RIGHT | Graphics.TEXT_JUSTIFY_VCENTER);

        // Unit label sits to the right of the speed numeral, with its
        // baseline aligned to the speed's baseline (~25% below the
        // speed center for FONT_NUMBER_HOT). Reads as a magazine-style
        // suffix: "28 km/h", not a stacked block.
        // Skipped entirely when the rider has [showSpeedUnit] off.
        if (showSpeedUnit) {
            var unitText = Units.speedUnitLabel(speedUnit);
            var unitFont = Graphics.FONT_XTINY;
            dc.setColor(COLOR_DIM, Graphics.COLOR_TRANSPARENT);
            dc.drawText(speedRightX + 4,
                        speedY + (fontH * 18) / 100 - 1,
                        unitFont, unitText,
                        Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    function clamp01(value as Lang.Float, lo as Lang.Float, hi as Lang.Float) as Lang.Float {
        if (value < lo) { return lo; }
        if (value > hi) { return hi; }
        return value;
    }

    //! Normalise a degree value into [0, 360). CIQ's drawArc tolerates
    //! out-of-range angles inconsistently across firmware versions, so
    //! everything fed to it goes through this helper.
    function wrapDeg(deg as Lang.Number) as Lang.Number {
        var d = deg % 360;
        if (d < 0) { d += 360; }
        return d;
    }
}
