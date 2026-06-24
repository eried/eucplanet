using Toybox.Communications;
using Toybox.Lang;
using Toybox.System;
using Toybox.Attention;
using Toybox.Timer;

//! Phone <-> watch transport. Mirrors wear/.../WatchBridgeService.kt + the
//! relevant bits of WatchStateRepository.kt, just sitting on
//! `Communications.transmit` instead of the Wearable Data Layer.
//!
//! Inbound: the phone calls `ConnectIQ.sendMessage(device, app, dict, ...)`
//! and we get one frame here via `onMessage`. Outbound: `transmitControl`
//! wraps a control intent in `{Control.PAYLOAD_KEY: <intent>}` so the phone
//! listener can route it the same way `PhoneGarminListenerService` does.
class PhoneBridge {
    //! Heartbeat timer. Fires every 5 s and transmits Control.ALIVE so the
    //! phone can drive its Live indicator from actual end-to-end delivery
    //! rather than CIQ sendMessage's misleading local-write success.
    private var _aliveTimer as Timer.Timer? = null;

    function initialize() {}

    //! Wire the listener up. The View calls this in onShow() so the watch
    //! only consumes incoming frames while the dial is on screen — same
    //! lifecycle as the Wear OS `WatchBridgeService` registration.
    function start() as Void {
        Communications.registerForPhoneAppMessages(method(:onMessage));
        sendWatchInfo();
        // Heartbeat. First ack goes out immediately so the phone's Live
        // indicator doesn't wait 5 s for the bridge to come up.
        transmitControl(Control.ALIVE);
        _aliveTimer = new Timer.Timer();
        _aliveTimer.start(method(:onAliveTick), 5000, /* repeat = */ true);
    }

    function stop() as Void {
        Communications.registerForPhoneAppMessages(null);
        if (_aliveTimer != null) {
            _aliveTimer.stop();
            _aliveTimer = null;
        }
    }

    function onAliveTick() as Void {
        transmitControl(Control.ALIVE);
    }

    function onMessage(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        if (data == null) { return; }
        // CIQ delivers the phone's payload either as a Dictionary directly
        // (modern Connect Mobile builds) or as a single-element Array wrapping
        // one (older builds). Normalise here so the rest of the code only
        // sees a Dictionary.
        if (data instanceof Lang.Array && data.size() > 0) {
            data = data[0];
        }
        if (!(data instanceof Lang.Dictionary)) { return; }

        var kind = data.get(Keys.KIND);
        if (kind == null || kind.equals(Keys.KIND_STATE)) {
            WatchState.update(data);
        } else if (kind.equals(Keys.KIND_WAKE)) {
            // The phone fires this whenever its app comes to foreground. The
            // phone separately calls ConnectIQ.openApplication() to actually
            // launch this app when it was closed; this message only nudges an
            // already-open app, so we just bump the snapshot's last-update to
            // clear the disconnected placeholder if it was up.
            WatchState.snapshot.phoneSynced = true;
        } else if (kind.equals(Keys.KIND_QUIT)) {
            // User picked "Stop all" on the phone with "close watch on exit"
            // toggled on. CIQ apps can self-exit; do that here so the dial
            // doesn't sit on a stale frame.
            System.exit();
        } else if (kind.equals(Keys.KIND_VIBRATE)) {
            var ms = data.get(Keys.VIBRATE_MS);
            if (ms instanceof Lang.Number && Attention has :vibrate) {
                vibrate(ms);
            }
        }
    }

    //! Send a control intent to the phone. Best-effort, no ack. Matches the
    //! semantics of `WatchStateRepository.sendControl` on the Wear OS side.
    //!
    //! When the phone-side listener never acks (TETHERED simulator is half-
    //! duplex, real BT can also stall), the SDK queues every transmit and
    //! eventually throws "Communications transmit queue full" as an
    //! uncaught System Error that takes down the dial. Catch and drop so a
    //! stuck phone link doesn't kill the watch app.
    function transmitControl(intent as Lang.String) as Void {
        var payload = { Control.PAYLOAD_KEY => intent };
        try {
            Communications.transmit(payload, null, new TransmitListener());
        } catch (e) {
            // Likely a full queue from an ack-less peer; the next tick will
            // try again once the SDK drains. No phone == no recovery action.
        }
    }

    //! Tell the phone about the watch's identity on launch. Mirrors
    //! `WatchStateRepository.sendWatchInfo` so the phone's Service Mode log
    //! captures both sides of the pair.
    function sendWatchInfo() as Void {
        var stats = System.getDeviceSettings();
        var info = Control.WATCH_INFO_PREFIX +
            "model=" + stats.partNumber +
            "|fw=" + stats.firmwareVersion[0] + "." + stats.firmwareVersion[1] +
            "|lang=" + stats.systemLanguage;
        transmitControl(info);
    }

    function vibrate(ms as Lang.Number) as Void {
        var pulse = [new Attention.VibeProfile(50, ms)];
        Attention.vibrate(pulse);
    }
}

class TransmitListener extends Communications.ConnectionListener {
    function initialize() {
        Communications.ConnectionListener.initialize();
    }
    function onComplete() {}
    function onError() {
        // Phone gone, Connect Mobile not running, or BT dropped. The phone
        // is the authoritative side; a dropped horn tap is recoverable so
        // we don't surface this — same trade-off as the Wear OS bridge.
    }
}
