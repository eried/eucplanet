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
    //! Heartbeat timer. Fires every 1.5 s and transmits Control.ALIVE so the
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
        // 1.5 s, not the old 5 s: the phone's backlog cap only advances on our
        // echoed seq, so a lazy heartbeat had the dial updating in 5 s clumps.
        // Field-tested as fix-2: no crash, smoother dial. Still backpressured
        // by _txBusy so a stalled transport can't stack frames in the queue.
        _aliveTimer.start(method(:onAliveTick), 1500, /* repeat = */ true);
    }

    function stop() as Void {
        Communications.registerForPhoneAppMessages(null);
        if (_aliveTimer != null) {
            _aliveTimer.stop();
            _aliveTimer = null;
        }
    }

    function onAliveTick() as Void {
        // A rider control stranded by a lost transmit callback outranks the
        // heartbeat for the freed slot.
        if (_pendingControls.size() > 0 && txSlotFree()) {
            var next = _pendingControls[0] as Lang.String;
            _pendingControls = _pendingControls.slice(1, null);
            transmitDict({ Control.PAYLOAD_KEY => next });
            return;
        }
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
            // Echo the seq right now instead of waiting for the heartbeat, so
            // the phone's backlog cap releases as fast as we actually consume
            // frames (fix-4) - but ONLY while the link is proving fast. On a
            // congested link (last completion slower than the heartbeat
            // period) the per-frame ack adds nothing the 1.5 s heartbeat
            // doesn't, and its extra transmits are what slow-leaked the SDK
            // queue toward "transmit queue full" over a long session.
            if (!_txBusy && _lastTxDurationMs < 1500) {
                sendAlive();
            }
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

    //! Input-event report for the phone's Service Mode Wearables tab. A
    //! no-op unless the phone flagged diag recording in the state frames,
    //! so a normal ride sends nothing extra. Best-effort like every other
    //! control: _txBusy may drop one, and that is fine for diagnostics.
    //! Debug lines waiting for a free transmit slot. Dropping them (the old
    //! behavior) made the diag stream lossy in exactly the interesting
    //! moments: a press produces 2-3 events milliseconds apart, one transmit
    //! holds the slot for seconds on a slow link, so only the FIRST line of
    //! every burst survived (field log: onKeyPressed with no onSelect after
    //! it). Buffered lines flush as ONE joined frame when the slot frees.
    private var _pendingDebug as Lang.Array = [];

    function sendDebug(msg as Lang.String) as Void {
        // Println first: on real watches it goes nowhere (unless an app log
        // file exists), but in the CIQ simulator it lands in the console -
        // the only way to verify the input pipeline without a tester, since
        // the tethered simulator's watch->phone transmits stall.
        System.println("dbg " + msg);
        if (!WatchState.snapshot.diagOn) { return; }
        // Never ahead of a rider control, and never a transmit of its own
        // while one is outstanding - buffer and let onTransmitDone flush.
        if (_pendingControls.size() == 0 && txSlotFree()) {
            transmitDict({ Control.PAYLOAD_KEY => Control.DEBUG_PREFIX + msg });
        } else if (_pendingDebug.size() < 8) {
            _pendingDebug = _pendingDebug.add(msg);
        }
    }

    //! Rider controls waiting for the transmit slot. With per-frame acks and
    //! the heartbeat sharing the single in-flight transmit, the slot is busy
    //! more often than not on a slow link - dropping controls there was the
    //! field report "a press 1-2 s after the last one does not register,
    //! waiting three seconds works". Bounded so a dead link cannot pile up
    //! stale horn blasts to fire on reconnect.
    private var _pendingControls as Lang.Array = [];

    //! Send a control intent to the phone. Queued (bounded) rather than
    //! dropped when a transmit is outstanding; the queue drains from
    //! onTransmitDone, ahead of any ack.
    //!
    //! When the phone-side listener never acks (TETHERED simulator is half-
    //! duplex, real BT can also stall), the SDK queues every transmit and
    //! eventually throws "Communications transmit queue full" as an
    //! uncaught System Error that takes down the dial. Catch and drop so a
    //! stuck phone link doesn't kill the watch app.
    function transmitControl(intent as Lang.String) as Void {
        if (!txSlotFree()) {
            if (_pendingControls.size() < 4) {
                _pendingControls = _pendingControls.add(intent);
            }
            return;
        }
        transmitDict({ Control.PAYLOAD_KEY => intent });
    }

    //! How long a transmit may sit without its completion callback before
    //! the busy flag is presumed orphaned. Raised from 4 s: field timing
    //! put normal completions at 2-3 s on a congested link, so 4 s
    //! reclaimed slots whose transmits were merely slow.
    private const TX_ABANDON_MS = 8000;
    //! Duration of the last COMPLETED transmit (ms). The per-frame ack
    //! reads it to stand down while the link is slow.
    private var _lastTxDurationMs as Lang.Number = 0;

    //! True when the transmit slot is available. A stale flag (no callback
    //! within [TX_ABANDON_MS]) is reclaimed here, but the CURRENT send is
    //! still refused: the old behavior of overriding a stale flag sent a
    //! new transmit on top of one the SDK might still be delivering, and
    //! each such stack added an entry the radio never drained - the slow
    //! leak behind "Communications transmit queue full" after 10+ minutes.
    //! Reclaim-without-send gives the queue a full extra tick to drain.
    private function txSlotFree() as Lang.Boolean {
        if (!_txBusy) { return true; }
        if (System.getTimer() - _txBusySinceMs >= TX_ABANDON_MS) {
            _txBusy = false;
            _lastTxDurationMs = TX_ABANDON_MS;
        }
        return false;
    }

    //! Guarded transmit shared by control intents and the ALIVE heartbeat.
    //!
    //! Backpressure: NEVER a second transmit on an outstanding one - a full
    //! CIQ outbound queue throws "Communications transmit queue full" as an
    //! uncaught System Error that takes down the dial (issue #13). Busy =
    //! this frame is dropped (or queued upstream for rider controls); a
    //! stale flag is reclaimed by [txSlotFree] without sending.
    private function transmitDict(payload as Lang.Dictionary) as Void {
        if (!txSlotFree()) { return; }
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
    //! error). Records the completion time for the adaptive ack, then drains
    //! pending rider controls FIRST - before any heartbeat or ack can grab
    //! the freed slot - so a queued tap goes out the moment the link can
    //! carry it.
    function onTransmitDone() as Void {
        _lastTxDurationMs = System.getTimer() - _txBusySinceMs;
        _txBusy = false;
        if (_pendingControls.size() > 0) {
            var next = _pendingControls[0] as Lang.String;
            _pendingControls = _pendingControls.slice(1, null);
            transmitDict({ Control.PAYLOAD_KEY => next });
            return;
        }
        // Buffered debug lines ride out as one frame. Joined so a whole
        // press sequence (pressed / behavior / released) costs one transmit;
        // the phone renders the joined note as-is, timestamps are arrival
        // time - order within the line is the on-watch order.
        if (_pendingDebug.size() > 0) {
            var joined = "";
            for (var i = 0; i < _pendingDebug.size(); i++) {
                if (i > 0) { joined += " | "; }
                joined += _pendingDebug[i] as Lang.String;
            }
            _pendingDebug = [];
            transmitDict({ Control.PAYLOAD_KEY => Control.DEBUG_PREFIX + joined });
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
