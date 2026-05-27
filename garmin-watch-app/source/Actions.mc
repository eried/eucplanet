using Toybox.Lang;
using Toybox.Attention;

//! Dispatches the FlicAction-name vocabulary the phone sends in the
//! stem1/stem2/screen1/screen2 binding fields. The watch never executes the
//! action itself — it just forwards the intent over the wire and the phone
//! does the heavy lifting (same pattern as wear/MainActivity.dispatchAction).
//!
//! HORN and LIGHT_TOGGLE use the dedicated control strings so an older phone
//! build that predates the `action:` prefix path keeps working; everything
//! else gets the prefix and goes through FlicManager.dispatchActionByName().
class ActionDispatch {
    private var _bridge as PhoneBridge;

    function initialize(bridge as PhoneBridge) {
        _bridge = bridge;
    }

    //! Send the named action over the wire. `actionName` is a FlicAction
    //! enum name like "HORN" / "LIGHT_TOGGLE" / "LOCK_TOGGLE" / "RECORD_TOGGLE".
    //! "NONE" is a no-op (the user has unbound this slot).
    function dispatch(actionName as Lang.String) as Void {
        if (actionName == null || actionName.equals("NONE") || actionName.equals("")) {
            return;
        }

        var intent;
        if (actionName.equals("HORN")) {
            intent = Control.HORN;
        } else if (actionName.equals("LIGHT_TOGGLE")) {
            // Toggle locally based on cached state so the phone gets the
            // explicit on/off intent matching what `WheelRepository.toggleLight`
            // expects on the receiving side. Same trick the Wear OS dispatcher uses.
            intent = WatchState.snapshot.lightOn ? Control.LIGHT_OFF : Control.LIGHT_ON;
        } else {
            intent = Control.ACTION_PREFIX + actionName;
        }

        _bridge.transmitControl(intent);

        // Optional haptic feedback so the rider feels the button press even
        // when the dial is in ambient mode. Gated on the phone-pushed toggle.
        if (WatchState.snapshot.hapticOnAction && Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(50, 60)]);
        }
    }
}
