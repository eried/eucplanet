import SwiftUI
import WidgetKit

// The weather widgets: Android's "Good to ride?" (a face and the score) and
// "Riding weather" (the verdict and the curve for the rider's window). The
// app scores the forecast and writes it, hour by hour, into "weather.json"
// in the App Group container (IosWeatherWidgets.kt); this file lays it out.
//
// What iOS does differently from Android: no hourly worker repaints these.
// The file carries the hours ahead, so the widget moves along them by
// itself, one timeline entry per forecast step, and shows the hour that is
// now even when the app has not run since the morning.

// What the app wrote. The field names are IosWeatherWidgets.encode's.
struct WeatherStore: Codable {
    struct Point: Codable {
        // The moment this step is the forecast for, seconds since 1970.
        var t: Double
        var score: Double
        // The score as the app writes it: "+4", "0", "-3".
        var label: String
        // WeatherFace's key, looked up in `faces`.
        var face: String
        // In the rider's units, as the app formats them.
        var temp: String
        var wind: String
    }

    struct Face: Codable {
        var emoji: String
        var text: String
    }

    // When the forecast was fetched, seconds since 1970.
    var fetchedAt: Double
    var points: [Point]
    var faces: [String: Face]
    // How far ahead the curve looks, seconds: the rider's window.
    var window: Double
    // From this age, seconds, the small widget says how old the forecast is.
    var stale: Double
    var windowLabel: String
    var place: String
    // "Now", under the curve's left end.
    var now: String
    // "%1$d h ago".
    var hourAgo: String

    static let file = "weather.json"

    // Timeline entries per request; iOS asks again at the end.
    static let maxEntries = 48

    static func load() -> WeatherStore? {
        guard
            let folder = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: WidgetStore.group),
            let data = try? Data(contentsOf: folder.appendingPathComponent(file)),
            let store = try? JSONDecoder().decode(WeatherStore.self, from: data),
            !store.points.isEmpty
        else { return nil }
        return store
    }

    // How long one step lasts: the provider's hours, or its quarter hours.
    var step: TimeInterval {
        points.count >= 2 ? max(60, points[1].t - points[0].t) : 3600
    }

    // From when step `i` is the nearest one, which is the one the panel
    // calls now: half a step before its own time.
    func starts(_ i: Int) -> TimeInterval {
        points[i].t - step / 2
    }

    // When the last step is over and there is nothing left to show.
    var end: TimeInterval {
        (points.last?.t ?? 0) + step / 2
    }
}

struct WeatherEntry: TimelineEntry {
    let date: Date
    // nil before the first forecast, and once the hours ran out.
    let store: WeatherStore?
    // The step that is now.
    let index: Int
    // The texts for a widget with nothing to draw, and the gallery names.
    let names: WidgetStore

    var point: WeatherStore.Point? {
        guard let store, store.points.indices.contains(index) else { return nil }
        return store.points[index]
    }

    // From now to the end of the rider's window.
    var curve: [WeatherStore.Point] {
        guard let store, let point else { return [] }
        return Array(store.points[index...].prefix { $0.t <= point.t + store.window })
    }

    var face: WeatherStore.Face? {
        guard let store, let point else { return nil }
        return store.faces[point.face]
    }
}

// One entry per forecast step from now on, each showing its own hour. When
// the steps run out the last entry says there is no forecast; the app asks
// for a new timeline whenever it writes one.
struct WeatherProvider: TimelineProvider {
    func placeholder(in context: Context) -> WeatherEntry {
        timeline(Date()).entries[0]
    }

