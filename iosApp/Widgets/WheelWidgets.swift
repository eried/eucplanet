import SwiftUI
import WidgetKit

// The home screen widgets: Android's EucWidget (three readings and up to
// three buttons) and its button-only widgets. The app decides everything
// they say (IosWidgets.kt writes "widgets.json" into the App Group
// container, in the rider's language and units, and asks for a redraw);
// this file reads it and lays it out. iOS draws a widget again only now and
// then, so the numbers carry the time they were read.

// What the app wrote. The field names are IosWidgets.encode's.
struct WidgetStore: Codable {
    struct Metric: Codable {
        var value: String
        var caption: String
    }

    struct Action: Codable {
        var key: String
        var label: String
        var enabled: Bool
        // Whether it still works once no wheel answers.
        var enabledIdle: Bool
    }

    // When the numbers were read, seconds since 1970.
    var at: Double
    var connected: Bool
    var status: String
    var statusIdle: String
    var metrics: [Metric]
    var buttons: [Action]
    var standalone: [Action]
    // The gallery's names and descriptions, in the app's language.
    var names: [String: String]

    static let group = "group.com.eried.eucplanet.ios"
    static let file = "widgets.json"
    static let dash = "--"

    // Numbers older than this are not shown as current. The app writes every
    // five minutes while a wheel is connected (IosWidgets.REFRESH_MS).
    static let staleAfter: TimeInterval = 20 * 60

    // Before the app has ever run: the names Android's widget list shows, in
    // English, and nothing to read.
    static let empty = WidgetStore(
        at: 0, connected: false, status: "EUC Planet", statusIdle: "EUC Planet",
        metrics: [], buttons: [], standalone: [], names: [:])

    static func load() -> WidgetStore {
        guard
            let folder = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group),
            let data = try? Data(contentsOf: folder.appendingPathComponent(file)),
            let store = try? JSONDecoder().decode(WidgetStore.self, from: data)
        else { return empty }
        return store
    }

    func name(_ key: String, _ fallback: String) -> String {
        names[key] ?? fallback
    }

    // The same widget once its numbers are too old: the disconnected look.
    var idle: WidgetStore {
        var s = self
        s.connected = false
        s.status = statusIdle
        s.metrics = metrics.map { Metric(value: WidgetStore.dash, caption: $0.caption) }
        s.buttons = buttons.map { Action(key: $0.key, label: $0.label, enabled: $0.enabledIdle, enabledIdle: $0.enabledIdle) }
        s.standalone = standalone.map { Action(key: $0.key, label: $0.label, enabled: $0.enabledIdle, enabledIdle: $0.enabledIdle) }
        return s
    }
}

struct WheelEntry: TimelineEntry {
    let date: Date
    let store: WidgetStore
}

// One entry for what the app last wrote and, while a wheel is connected, a
// second one at the moment those numbers get too old. The app asks for a new
// timeline whenever it has something to say, so this never asks by itself.
struct WheelProvider: TimelineProvider {
    func placeholder(in context: Context) -> WheelEntry {
        WheelEntry(date: Date(), store: WidgetStore.load())
    }

    func getSnapshot(in context: Context, completion: @escaping (WheelEntry) -> Void) {
        completion(entries(Date())[0])
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WheelEntry>) -> Void) {
        completion(Timeline(entries: entries(Date()), policy: .never))
    }

    private func entries(_ now: Date) -> [WheelEntry] {
        let store = WidgetStore.load()
        guard store.connected else { return [WheelEntry(date: now, store: store)] }
        let staleAt = Date(timeIntervalSince1970: store.at + WidgetStore.staleAfter)
        if staleAt <= now { return [WheelEntry(date: now, store: store.idle)] }
        return [WheelEntry(date: now, store: store), WheelEntry(date: staleAt, store: store.idle)]
    }
}

// A widget sits on the home screen, outside the app's theme, so it carries
// its own colours: Android's widget_bg and widget_button_bg.
enum WidgetPalette {
    static let card = Color(red: 0x12 / 255.0, green: 0x17 / 255.0, blue: 0x22 / 255.0)
    static let button = Color.white.opacity(0.12)
    static let text = Color.white
    static let muted = Color.white.opacity(0.6)
    // A button that cannot act, Android's 38%.
    static let disabled = Color.white.opacity(0.38)
}

