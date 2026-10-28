import ActivityKit

// What the ride Live Activity carries. Compiled into both the app (which
// starts and updates it) and the widget extension (which draws it).
@available(iOS 16.1, *)
struct RideActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        // The wheel's name, as the Android notification's title.
        var title: String
        // Speed, battery and voltage, or the connection state, as its text.
        var text: String
    }
}
