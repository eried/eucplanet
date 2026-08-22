using Toybox.WatchUi;
using Toybox.Lang;
using Toybox.System;

//! Routes user input to ActionDispatch. Mirrors
//! `wear/.../MainActivity.kt::onKeyDown / onKeyUp / onKeyLongPress`.
//!
//! CIQ buttons are normalised through `BehaviorDelegate`'s named callbacks:
//!   onSelect  -> primary action button (start/stop on Fenix, lap on Edge)
//!   onMenu    -> menu / up button
//!   onBack    -> back button (intercepted so the app stays foregrounded)
//!   onKey     -> raw fallback for the light key (LIGHT) and the wheel
//!                rotation on Venu/Vivoactive
//!
//! The Wear OS app exposes stem1/stem2 bindings on the rider's two
//! programmable buttons. Here we treat:
//!   - onSelect -> stem1
//!   - onMenu   -> stem2
//! so the binding model stays identical and the phone Settings UI doesn't
//! need a separate "Garmin" section.
class EucPlanetDelegate extends WatchUi.BehaviorDelegate {

    //! How fresh an onKey must be for a behavior callback to count as
    //! physical. A real press delivers onKey and the behavior in the same
    //! input cycle; 1 s is orders of magnitude above that while never
    //! bridging two separate interactions.
    private const KEY_FRESH_MS = 1000;

    private var _view as EucPlanetView;
    private var _actions as ActionDispatch;
    //! System.getTimer() of the last raw key event. Behaviors arriving
    //! without a fresh key are tap-synthesized, not button presses.
    private var _lastKeyMs as Lang.Number = -100000;

    function initialize(view as EucPlanetView, actions as ActionDispatch) {
        BehaviorDelegate.initialize();
        _view = view;
        _actions = actions;
    }

    //! True when a raw key event arrived within the same input cycle.
    //! Field evidence (Fenix 8, 2026-08-22 diag): screen taps arrive
    //! DIRECTLY as onSelect - never as onTap, onHold or onKey - so the
    //! only way to tell "rider pressed START" from "rider brushed the
    //! screen" is whether a real key event preceded the behavior.
    //!
    //! Non-touch watches (Instinct 2) skip the gate entirely: with no
    //! screen to synthesize from, every behavior IS a physical press, and
    //! gating there could only break buttons if a firmware skips onKey.
    private function keyWasPhysical() as Lang.Boolean {
        var settings = System.getDeviceSettings();
        if (!settings.isTouchScreen) { return true; }
        return (System.getTimer() - _lastKeyMs) < KEY_FRESH_MS;
    }

    function onSelect() as Lang.Boolean {
        var s = WatchState.snapshot;
        if (!keyWasPhysical()) {
            // Tap promoted to the select behavior. Swallow it: the rider's
            // Button 1 binding must only fire from the physical button.
            _actions.debug("onSelect ignored (tap-synthesized, no key)");
            return true;
        }
        _actions.debug("onSelect act=" + s.stem1Click);
        _actions.dispatch(s.stem1Click);
        return true;
    }

    function onMenu() as Lang.Boolean {
        var s = WatchState.snapshot;
        if (!keyWasPhysical()) {
            _actions.debug("onMenu ignored (tap-synthesized, no key)");
            return true;
        }
        _actions.debug("onMenu act=" + s.stem2Click);
        _actions.dispatch(s.stem2Click);
        return true;
    }

    //! Raw key events. Every PHYSICAL press passes through here before the
    //! system maps it to a behavior (onSelect / onMenu); tap-synthesized
    //! behaviors do not. Stamp the time, log while diag records, and let
    //! the default routing continue.
    function onKey(evt as WatchUi.KeyEvent) as Lang.Boolean {
        _lastKeyMs = System.getTimer();
        _actions.debug("onKey k=" + evt.getKey() + " type=" + evt.getType());
        return false;
    }

    function onTap(evt as WatchUi.ClickEvent) as Lang.Boolean {
        // Hit-test the horn + light buttons drawn in the lower half of the
        // dial. The buttons are placed at the same fractional coordinates as
        // the drawHornLight code, keep both in sync if either changes.
        var coords = evt.getCoordinates();
        var x = coords[0];
        var y = coords[1];
        var s = WatchState.snapshot;
        var settings = System.getDeviceSettings();
        var sw = settings.screenWidth;
        var sh = settings.screenHeight;
        // Band matches the drawn buttons (centre 84% height, radius 10%)
        // with headroom above so a hurried glove-tap still lands.
        if (y > (sh * 72) / 100) {
            // Zones follow the touch-slot BINDINGS, not the horn/light
            // capability flags: a slot rebound to Lock (or anything else)
            // keeps its tap target. dispatch() is a no-op for NONE.
            if (x < sw / 2) {
                _actions.debug("tap x=" + x + " y=" + y + " slot=1 act=" + s.screen1Click);
                _view.notifyTouch(1);
                _actions.dispatch(s.screen1Click);
                return true;
            }
            _actions.debug("tap x=" + x + " y=" + y + " slot=2 act=" + s.screen2Click);
            _view.notifyTouch(2);
            _actions.dispatch(s.screen2Click);
            return true;
        }
        _actions.debug("tap x=" + x + " y=" + y + " slot=none (consumed)");
        // Consume the tap even when it hits nothing. Returning false here
        // lets BehaviorDelegate promote an unhandled tap to onSelect - the
        // same callback as the physical start button - so ANY screen touch
        // fired the rider's Button 1 action (Fenix 8 field report: had to
        // disable the touchscreen to stop mode changes from stray taps).
        return true;
    }

    function onHold(evt as WatchUi.ClickEvent) as Lang.Boolean {
        var coords = evt.getCoordinates();
        var x = coords[0];
        var y = coords[1];
        var s = WatchState.snapshot;
        var settings = System.getDeviceSettings();
        var sw = settings.screenWidth;
        var sh = settings.screenHeight;
        if (y > (sh * 72) / 100) {
            if (x < sw / 2) {
                _actions.debug("hold x=" + x + " y=" + y + " slot=1 act=" + s.screen1Hold);
                _view.notifyTouch(1);
                _actions.dispatch(s.screen1Hold);
                return true;
            } else {
                _actions.debug("hold x=" + x + " y=" + y + " slot=2 act=" + s.screen2Hold);
                _view.notifyTouch(2);
                _actions.dispatch(s.screen2Hold);
                return true;
            }
        }
        // Same reasoning as onTap: never let an unhandled hold fall through
        // to a behavior callback (onMenu on touch watches), which would fire
        // the rider's Button 2 binding from a stray long-press.
        _actions.debug("hold x=" + x + " y=" + y + " slot=none (consumed)");
        return true;
    }

    function onBack() as Lang.Boolean {
        _actions.debug("onBack");
        // Default behavior: pop the view. Returning false lets CIQ handle the
        // exit. If the rider has bound a hold binding to the back button we
        // could route it here, but for now keep parity with the Wear OS dial
        // which uses the back gesture to leave the app.
        return false;
    }
}