extension View {
    // The card behind a widget. iOS 17 wants it declared, and adds the margins itself.
    @ViewBuilder
    func widgetCard() -> some View {
        if #available(iOS 17.0, *) {
            self.containerBackground(for: .widget) { WidgetPalette.card }
        } else {
            self.padding(14)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(WidgetPalette.card)
        }
    }
}

// MARK: - The wheel widget

struct WheelWidget: Widget {
    var body: some WidgetConfiguration {
        let store = WidgetStore.load()
        return StaticConfiguration(kind: "EucWheel", provider: WheelProvider()) { entry in
            WheelWidgetView(store: entry.store).widgetCard()
        }
        .configurationDisplayName(store.name("wheel", "Wheel dashboard"))
        .description(store.name("wheelDesc", "Metrics and quick actions"))
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct WheelWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let store: WidgetStore

    // An unconfigured slot collapses rather than leaving a gap.
    private var metrics: [WidgetStore.Metric] {
        store.metrics.filter { !$0.caption.isEmpty }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            WidgetStatusRow(store: store)
            if family == .systemSmall {
                // No room for buttons: the readings, one per line.
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(metrics.enumerated()), id: \.offset) { _, m in
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Text(m.value)
                                .font(.system(size: 22, weight: .bold)).monospacedDigit()
                                .foregroundColor(WidgetPalette.text)
                            Text(m.caption)
                                .font(.system(size: 10))
                                .foregroundColor(WidgetPalette.muted)
                                .lineLimit(1).minimumScaleFactor(0.7)
                        }
                    }
                }
                .frame(maxHeight: .infinity, alignment: .center)
            } else {
                HStack(spacing: 0) {
                    ForEach(Array(metrics.enumerated()), id: \.offset) { _, m in
                        VStack(spacing: 1) {
                            Text(m.value)
                                .font(.system(size: 26, weight: .bold)).monospacedDigit()
                                .foregroundColor(WidgetPalette.text)
                                .lineLimit(1).minimumScaleFactor(0.6)
                            Text(m.caption)
                                .font(.system(size: 10))
                                .foregroundColor(WidgetPalette.muted)
                                .lineLimit(1).minimumScaleFactor(0.7)
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
                .frame(maxHeight: .infinity, alignment: .center)
                WidgetButtonRow(actions: store.buttons, vertical: false, fill: false)
            }
        }
    }
}

// "Connected" or "No wheel", and when the numbers were read.
struct WidgetStatusRow: View {
    let store: WidgetStore

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "bolt.fill").font(.system(size: 10))
            Text(store.status).font(.system(size: 11)).lineLimit(1)
            Spacer(minLength: 4)
            if store.connected {
                Text(Date(timeIntervalSince1970: store.at), style: .time)
                    .font(.system(size: 11)).monospacedDigit()
            }
        }
        .foregroundColor(WidgetPalette.muted)
    }
}

// MARK: - Buttons

// The configured actions, an empty slot left out. A press runs in the app
// (RideActionIntent, into RideActivity.press). Button(intent:) needs iOS 17;
// on 16 a widget cannot be pressed, so the row is left out there.
struct WidgetButtonRow: View {
    let actions: [WidgetStore.Action]
    let vertical: Bool
    // A button widget is nothing but its buttons, so they take the whole card.
    let fill: Bool

    private var shown: [WidgetStore.Action] {
        actions.filter { !$0.label.isEmpty }
    }

    var body: some View {
        if #available(iOS 17.0, *), !shown.isEmpty {
            let buttons = ForEach(Array(shown.enumerated()), id: \.offset) { _, action in
                Button(intent: RideActionIntent(key: action.key)) {
                    WidgetButtonLabel(action: action, fill: fill)
                }
                .buttonStyle(.plain)
                // A disabled button swallows its press; it does not open the app.
                .disabled(!action.enabled)
            }
            if vertical {
                VStack(spacing: 8) { buttons }
            } else {
                HStack(spacing: 8) { buttons }
            }
        }
    }
}

