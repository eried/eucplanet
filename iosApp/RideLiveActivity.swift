import ActivityKit
import Shared

// ActivityKit side of RideActivity.kt: Kotlin says when and what, this puts
// it on the lock screen and in the Dynamic Island.
final class RideLiveActivity: NSObject, RideActivityBridge {
    private var activity: Any?

    func start(title: String, text: String, actionKeys: [String], actionLabels: [String]) {
        guard #available(iOS 16.2, *) else { return }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        // A Live Activity left over from a previous run would sit next to the new one.
        for old in Activity<RideActivityAttributes>.activities {
            Task { await old.end(nil, dismissalPolicy: .immediate) }
        }
        let state = RideActivityAttributes.ContentState(
            title: title, text: text, actions: buttons(actionKeys, actionLabels))
        activity = try? Activity.request(
            attributes: RideActivityAttributes(),
            content: ActivityContent(state: state, staleDate: nil)
        )
    }

    func update(title: String, text: String, actionKeys: [String], actionLabels: [String]) {
        guard #available(iOS 16.2, *), let a = activity as? Activity<RideActivityAttributes> else { return }
        let state = RideActivityAttributes.ContentState(
            title: title, text: text, actions: buttons(actionKeys, actionLabels))
        Task { await a.update(ActivityContent(state: state, staleDate: nil)) }
    }

    func end() {
        guard #available(iOS 16.2, *), let a = activity as? Activity<RideActivityAttributes> else { return }
        activity = nil
        Task { await a.end(nil, dismissalPolicy: .immediate) }
    }

    private func buttons(_ keys: [String], _ labels: [String]) -> [RideActionButton] {
        zip(keys, labels).map { RideActionButton(key: $0, label: $1) }
    }
}