    func getSnapshot(in context: Context, completion: @escaping (WeatherEntry) -> Void) {
        completion(timeline(Date()).entries[0])
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WeatherEntry>) -> Void) {
        completion(timeline(Date()))
    }

    private func timeline(_ now: Date) -> Timeline<WeatherEntry> {
        let names = WidgetStore.load()
        let none = WeatherEntry(date: now, store: nil, index: 0, names: names)
        guard let store = WeatherStore.load(), now.timeIntervalSince1970 < store.end else {
            return Timeline(entries: [none], policy: .never)
        }
        // The step nearest to now, or the first one for a forecast that starts later.
        let first = store.points.indices.last { store.starts($0) <= now.timeIntervalSince1970 } ?? 0
        let last = min(store.points.count, first + WeatherStore.maxEntries)
        var entries = (first..<last).map { i in
            WeatherEntry(
                date: i == first ? now : Date(timeIntervalSince1970: store.starts(i)),
                store: store, index: i, names: names)
        }
        guard last == store.points.count else { return Timeline(entries: entries, policy: .atEnd) }
        entries.append(WeatherEntry(date: Date(timeIntervalSince1970: store.end), store: nil, index: 0, names: names))
        return Timeline(entries: entries, policy: .never)
    }
}

// Android's WeatherGraph colours: the panel's red-to-green ramp and its ink.
enum WeatherPalette {
    static let ink = Color(red: 0x9A / 255.0, green: 0xA0 / 255.0, blue: 0xA6 / 255.0)
    static let message = Color(red: 0xE8 / 255.0, green: 0xEA / 255.0, blue: 0xED / 255.0)

    private static let bad: [Double] = [0xE0, 0x5A, 0x47]
    private static let good: [Double] = [0x5A, 0xB5, 0x5A]

    // -5 is all bad, +5 all good.
    static func color(_ score: Double) -> Color {
        let t = min(max((score + 5) / 10, 0), 1)
        let c = (0..<3).map { (bad[$0] + (good[$0] - bad[$0]) * t) / 255.0 }
        return Color(red: c[0], green: c[1], blue: c[2])
    }
}

// A tap opens the app on its weather panel (AppDelegate, eucplanet://weather).
let weatherPanelUrl = URL(string: "eucplanet://weather")!

// MARK: - Good to ride?

struct WeatherScoreWidget: Widget {
    var body: some WidgetConfiguration {
        let names = WidgetStore.load()
        return StaticConfiguration(kind: "EucWeatherScore", provider: WeatherProvider()) { entry in
            WeatherScoreView(entry: entry).widgetCard().widgetURL(weatherPanelUrl)
        }
        .configurationDisplayName(names.name("weatherScore", "Good to ride?"))
        .description(names.name("weatherScoreDesc", "The weather for riding, right now."))
        .supportedFamilies([.systemSmall])
    }
}

struct WeatherScoreView: View {
    let entry: WeatherEntry

    // The temperature while the forecast is recent, its age once it is not:
    // a stale "+4" is worse than no answer.
    private var line: String {
        guard let store = entry.store, let point = entry.point else {
            return entry.names.name("weatherNeverShort", "Never")
        }
        let age = entry.date.timeIntervalSince1970 - store.fetchedAt
        if age >= store.stale {
            return store.hourAgo.replacingOccurrences(of: "%1$d", with: "\(Int(age / 3600))")
        }
        return point.temp
    }

