import SwiftUI
import WatchConnectivity

// EUC Planet — Apple Watch riding dial (minimal v1: speed + battery + horn/light).
// The iOS phone app streams a telemetry snapshot over WatchConnectivity; this app
// renders it and sends button intents back. Wire keys mirror
// iosApp/WatchSessionManager.swift and shared/.../watch/WatchProtocol.kt.

/// Decoded telemetry the dial renders. Demo defaults so the face looks alive
/// before the first phone frame arrives (and in standalone simulator runs).
struct WatchTelemetry {
    var connected = true
    var name = "EUC Planet"
    var speedKmh = 23.0
    var battery = 76
    var pwm = 38.0
    var tempC = 41.0
    var maxKmh = 50.0
    var light = false
    var hasHorn = true
    var hasLight = true
    var unitSpeed = "kmh"
    var unitTemp = "C"
    var accent = "#FF36D1C4"
    /// True once a real phone frame has been applied (hides the demo look).
    var fresh = false
}

/// Wire keys — keep in sync with WatchSessionManager.Key on the phone.
private enum K {
    static let connected = "connected", name = "name", speed = "speed", battery = "batt"
    static let pwm = "pwm", temp = "temp", maxSpeed = "max", light = "light"
    static let hasHorn = "hasHorn", hasLight = "hasLight", unitSpeed = "us", unitTemp = "ut"
    static let accent = "accent", control = "control"
}

final class WatchLinkClient: NSObject, ObservableObject, WCSessionDelegate {
    @Published var t = WatchTelemetry()
    /// Wall-clock of the last frame; the UI greys out if the phone goes quiet.
    @Published var lastFrame: Date? = nil

    func start() {
        guard WCSession.isSupported() else { return }
        let s = WCSession.default
        s.delegate = self
        s.activate()
        // Pick up the latest context the phone left while we were closed.
        if !s.receivedApplicationContext.isEmpty { apply(s.receivedApplicationContext) }
    }

    func send(_ control: String) {
        guard WCSession.isSupported() else { return }
        let s = WCSession.default
        if s.isReachable {
            s.sendMessage([K.control: control], replyHandler: nil, errorHandler: nil)
        } else {
            s.transferUserInfo([K.control: control])   // queued, delivered when reachable
        }
    }

    private func apply(_ d: [String: Any]) {
        var n = t
        if let v = d[K.connected] as? Bool { n.connected = v }
        if let v = d[K.name] as? String { n.name = v }
        if let v = d[K.speed] as? Double { n.speedKmh = v } else if let v = d[K.speed] as? Float { n.speedKmh = Double(v) }
        if let v = d[K.battery] as? Int { n.battery = v }
        if let v = d[K.pwm] as? Double { n.pwm = v } else if let v = d[K.pwm] as? Float { n.pwm = Double(v) }
        if let v = d[K.temp] as? Double { n.tempC = v } else if let v = d[K.temp] as? Float { n.tempC = Double(v) }
        if let v = d[K.maxSpeed] as? Double { n.maxKmh = max(10, v) } else if let v = d[K.maxSpeed] as? Float { n.maxKmh = max(10, Double(v)) }
        if let v = d[K.light] as? Bool { n.light = v }
        if let v = d[K.hasHorn] as? Bool { n.hasHorn = v }
        if let v = d[K.hasLight] as? Bool { n.hasLight = v }
        if let v = d[K.unitSpeed] as? String { n.unitSpeed = v }
        if let v = d[K.unitTemp] as? String { n.unitTemp = v }
        if let v = d[K.accent] as? String, !v.isEmpty { n.accent = v }
        n.fresh = true
        DispatchQueue.main.async { self.t = n; self.lastFrame = Date() }
    }

    func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {}
    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) { apply(message) }
    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) { apply(applicationContext) }
}

// MARK: - Units (mirror of the phone's UnitFormat)

private func convSpeed(_ kmh: Double, _ unit: String) -> Double {
    switch unit {
    case "mph": return kmh * 0.621371
    case "ms": return kmh / 3.6
    case "kn": return kmh * 0.539957
    default: return kmh
    }
}
private func speedLabel(_ unit: String) -> String {
    switch unit { case "mph": return "mph"; case "ms": return "m/s"; case "kn": return "kn"; default: return "km/h" }
}

