import UIKit
import Shared

// UIKit host for the shared Compose UI. Everything on screen comes from the
// shared module; this file only creates the window.
@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private let flic = FlicBridge()
    private let location = LocationBridge()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        RideActivity.shared.bridge = RideLiveActivity()
        // Early, so presses queued while the app was asleep arrive quickly.
        IosLocation.shared.sdk = location
        IosFlic.shared.sdk = flic
        flic.start()
        let w = UIWindow(frame: UIScreen.main.bounds)
        // Same as the launch screen colour, so there is no flash before the
        // first Compose frame.
        let dark = UIColor(red: 13.0 / 255.0, green: 13.0 / 255.0, blue: 13.0 / 255.0, alpha: 1.0)
        w.backgroundColor = dark
        let root = MainViewControllerKt.MainViewController()
        root.view.backgroundColor = dark
        w.rootViewController = root
        w.makeKeyAndVisible()
        window = w
        return true
    }
}
