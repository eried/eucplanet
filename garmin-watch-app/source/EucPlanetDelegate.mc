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

    private var _view as EucPlanetView;
    private var _actions as ActionDispatch;

    function initialize(view as EucPlanetView, actions as ActionDispatch) {
        BehaviorDelegate.initialize();
        _view = view;
        _actions = actions;
    }

    function onSelect() as Lang.Boolean {
        var s = WatchState.snapshot;
        _actions.dispatch(s.stem1Click);
        return true;
    }

    function onMenu() as Lang.Boolean {
        var s = WatchState.snapshot;
        _actions.dispatch(s.stem2Click);
        return true;
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
        if (y > (sh * 80) / 100) {
            // Zones follow the touch-slot BINDINGS, not the horn/light
            // capability flags: a slot rebound to Lock (or anything else)
            // keeps its tap target. dispatch() is a no-op for NONE.
            if (x < sw / 2) {
                _actions.dispatch(s.screen1Click);
                return true;
            }
            _actions.dispatch(s.screen2Click);
            return true;
        }
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
        if (y > (sh * 80) / 100) {
            if (x < sw / 2) {
                _actions.dispatch(s.screen1Hold);
                return true;
            } else {
                _actions.dispatch(s.screen2Hold);
                return true;
            }
        }
        // Same reasoning as onTap: never let an unhandled hold fall through
        // to a behavior callback (onMenu on touch watches), which would fire
        // the rider's Button 2 binding from a stray long-press.
        return true;
    }

    function onBack() as Lang.Boolean {
        // Default behavior: pop the view. Returning false lets CIQ handle the
        // exit. If the rider has bound a hold binding to the back button we
        // could route it here, but for now keep parity with the Wear OS dial
        // which uses the back gesture to leave the app.
        return false;
    }
}