// MARK: - Views

@main
struct EucPlanetWatchApp: App {
    @StateObject private var link = WatchLinkClient()
    var body: some Scene {
        WindowGroup {
            DialView(link: link).onAppear { link.start() }
        }
    }
}

struct DialView: View {
    @ObservedObject var link: WatchLinkClient
    var body: some View {
        let t = link.t
        let accent = Color(argb: t.accent) ?? .teal
        let stale = !t.connected
        let shown = convSpeed(t.speedKmh, t.unitSpeed)
        let frac = min(1.0, max(0.0, t.maxKmh > 0 ? t.speedKmh / t.maxKmh : 0))
        return GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            VStack(spacing: 2) {
                ZStack {
                    SpeedArc(fraction: frac, accent: stale ? .gray : accent)
                        .frame(width: side * 0.92, height: side * 0.92)
                    VStack(spacing: 0) {
                        Text(stale ? "--" : String(Int(shown.rounded())))
                            .font(.system(size: side * 0.34, weight: .bold, design: .rounded))
                            .foregroundColor(stale ? .gray : .white)
                            .monospacedDigit()
                        Text(speedLabel(t.unitSpeed))
                            .font(.system(size: side * 0.10))
                            .foregroundColor(.gray)
                        Text(t.pwm > 0 ? "PWM \(Int(t.pwm.rounded()))%" : " ")
                            .font(.system(size: side * 0.085))
                            .foregroundColor(t.pwm >= 80 ? .red : (t.pwm >= 60 ? .orange : .gray))
                    }
                }
                BatteryRow(pct: t.battery, accent: accent)
                HStack(spacing: 10) {
                    ActionButton(system: "speaker.wave.2.fill", tint: accent, enabled: t.hasHorn) { link.send("horn") }
                    ActionButton(system: t.light ? "lightbulb.fill" : "lightbulb",
                                 tint: t.light ? .yellow : accent, enabled: t.hasLight) { link.send("light_toggle") }
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .ignoresSafeArea()
        .background(Color.black)
    }
}

/// 270° arc gauge, filled clockwise from the lower-left to the lower-right.
struct SpeedArc: View {
    var fraction: Double
    var accent: Color
    var body: some View {
        ZStack {
            Circle().trim(from: 0, to: 0.75)
                .stroke(Color.white.opacity(0.12), style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .rotationEffect(.degrees(135))
            Circle().trim(from: 0, to: 0.75 * fraction)
                .stroke(accent, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .rotationEffect(.degrees(135))
        }
    }
}

struct BatteryRow: View {
    var pct: Int
    var accent: Color
    var body: some View {
        HStack(spacing: 6) {
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.15))
                    Capsule().fill(pct <= 15 ? Color.red : accent)
                        .frame(width: g.size.width * CGFloat(min(100, max(0, pct))) / 100.0)
                }
            }
            .frame(height: 8)
            Text("\(pct)%").font(.system(size: 13, weight: .semibold)).foregroundColor(.white)
        }
        .padding(.horizontal, 6)
    }
}

struct ActionButton: View {
    var system: String
    var tint: Color
    var enabled: Bool
    var action: () -> Void
    var body: some View {
        Button(action: { if enabled { action() } }) {
            Image(systemName: system)
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(enabled ? .black : .gray)
                .frame(width: 44, height: 34)
                .background(enabled ? tint : Color.white.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

extension Color {
    /// Parse "#AARRGGBB" (or "#RRGGBB") as sent by the phone theme.
    init?(argb: String) {
        var s = argb
        if s.hasPrefix("#") { s.removeFirst() }
        guard let v = UInt64(s, radix: 16) else { return nil }
        let a, r, g, b: Double
        if s.count == 8 {
            a = Double((v >> 24) & 0xFF) / 255.0
            r = Double((v >> 16) & 0xFF) / 255.0
            g = Double((v >> 8) & 0xFF) / 255.0
            b = Double(v & 0xFF) / 255.0
        } else if s.count == 6 {
            a = 1.0
            r = Double((v >> 16) & 0xFF) / 255.0
            g = Double((v >> 8) & 0xFF) / 255.0
            b = Double(v & 0xFF) / 255.0
        } else { return nil }
        self = Color(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}
