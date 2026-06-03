using Toybox.Application;
using Toybox.WatchUi;
using Toybox.Lang;
using Toybox.System;

//! Entry point declared in manifest.xml as `entry="EucPlanetApp"`. Owns the
//! single PhoneBridge instance for the app's lifetime so the listener
//! registration doesn't churn when the View navigates.
class EucPlanetApp extends Application.AppBase {

    public var bridge as PhoneBridge = new PhoneBridge();
    public var actions as ActionDispatch = new ActionDispatch(bridge);

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state as Lang.Dictionary?) as Void {
        bridge.start();
    }

    function onStop(state as Lang.Dictionary?) as Void {
        bridge.stop();
    }

    //! The initial View + Delegate pair shown when the user opens the app.
    //! Return type matches AppBase.getInitialView's signature: an Array of
    //! Views (and optionally one InputDelegate) wrapped in an Array. Monkey C
    //! is permissive about the array shape so long as we don't over-specify.
    function getInitialView() {
        var view = new EucPlanetView();
        var delegate = new EucPlanetDelegate(view, actions);
        return [view, delegate];
    }
}

//! Convenience: read the bridge from anywhere without threading it through
//! constructors. Module-level singletons are the idiomatic CIQ pattern.
function app() as EucPlanetApp {
    return Application.getApp() as EucPlanetApp;
}
