using Toybox.WatchUi;
using Toybox.Graphics;
using Toybox.Lang;
using Toybox.System;
using Toybox.Timer;
using Toybox.Math;

//! Main dial. Port of the Wear OS dashboard screen in
//! `wear/.../WatchApp.kt`: speed gauge centered, three battery rings (wheel
//! / phone / watch) along the rim, PWM tile, horn / light pair on the lower
//! half, nav overlay floating over the gauge when the phone popup is active.
//!
//! Renders on the main UI thread inside `onUpdate(dc)`. State changes come
//! in from the PhoneBridge listener which calls `WatchState.update` and
//! then triggers `WatchUi.requestUpdate()` via our registered callback.
class EucPlanetView extends WatchUi.View {

    //! Drives a periodic redraw at ~5 Hz even when no phone message arrives,
    //! so the "stale-frame" detector kicks in: if `now - lastUpdateMs > 3 s`
    //! the dial shows the disconnected placeholder.
    private var _refreshTimer as Timer.Timer? = null;

    //! Debug-only synthetic-data preload timer. Fires once 4 s after onShow
    //! and is a no-op when a real phone frame has already landed.
    private var _demoTimer as Timer.Timer? = null;

    //! Cached bitmap resources. Loaded once in onLayout to avoid hammering
    //! the resource pipeline on every onUpdate (5 Hz redraw + onTick).
    //! Drawn in drawHornLight / drawBatteryRow.
    private var _iconHorn as WatchUi.BitmapResource? = null;
    private var _iconLight as WatchUi.BitmapResource? = null;
    private var _iconWheel as WatchUi.BitmapResource? = null;
    private var _iconPhone as WatchUi.BitmapResource? = null;
    private var _iconWatch as WatchUi.BitmapResource? = null;

    function initialize() {
        View.initialize();
    }

    function onLayout(dc as Graphics.Dc) as Void {
        // Cache bitmap resources so the per-frame redraw doesn't keep
        // re-resolving the resource handle.
        _iconHorn = WatchUi.loadResource(Rez.Drawables.IconHorn);
        _iconLight = WatchUi.loadResource(Rez.Drawables.IconLight);
        _iconWheel = WatchUi.loadResource(Rez.Drawables.IconWheel);
        _iconPhone = WatchUi.loadResource(Rez.Drawables.IconPhone);
        _iconWatch = WatchUi.loadResource(Rez.Drawables.IconWatch);
    }

    function onShow() as Void {
        WatchState.setListener(method(:requestRedraw));
        _refreshTimer = new Timer.Timer();
        _refreshTimer.start(method(:onTick), 1000, true);
        maybeLoadDemo();
    }

    function maybeLoadDemo() as Void {
        // Only fire when the dial is still waiting on the phone. As soon as
        // a real frame lands `phoneSynced` flips true and we leave the demo
        // path alone.
        if (WatchState.snapshot.phoneSynced) { return; }
        var d = {
            "k" => "state",
            "c" => true,
            "n" => "Demo V14",
            "s" => 28.5,
            "b" => 78,
            "b2" => 64,
            "v" => 95.4,
            "i" => 12.3,
            "p" => 42.0,
            "t" => 38.0,
            "tr" => 12.34,
            "tq" => 5.6,
            "l" => false,
            "ms" => 70.0,
            "ch" => true,
            "cl" => true,
            "us" => "kmh",
            "ud" => "km",
            "ut" => "C",
            "ac" => "default",
            "wko" => true,
            "wsb" => true,
            "wpb" => true,
            "wwb" => true,
            "wpd" => "BOTH",
            "wsu" => true,
            "wpp" => false,
            "wrot" => 0,
            "wgb" => true,
            "wgo" => 65,
            "wgr" => 85,
            "s1c" => "NONE",
            "s1h" => "NONE",
            "s2c" => "NONE",
            "s2h" => "NONE",
            "b1c" => "HORN",
            "b1h" => "NONE",
            "b2c" => "LIGHT_TOGGLE",
            "b2h" => "NONE",
            "hap" => false,
            "gs" => -1.0,
            "gsr" => "",
            "na" => false,
            "ng" => 0.0,
            "np" => "",
            "nd" => "",
            "nar" => false,
            "ts" => System.getTimer()
        };
        WatchState.update(d);
    }