struct WidgetButtonLabel: View {
    let action: WidgetStore.Action
    // A button widget's button fills its cell; the wheel widget's is one row high.
    let fill: Bool

    var body: some View {
        let color = action.enabled ? WidgetPalette.text : WidgetPalette.disabled
        let size: CGFloat = fill ? 15 : 12
        HStack(spacing: 4) {
            Image(systemName: rideActionSymbol(action.key)).font(.system(size: size))
            Text(action.label)
                .font(.system(size: size, weight: .semibold))
                .lineLimit(1).minimumScaleFactor(0.7)
        }
        .foregroundColor(color)
        .padding(.horizontal, 6)
        .frame(maxWidth: .infinity, minHeight: 36, maxHeight: fill ? .infinity : 36)
        .background(WidgetPalette.button)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

// Android ships five button widgets (1, 2, 3, 1 and 2, 2 and 3) because its
// widgets cannot ask which slot to show; the same five here, so a rider
// finds the one they had. Their actions are the standalone slots.
struct ActionWidgetView: View {
    let store: WidgetStore
    let slots: [Int]
    @Environment(\.widgetFamily) private var family

    private var actions: [WidgetStore.Action] {
        slots.compactMap { $0 < store.standalone.count ? store.standalone[$0] : nil }
            .filter { !$0.label.isEmpty }
    }

    var body: some View {
        if actions.isEmpty {
            Text(store.name("unset", "Set a button in Settings"))
                .font(.system(size: 12))
                .multilineTextAlignment(.center)
                .foregroundColor(WidgetPalette.muted)
        } else {
            WidgetButtonRow(actions: actions, vertical: family == .systemSmall, fill: true)
        }
    }
}

private func actionWidget(
    kind: String, slots: [Int], name: String, nameFallback: String, desc: String, descFallback: String
) -> some WidgetConfiguration {
    let store = WidgetStore.load()
    return StaticConfiguration(kind: kind, provider: WheelProvider()) { entry in
        ActionWidgetView(store: entry.store, slots: slots).widgetCard()
    }
    .configurationDisplayName(store.name(name, nameFallback))
    .description(store.name(desc, descFallback))
    .supportedFamilies(slots.count > 1 ? [.systemSmall, .systemMedium] : [.systemSmall])
}

struct ActionWidget1: Widget {
    var body: some WidgetConfiguration {
        actionWidget(
            kind: "EucAction1", slots: [0], name: "act1", nameFallback: "Wheel button 1",
            desc: "actDesc", descFallback: "Set its action in the app")
    }
}

struct ActionWidget2: Widget {
    var body: some WidgetConfiguration {
        actionWidget(
            kind: "EucAction2", slots: [1], name: "act2", nameFallback: "Wheel button 2",
            desc: "actDesc", descFallback: "Set its action in the app")
    }
}

struct ActionWidget3: Widget {
    var body: some WidgetConfiguration {
        actionWidget(
            kind: "EucAction3", slots: [2], name: "act3", nameFallback: "Wheel button 3",
            desc: "actDesc", descFallback: "Set its action in the app")
    }
}

struct ActionWidget12: Widget {
    var body: some WidgetConfiguration {
        actionWidget(
            kind: "EucAction12", slots: [0, 1], name: "act12", nameFallback: "Wheel buttons 1 and 2",
            desc: "actsDesc", descFallback: "Set their actions in the app")
    }
}

struct ActionWidget23: Widget {
    var body: some WidgetConfiguration {
        actionWidget(
            kind: "EucAction23", slots: [1, 2], name: "act23", nameFallback: "Wheel buttons 2 and 3",
            desc: "actsDesc", descFallback: "Set their actions in the app")
    }
}

// The button widgets cannot be pressed before iOS 17, so they are offered
// only from there.
@available(iOS 17.0, *)
struct ActionWidgets: WidgetBundle {
    var body: some Widget {
        ActionWidget1()
        ActionWidget2()
        ActionWidget3()
        ActionWidget12()
        ActionWidget23()
    }
}
