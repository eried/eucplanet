import Foundation
import Shared
#if canImport(WatchConnectivity)
import WatchConnectivity
#endif

/// Phone side of the EUC Planet ↔ Apple Watch link — the iOS counterpart to
/// Android's `WearBridge`. Forwards each shared `WatchState` snapshot to the
/// paired Apple Watch over WatchConnectivity, and routes the watch's horn /
/// light button intents back into the shared `WatchLink` so the Compose app
/// can act on them.
///
/// Wire keys here MUST match the watch app's decoder (`iosApp/watch/`) and the
/// control strings in `shared/.../watch/WatchProtocol.kt`. Kept as plain string
/// literals on both Swift sides so the watch target needn't link the Kotlin
/// framework.
final class WatchSessionManager: NSObject {
    static let shared = WatchSessionManager()

    // Wire keys — keep in sync with WatchAppModel.decode() on the watch.
    enum Key {
        static let connected = "connected"
        static let name = "name"
        static let speed = "speed"
        static let battery = "batt"
        static let pwm = "pwm"
        static let temp = "temp"
        static let maxSpeed = "max"
        static let light = "light"
        static let hasHorn = "hasHorn"
        static let hasLight = "hasLight"
        static let unitSpeed = "us"
        static let unitTemp = "ut"
        static let accent = "accent"
        static let control = "control"   // matches WatchControl.KEY
        static let showBattery = "sb"
        static let showPwm = "sp"
        static let showUnit = "su"
        static let rotation = "rot"
    }

    // Throttle the deduped application-context push; the wheel publishes ~4–5 Hz.
    private var lastContextSent: TimeInterval = 0

    /// Wire up the link. Safe to call when no watch is paired — it just sets the
    /// forwarding hook and (if supported) activates the session.
    func start() {
        WatchLink.shared.nativeSink = { [weak self] state in
            self?.send(state)
        }
        #if canImport(WatchConnectivity)
        guard WCSession.isSupported() else { return }
        let s = WCSession.default
        s.delegate = self
        s.activate()
        #endif
    }

    private func send(_ state: WatchState) {
        #if canImport(WatchConnectivity)
        guard WCSession.isSupported() else { return }
        let s = WCSession.default
        guard s.activationState == .activated else { return }
        let payload = Self.encode(state)
        // Low-latency live frames when the watch app is foreground/reachable.
        if s.isReachable {
            s.sendMessage(payload, replyHandler: nil, errorHandler: nil)
        }
        // Always keep the latest-state context fresh (the system dedupes + delivers
        // it when the watch app next foregrounds). Throttled to avoid churn.
        let now = Date().timeIntervalSince1970
        if now - lastContextSent > 0.4 {
            lastContextSent = now
            try? s.updateApplicationContext(payload)
        }
        #endif
    }

    static func encode(_ s: WatchState) -> [String: Any] {
        return [
            Key.connected: s.connected,
            Key.name: s.wheelName,
            Key.speed: s.speedKmh,
            Key.battery: Int(s.batteryPercent),
            Key.pwm: s.pwmPercent,
            Key.temp: s.tempC,
            Key.maxSpeed: s.maxSpeedKmh,
            Key.light: s.lightOn,
            Key.hasHorn: s.hasHorn,
            Key.hasLight: s.hasLight,
            Key.unitSpeed: s.unitSpeed,
            Key.unitTemp: s.unitTemp,
            Key.accent: s.accentArgb,
            Key.showBattery: s.showWheelBattery,
            Key.showPwm: s.showPwm,
            Key.showUnit: s.showSpeedUnit,
            Key.rotation: Int(s.dialRotationDeg),
        ]
    }
}

#if canImport(WatchConnectivity)
extension WatchSessionManager: WCSessionDelegate {
    func session(_ session: WCSession,
                 activationDidCompleteWith activationState: WCSessionActivationState,
                 error: Error?) {}
    func sessionDidBecomeInactive(_ session: WCSession) {}
    func sessionDidDeactivate(_ session: WCSession) { session.activate() }

    private func handleControl(_ message: [String: Any]) {
        if let action = message[Key.control] as? String {
            WatchLink.shared.deliverControl(action: action)
        }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        handleControl(message)
    }

    func session(_ session: WCSession,
                 didReceiveMessage message: [String: Any],
                 replyHandler: @escaping ([String: Any]) -> Void) {
        handleControl(message)
        replyHandler([:])
    }
}
#endif