    function onHide() as Void {
        WatchState.setListener(null);
        if (_refreshTimer != null) {
            _refreshTimer.stop();
            _refreshTimer = null;
        }
    }

    function onTick() as Void {
        WatchUi.requestUpdate();
    }

    function requestRedraw() as Void {
        WatchUi.requestUpdate();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var s = WatchState.snapshot;
        var now = System.getTimer();
        // 10s tolerance: when the rider backgrounds the phone app, the
        // GarminBridge coroutine often pauses for a few seconds before
        // Android resumes it. A 3s window flagged "Disconnected" while
        // the rider was just glancing at notifications. 10s holds the
        // dial visible long enough for normal background blips.
        var stale = s.lastUpdateMs > 0 && (now - s.lastUpdateMs) > 10000;

        // Only two placeholder branches: no phone yet, or stale (phone
        // went away). When the phone IS publishing but no wheel is
        // connected, render the dial with zeroed telemetry — matches the
        // Wear OS dial, which shows the full layout with "0" speed and
        // dashes for battery / voltage rather than a placeholder text.
        // Horn / light buttons stay drawn but greyed so the rider can
        // still see the layout.
        if (!s.phoneSynced) {
            drawCenterText(dc, WatchUi.loadResource(Rez.Strings.WaitingPhone));
            return;
        }
        if (stale) {
            drawCenterText(dc, WatchUi.loadResource(Rez.Strings.Disconnected));
            return;
        }

        // Main dial. Drawn regardless of wheel-connection state; the
        // wheel telemetry naturally zeros out when disconnected.
        SpeedGauge.draw(dc, s);

        // Order matters: PWM under speed, battery row under PWM, horn/light
        // at the bottom. All in the inner safe area so they don't clip the
        // surrounding arc. Mirrors wear/.../WatchApp.kt layout 1:1.
        drawPwmBadge(dc, s);
        drawBatteryRow(dc, s);
        // Buttons greyed when no wheel is connected — same UX as the Wear OS
        // dial: dial stays visible, controls show but read as inactive.
        drawHornLight(dc, s, /* enabled = */ s.connected);

        if (s.navActive) {
            drawNavOverlay(dc, s);
        }
    }

    //! Center-aligned text with line-break support. CIQ's drawText doesn't
    //! honour `\n`, so split + stack manually. Used for the disconnected /
    //! waiting-for-phone placeholders where copy comes in pre-wrapped from
    //! the shared strings.xml (matches wear/.../strings.xml).
    private function drawCenterText(dc as Graphics.Dc, text as Lang.String) as Void {
        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        var lines = splitLines(text);
        var font = Graphics.FONT_SMALL;
        var lineH = Graphics.getFontHeight(font);
        var totalH = lineH * lines.size();
        var y = cy - (totalH / 2);
        for (var i = 0; i < lines.size(); i += 1) {
            dc.drawText(cx, y + i * lineH, font, lines[i],
                        Graphics.TEXT_JUSTIFY_CENTER);
        }
    }

    private function splitLines(text as Lang.String) as Lang.Array {
        var lines = [];
        var current = "";
        for (var i = 0; i < text.length(); i += 1) {
            var ch = text.substring(i, i + 1);
            if (ch.equals("\n")) {
                lines.add(current);
                current = "";
            } else {
                current = current + ch;
            }
        }
        lines.add(current);
        return lines;
    }