    var body: some View {
        VStack(spacing: 4) {
            Text(entry.names.name("weatherQuestion", "Ride?"))
                .font(.system(size: 12))
                .foregroundColor(WeatherPalette.ink)
            HStack(spacing: 6) {
                Text(entry.face?.emoji ?? "🛰").font(.system(size: 34))
                Text(entry.point?.label ?? WidgetStore.dash)
                    .font(.system(size: 38, weight: .bold)).monospacedDigit()
                    .foregroundColor(entry.point.map { WeatherPalette.color($0.score) } ?? WeatherPalette.ink)
                    .lineLimit(1).minimumScaleFactor(0.6)
            }
            Text(line)
                .font(.system(size: 13))
                .foregroundColor(WeatherPalette.ink)
                .lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Riding weather

struct WeatherForecastWidget: Widget {
    var body: some WidgetConfiguration {
        let names = WidgetStore.load()
        return StaticConfiguration(kind: "EucWeatherForecast", provider: WeatherProvider()) { entry in
            WeatherForecastView(entry: entry).widgetCard().widgetURL(weatherPanelUrl)
        }
        .configurationDisplayName(names.name("weatherForecast", "Riding weather"))
        .description(names.name(
            "weatherForecastDesc", "How good the weather is for riding, over the forecast window"))
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

struct WeatherForecastView: View {
    @Environment(\.widgetFamily) private var family
    let entry: WeatherEntry

    // The large size has the room Android's tall widget has: the hours under
    // the curve, its scale, and the temperature and wind.
    private var detail: Bool { family == .systemLarge }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(entry.face?.emoji ?? "🛰").font(.system(size: 26))
                Text(entry.point?.label ?? WidgetStore.dash)
                    .font(.system(size: 26, weight: .bold)).monospacedDigit()
                    .foregroundColor(entry.point.map { WeatherPalette.color($0.score) } ?? WeatherPalette.ink)
                VStack(alignment: .leading, spacing: 1) {
                    // The verdict in words, which is what the panel leads with.
                    Text(entry.face?.text
                        ?? entry.names.name("weatherNever", "No forecast yet. Open the app once, or tap refresh."))
                        .font(.system(size: 13))
                        .foregroundColor(WeatherPalette.message)
                        .lineLimit(2).minimumScaleFactor(0.8)
                    if detail, let store = entry.store, let point = entry.point {
                        Text("\(point.temp)   \(point.wind)   \(store.windowLabel)")
                            .font(.system(size: 12)).monospacedDigit()
                            .foregroundColor(WeatherPalette.ink)
                            .lineLimit(1).minimumScaleFactor(0.7)
                    }
                }
                Spacer(minLength: 0)
            }
            if let store = entry.store, entry.curve.count >= 2 {
                WeatherGraph(points: entry.curve, withHours: detail, nowLabel: store.now)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Spacer(minLength: 0)
            }
            HStack(spacing: 6) {
                if let store = entry.store, !store.place.isEmpty {
                    Text(store.place).lineLimit(1)
                }
                WeatherStamp(entry: entry)
                Spacer(minLength: 4)
                WeatherRefreshButton()
            }
            .font(.system(size: 11))
            .foregroundColor(WeatherPalette.ink)
        }
    }
}

// When the forecast was fetched: its clock time, with the day once it is
// not today's. A widget is not redrawn every minute, so this does not count
// minutes as Android's does.
struct WeatherStamp: View {
    let entry: WeatherEntry

    var body: some View {
        if let store = entry.store {
            let fetched = Date(timeIntervalSince1970: store.fetchedAt)
            if Calendar.current.isDate(fetched, inSameDayAs: entry.date) {
                Text(fetched, style: .time).monospacedDigit()
            } else {
                Text(fetched, format: .dateTime.day().month().hour().minute()).monospacedDigit()
            }
        } else {
            Text(entry.names.name("weatherNeverShort", "Never"))
        }
    }
}

// Fetches the forecast again, in the app (RideActionIntent, into
// IosWeatherWidgets.refresh). Buttons need iOS 17; before that the app
// refreshes when it is opened.
struct WeatherRefreshButton: View {
    var body: some View {
        if #available(iOS 17.0, *) {
            Button(intent: RideActionIntent(key: "WEATHER_REFRESH")) {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(WeatherPalette.ink)
                    .frame(width: 26, height: 22)
            }
            .buttonStyle(.plain)
        }
    }
}

// The forecast curve: Android's WeatherGraph, drawn here instead of shipped
// as a bitmap. The band is fixed at -5..+5 rather than fitted to the window,
// so a mediocre afternoon does not look like a great one. The line and the
// labels keep one size: the large widget has more curve, not a thicker one.
struct WeatherGraph: View {
    let points: [WeatherStore.Point]
    // The clock labels along the bottom and the score marks down the left.
    let withHours: Bool
    let nowLabel: String

