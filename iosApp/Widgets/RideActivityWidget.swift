import ActivityKit
import SwiftUI
import WidgetKit

// The ride Live Activity: the iOS counterpart of Android's ongoing ride
// notification. Lock screen: the wheel's name over speed, battery and
// voltage. Dynamic Island: the same when expanded, the speed when compact.
@main
struct EucPlanetWidgets: WidgetBundle {
    var body: some Widget {
        RideActivityWidget()
    }
}

struct RideActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RideActivityAttributes.self) { context in
            VStack(alignment: .leading, spacing: 4) {
                Text(context.state.title).font(.headline)
                Text(context.state.text).font(.body).monospacedDigit()
                RideActionRow(actions: context.state.actions)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .activityBackgroundTint(Color.black)
            .activitySystemActionForegroundColor(Color.white)
            .foregroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(context.state.title).font(.headline)
                        Text(context.state.text).monospacedDigit()
                        RideActionRow(actions: context.state.actions)
                    }
                }
            } compactLeading: {
                Image(systemName: "bolt.fill")
            } compactTrailing: {
                // The speed alone: the first part of "12.3 km/h | 80% | 100.1 V".
                Text(context.state.text.components(separatedBy: " | ").first ?? "")
                    .lineLimit(1).monospacedDigit()
            } minimal: {
                Image(systemName: "bolt.fill")
            }
        }
    }
}

// The rider's quick actions, the Android notification's buttons, in slot
// order. Button(intent:) needs iOS 17; on 16 the row is left out, as the
// buttons were before.
struct RideActionRow: View {
    let actions: [RideActionButton]

    var body: some View {
        if #available(iOS 17.0, *), !actions.isEmpty {
            HStack(spacing: 8) {
                ForEach(Array(actions.enumerated()), id: \.offset) { _, action in
                    Button(intent: RideActionIntent(key: action.key)) {
                        Label(action.label, systemImage: symbol(action.key))
                            .font(.caption.weight(.semibold))
                            .lineLimit(1)
                            .frame(maxWidth: .infinity)
                    }
                    .tint(.white)
                    .foregroundColor(.black)
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding(.top, 4)
        }
    }

    private func symbol(_ key: String) -> String {
        switch key {
        case "STOP_ALL": return "xmark.circle"
        case "LOCK": return "lock"
        case "LIGHT": return "flashlight.on.fill"
        case "RECORD": return "record.circle"
        case "HORN": return "speaker.wave.2.fill"
        case "DISCONNECT": return "antenna.radiowaves.left.and.right.slash"
        case "STOP_NAV": return "location.slash"
        default: return "circle"
        }
    }
}