    //! PWM bar at y=53% — a horizontal track + filled portion, with the
    //! percentage to the right. Mirrors the Wear OS dial: bar runs about
    //! 32% of the width, percentage text sits flush-right after a 4-px gap.
    private function drawPwmBadge(dc as Graphics.Dc, s as WatchSnapshot) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var cx = w / 2;
        var y = (h * 50) / 100;
        var pct = s.pwmPercent;
        var color = pct >= 90 ? SpeedGauge.COLOR_DANGER
                  : pct >= 70 ? SpeedGauge.COLOR_WARN
                  : SpeedGauge.COLOR_SAFE;

        var showBar = s.pwmDisplay.equals("BAR") || s.pwmDisplay.equals("BOTH");
        var showNum = s.pwmDisplay.equals("NUMBERS") || s.pwmDisplay.equals("BOTH");

        var barW = (w * 32) / 100;
        var barH = (h * 18) / 1000; // ~1.8% of height
        if (barH < 6) { barH = 6; }

        // Layout: bar on the left side of the inner area, % on the right.
        // Both sit on the same horizontal line.
        var groupW = showBar && showNum ? (barW + (w * 14) / 100) : (showBar ? barW : (w * 14) / 100);
        var groupX = cx - (groupW / 2);

        if (showBar) {
            dc.setColor(SpeedGauge.COLOR_TRACK, Graphics.COLOR_TRANSPARENT);
            dc.fillRoundedRectangle(groupX, y, barW, barH, barH / 2);
            var fillW = (barW * pct.toNumber()) / 100;
            if (fillW > barW) { fillW = barW; }
            if (fillW > 0) {
                dc.setColor(color, Graphics.COLOR_TRANSPARENT);
                dc.fillRoundedRectangle(groupX, y, fillW, barH, barH / 2);
            }
        }
        if (showNum) {
            dc.setColor(color, Graphics.COLOR_TRANSPARENT);
            var textX = showBar ? (groupX + barW + (w * 3) / 100) : cx;
            var justify = showBar ? Graphics.TEXT_JUSTIFY_LEFT : Graphics.TEXT_JUSTIFY_CENTER;
            // Center the % text vertically against the bar.
            var pctFont = Graphics.FONT_XTINY;
            var pctH = Graphics.getFontHeight(pctFont);
            dc.drawText(textX, y + (barH / 2) - (pctH / 2),
                        pctFont, pct.format("%d") + "%", justify);
        }
    }

    //! Three battery readouts in a row, stacked vertically per cell:
    //! icon on top, percentage below. This packs more horizontal room
    //! per cell than the side-by-side layout, so on narrower watches the
    //! values stop crowding the safety band and each other. The block
    //! centers vertically around y=64%.
    private function drawBatteryRow(dc as Graphics.Dc, s as WatchSnapshot) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var fields = [];
        if (s.showWheelBattery) { fields.add({:icon => _iconWheel, :pct => s.batteryPercent}); }
        if (s.showPhoneBattery) { fields.add({:icon => _iconPhone, :pct => s.phoneBatteryPercent}); }
        if (s.showWatchBattery) { fields.add({:icon => _iconWatch, :pct => readWatchBatteryPercent()}); }
        if (fields.size() == 0) { return; }

        var rowW = (w * 55) / 100;
        var rowLeft = (w / 2) - (rowW / 2);
        var step = rowW / fields.size();
        var font = Graphics.FONT_XTINY;
        var iconW = 24;
        var iconH = 24;
        var textH = Graphics.getFontHeight(font);
        var stackGap = 1;
        var blockH = iconH + stackGap + textH;
        // Center the icon+text block vertically around 64% of the view.
        var blockTop = (h * 64) / 100 - (blockH / 2);
        var iconY = blockTop;
        var textY = blockTop + iconH + stackGap;

        for (var i = 0; i < fields.size(); i += 1) {
            var f = fields[i];
            var cx = rowLeft + (step / 2) + (step * i);
            var pctText = f[:pct].format("%d") + "%";
            if (f[:icon] != null) {
                dc.drawBitmap(cx - (iconW / 2), iconY, f[:icon]);
            }
            dc.setColor(batteryColor(f[:pct]), Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, textY, font, pctText, Graphics.TEXT_JUSTIFY_CENTER);
        }
    }

    //! Horn + light buttons drawn as filled circles near the bottom of the
    //! safe area at y=82%, with 24x24 megaphone/flashlight icons centred
    //! inside each circle. Tap targets handled in the Delegate via `onTap`
    //! over the same fractional regions; keep both in sync.
    //!
    //! Button radius is 7% of width so on small MIP watches (Fenix 8 Solar
    //! 240x240, Fenix 6X Pro 280x280) the buttons stay clear of the
    //! battery row above. On larger AMOLED panels the buttons still read
    //! as deliberate touch targets.
    private function drawHornLight(dc as Graphics.Dc, s as WatchSnapshot, enabled as Lang.Boolean) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var btnY = (h * 86) / 100;
        var btnR = (w * 7) / 100;
        var leftX = (w * 36) / 100;
        var rightX = (w * 64) / 100;

        var hornBg = enabled ? 0x29B6F6 : 0x1A1A1A;
        var lightBg = (s.lightOn && enabled) ? 0xFFB400 : (enabled ? 0x444444 : 0x1A1A1A);
        var iconSize = 24;
        var iconOffset = iconSize / 2;

        if (s.hasHorn) {
            dc.setColor(hornBg, Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(leftX, btnY, btnR);
            if (_iconHorn != null) {
                dc.drawBitmap(leftX - iconOffset, btnY - iconOffset, _iconHorn);
            }
        }
        if (s.hasLight) {
            dc.setColor(lightBg, Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(rightX, btnY, btnR);
            if (_iconLight != null) {
                dc.drawBitmap(rightX - iconOffset, btnY - iconOffset, _iconLight);
            }
        }
    }

    //! Navigation arrow + distance line, mirroring the Wear OS nav popup.
    private function drawNavOverlay(dc as Graphics.Dc, s as WatchSnapshot) as Void {
        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;
        var r = (dc.getWidth() * 22) / 100;

        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(cx, cy, r);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawCircle(cx, cy, r);

        if (s.navArrived) {
            dc.drawText(cx, cy, Graphics.FONT_TINY, "Arrived",
                        Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }
        // Arrow as a triangle rotated by s.navAngle (degrees, 0 = up).
        // CIQ doesn't have transform matrices on Dc; draw three points by hand.
        var rad = (s.navAngle - 90) * 3.14159265 / 180.0;
        var ax = cx + (r * 0.55 * Math.cos(rad)).toNumber();
        var ay = cy + (r * 0.55 * Math.sin(rad)).toNumber();
        var bx = cx + (r * 0.4 * Math.cos(rad + 2.4)).toNumber();
        var by = cy + (r * 0.4 * Math.sin(rad + 2.4)).toNumber();
        var ccx = cx + (r * 0.4 * Math.cos(rad - 2.4)).toNumber();
        var ccy = cy + (r * 0.4 * Math.sin(rad - 2.4)).toNumber();
        dc.fillPolygon([[ax, ay], [bx, by], [ccx, ccy]]);

        dc.drawText(cx, cy + r + 4, Graphics.FONT_XTINY, s.navDistance,
                    Graphics.TEXT_JUSTIFY_CENTER);
    }

    //! Watch's own battery percent. CIQ exposes this on System.Stats.
    private function readWatchBatteryPercent() as Lang.Number {
        var stats = System.getSystemStats();
        if (stats has :battery) {
            return stats.battery.toNumber();
        }
        return 0;
    }

    //! Battery-row colour rule. Reds at <=15%, ambers at <=30%, otherwise green.
    private function batteryColor(pct as Lang.Number) as Lang.Number {
        if (pct <= 15) { return SpeedGauge.COLOR_DANGER; }
        if (pct <= 30) { return SpeedGauge.COLOR_WARN; }
        return SpeedGauge.COLOR_SAFE;
    }
}

