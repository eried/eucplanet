import AppIntents

// A quick-action button on the ride Live Activity, Android's notification
// actions. Compiled into both the app and the widget extension: the widget
// needs the type to draw Button(intent:), and the system runs perform() in
// the app's process (LiveActivityIntent), where AppDelegate has set the
// handler that hands the press to RideActivity.kt. The extension never runs
// it, so it never needs the Kotlin framework.
@available(iOS 17.0, *)
struct RideActionIntent: LiveActivityIntent {
    // Not a Shortcuts action: the title is never shown to the rider.
    static var title: LocalizedStringResource = "Ride quick action"
    static var isDiscoverable: Bool = false

    // NotificationActionType's key (LOCK, LIGHT, RECORD, ...).
    @Parameter(title: "Action")
    var key: String

    init() {}

    init(key: String) {
        self.key = key
    }

    func perform() async throws -> some IntentResult {
        await RideActionIntentHandler.run?(key)
        return .result()
    }
}

// Set by the app at launch; nil in the widget extension.
enum RideActionIntentHandler {
    static var run: ((String) async -> Void)?
}