    var body: some View {
        Canvas { ctx, size in
            let n = points.count
            guard n >= 2 else { return }
            let w = size.width
            let h = size.height
            let labelH: CGFloat = withHours ? 18 : 0
            let plotH = h - labelH
            func y(_ v: Double) -> CGFloat {
                plotH - (min(max(v, -5), 5) + 5) / 10 * plotH * 0.88 - plotH * 0.06
            }
            // A column for the score marks, or "+5" sits under the curve
            // exactly when the afternoon is good.
            let gutter: CGFloat = withHours ? 22 : 0
            func x(_ i: Int) -> CGFloat {
                gutter + (w - gutter) * CGFloat(i) / CGFloat(n - 1)
            }
            let ink = WeatherPalette.ink

            // Where the hour labels point, behind the curve.
            let ticks = withHours ? min(4, n) : 0
            let tickAt = (0..<ticks).map { (n - 1) * $0 / max(1, ticks - 1) }
            for i in tickAt {
                var line = Path()
                line.move(to: CGPoint(x: x(i), y: 0))
                line.addLine(to: CGPoint(x: x(i), y: plotH))
                ctx.stroke(line, with: .color(ink.opacity(0.16)), lineWidth: 1)
            }

            // The neutral line: which side of "fine" the curve is on.
            var zero = Path()
            zero.move(to: CGPoint(x: gutter, y: y(0)))
            zero.addLine(to: CGPoint(x: w, y: y(0)))
            ctx.stroke(zero, with: .color(ink.opacity(0.24)), lineWidth: 1)

            if withHours {
                let font = Font.system(size: 10)
                let marks: [(String, Double, UnitPoint)] = [("+5", 5, .topLeading), ("0", 0, .leading), ("-5", -5, .bottomLeading)]
                for (text, v, anchor) in marks {
                    ctx.draw(
                        Text(text).font(font).foregroundColor(ink.opacity(0.67)),
                        at: CGPoint(x: 0, y: y(v)), anchor: anchor)
                }
            }

            var curve = Path()
            curve.move(to: CGPoint(x: x(0), y: y(points[0].score)))
            for i in 1..<n { curve.addLine(to: CGPoint(x: x(i), y: y(points[i].score))) }

            // The fill under the curve, tinted by the score at each end.
            var fill = curve
            fill.addLine(to: CGPoint(x: x(n - 1), y: plotH))
            fill.addLine(to: CGPoint(x: x(0), y: plotH))
            fill.closeSubpath()
            ctx.fill(
                fill,
                with: .linearGradient(
                    Gradient(colors: [
                        WeatherPalette.color(points[0].score).opacity(0.24),
                        WeatherPalette.color(points[n - 1].score).opacity(0.24),
                    ]),
                    startPoint: CGPoint(x: gutter, y: 0), endPoint: CGPoint(x: w, y: 0)))

            // The curve, each segment in its own score's colour.
            let stroke = StrokeStyle(lineWidth: 2.5, lineCap: .round)
            for i in 1..<n {
                var seg = Path()
                seg.move(to: CGPoint(x: x(i - 1), y: y(points[i - 1].score)))
                seg.addLine(to: CGPoint(x: x(i), y: y(points[i].score)))
                ctx.stroke(
                    seg, with: .color(WeatherPalette.color((points[i - 1].score + points[i].score) / 2)),
                    style: stroke)
            }

            // Now: the left end, marked so the curve reads as a future.
            let r: CGFloat = 4
            ctx.fill(
                Path(ellipseIn: CGRect(x: x(0) - r, y: y(points[0].score) - r, width: 2 * r, height: 2 * r)),
                with: .color(WeatherPalette.color(points[0].score)))

            if withHours {
                let hours = DateFormatter()
                hours.dateFormat = "HH"
                let size: CGFloat = 11
                for (t, i) in tickAt.enumerated() {
                    // The left end is where the rider stands, so it says so.
                    let label = t == 0
                        ? Text(nowLabel).font(.system(size: size, weight: .bold))
                        : Text(hours.string(from: Date(timeIntervalSince1970: points[i].t))).font(.system(size: size))
                    let resolved = ctx.resolve(label.foregroundColor(ink))
                    let half = resolved.measure(in: size2(w, labelH)).width / 2
                    ctx.draw(
                        resolved,
                        at: CGPoint(x: min(max(x(i), half), w - half), y: h - labelH * 0.5), anchor: .center)
                }
            }
        }
    }

    private func size2(_ w: CGFloat, _ h: CGFloat) -> CGSize {
        CGSize(width: w, height: max(h, 1))
    }
}
