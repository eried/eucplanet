import UIKit
import Shared

// UIKit host (no SwiftUI) so we don't hit the SwiftUICore link restriction on
// Xcode 26 when assembling the app outside a normal Xcode target.
@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    // Retained so the shared AvatarPicker hook keeps a live picker to present.
    let avatarPicker = AvatarPhotoPicker()
    // Retained CoreLocation bridge driven by the shared LocationService hooks.
    let locationBridge = LocationBridge()
    // Retained Bonjour bridge driven by the shared HudDiscoveryClient hooks —
    // auto-discovers the HUD on the LAN when the rider leaves the IP blank.
    let hudDiscovery = HudDiscoveryBridge()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let w = UIWindow(frame: UIScreen.main.bounds)
        // Dark window + root background so there's no white flash between the
        // (now dark) launch screen and the first Compose frame. Matches the app's
        // #0D0D0D background + the launch-screen colour.
        let dark = UIColor(red: 13.0/255.0, green: 13.0/255.0, blue: 13.0/255.0, alpha: 1.0)
        w.backgroundColor = dark
        let root = MainViewControllerKt.MainViewController()
        root.view.backgroundColor = dark
        w.rootViewController = root
        w.makeKeyAndVisible()
        window = w
        // Bring up the Apple Watch link (no-op when no watch is paired). Forwards
        // shared telemetry snapshots to the watch + routes its buttons back.
        WatchSessionManager.shared.start()
        // Wire the shared avatar picker to the system PHPicker. The shared
        // callback returns KotlinUnit (nested Kotlin Unit lambdas don't map to Void).
        let pick: (@escaping (String?) -> KotlinUnit) -> Void = { [weak self] onResult in
            self?.avatarPicker.present { b64 in _ = onResult(b64) }
        }
        AvatarPicker.shared.nativeImpl = pick
        // Wire the shared GPS seam to CoreLocation.
        LocationService.shared.nativeStart = { [weak self] in self?.locationBridge.start() }
        LocationService.shared.nativeStop = { [weak self] in self?.locationBridge.stop() }
        // Wire the shared HUD mDNS discovery seam to Bonjour (NetServiceBrowser).
        HudDiscoveryClient.shared.nativeStart = { [weak self] in self?.hudDiscovery.start() }
        HudDiscoveryClient.shared.nativeStop = { [weak self] in self?.hudDiscovery.stop() }
        return true
    }
}
