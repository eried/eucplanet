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
