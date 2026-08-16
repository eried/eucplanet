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

    //! True while a Communications.transmit is outstanding. The heartbeat skips
    //! its tick while this is set, so unacked ALIVE frames can't pile up in the
    //! SDK's small outbound queue. A full queue throws "Communications transmit
    //! queue full" — which both crashes the dial (the IQ error icon) AND stalls
    //! the inbound telemetry channel (the frozen-at-0 dial on real devices).
    //! Cleared from the shared TransmitListener when each transmit finishes.
    private var _txBusy as Lang.Boolean = false;
    //! When the current transmit started (System.getTimer ms). If its listener
    //! callback is lost, transmitControl abandons the busy flag after ~4 s so a
    //! stuck flag can't silence the watch->phone link forever.
    private var _txBusySinceMs as Lang.Number = 0;
    private var _txListener as TransmitListener?;

    //! Last STATE sequence number received from the phone. Echoed on every
    //! ALIVE ("alive:<seq>") so the phone can see how far the watch has actually
    //! caught up and stop outrunning it - which is what let Connect Mobile
    //! buffer a minute-long backlog (issue #14).
    private var _lastRxSeq as Lang.Number = 0;

    function initialize() {
        _txListener = new TransmitListener(self);
    }

    //! Wire the listener up. The View calls this in onShow() so the watch
    //! only consumes incoming frames while the dial is on screen — same
    //! lifecycle as the Wear OS `WatchBridgeService` registration.
    function start() as Void {
        Communications.registerForPhoneAppMessages(method(:onMessage));
        sendWatchInfo();
        // Heartbeat. First ack goes out immediately so the phone's Live
        // indicator doesn't wait 5 s for the bridge to come up.
        sendAlive();
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
        // transmitControl itself skips while a prior transmit is outstanding
        // (with a timeout), so the heartbeat can't pile unacked frames into the
        // queue - that overflow is what crashed the dial (issue #13).
        sendAlive();
    }

    //! ALIVE heartbeat carrying the last STATE seq we received in a separate
    //! dict field. An older phone build ignores the extra field and still reads
    //! cmd == "alive", so this stays backward-compatible both directions.
    function sendAlive() as Void {
        transmitDict({ Control.PAYLOAD_KEY => Control.ALIVE, Keys.SEQ => _lastRxSeq });
    }

    function onMessage(msg as Communications.PhoneAppMessage) as Void {
        // A frame that trips an exception (odd type, malformed dict) must never
        // take the dial down - drop it and wait for the next one. This matters
        // most when Connect Mobile delivers a backlog burst after a stall.
        try {
            handleMessage(msg);
        } catch (e) {
        }
    }

    function handleMessage(msg as Communications.PhoneAppMessage) as Void {
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
            // Remember the seq so the next ALIVE tells the phone we got this far.
            var sq = data.get(Keys.SEQ);
            if (sq instanceof Lang.Number) { _lastRxSeq = sq; }
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
        transmitDict({ Control.PAYLOAD_KEY => intent });
    }

    //! Guarded transmit shared by control intents and the ALIVE heartbeat.
    //!
    //! Backpressure: never stack a second transmit on an outstanding one. A
    //! full CIQ outbound queue throws "Communications transmit queue full" as
    //! an uncaught System Error that crashes the dial while the delegate keeps
    //! running - exactly issue #13. Drop the frame if one is in flight
    //! (best-effort, like the Wear bridge), but abandon a genuinely stuck
    //! transmit after ~4 s so a lost callback can't silence the link forever.
    private function transmitDict(payload as Lang.Dictionary) as Void {
        if (_txBusy && (System.getTimer() - _txBusySinceMs) < 4000) { return; }
        _txBusy = true;
        _txBusySinceMs = System.getTimer();
        try {
            Communications.transmit(payload, null, _txListener);
        } catch (e) {
            // Likely a full queue from an ack-less peer; clear the flag so the
            // next tick can retry once the SDK drains.
            _txBusy = false;
        }
    }

    //! Cleared by the shared TransmitListener when a transmit finishes (ok or
    //! error), freeing the heartbeat to send the next one.
    function onTransmitDone() as Void {
        _txBusy = false;
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
    private var _bridge as PhoneBridge;
    function initialize(bridge as PhoneBridge) {
        Communications.ConnectionListener.initialize();
        _bridge = bridge;
    }
    function onComplete() {
        _bridge.onTransmitDone();
    }
    function onError() {
        // Phone gone, Connect Mobile not running, or BT dropped. The phone
        // is the authoritative side; a dropped horn tap is recoverable so
        // we don't surface this — same trade-off as the Wear OS bridge.
        _bridge.onTransmitDone();
    }
}
