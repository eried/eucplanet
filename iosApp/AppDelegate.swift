import UIKit
import Shared

// UIKit host (no SwiftUI) so we don't hit the SwiftUICore link restriction on
// Xcode 26 when assembling the app outside a normal Xcode target.
@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let w = UIWindow(frame: UIScreen.main.bounds)
        w.rootViewController = MainViewControllerKt.MainViewController()
        w.makeKeyAndVisible()
        window = w
        return true
    }
}
