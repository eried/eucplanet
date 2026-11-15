import UIKit
import Shared

// UIKit host for the shared Compose UI. Everything on screen comes from the
// shared module; this file starts the services (IosAppStart) and creates the
// window, once a screen is wanted.
@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private let flic = FlicBridge()
    private let location = LocationBridge()
    private let qrCamera = QrScannerBridge()
    // EUC_HEADLESS: no screen until its timer, whatever the app's state.
    private var uiHeld = false

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        RideActivity.shared.bridge = RideLiveActivity()
        // The Live Activity's quick-action buttons land here (RideActionIntent).
        if #available(iOS 17.0, *) {
            RideActionIntentHandler.run = { key in
                await MainActor.run { RideActivity.shared.press(key: key) }
            }
        }
        // Early, so presses queued while the app was asleep arrive quickly.
        IosLocation.shared.sdk = location
        IosFlic.shared.sdk = flic
        IosCrews.shared.camera = qrCamera
        flic.start()
        // The services start here, before any screen: iOS relaunches the app
        // in the background, with no screen, when a wheel (or radar, or GPS
        // box) it was linked to talks after the system ended it for memory
        // (Bluetooth state restoration). The centrals must exist before this
        // returns for iOS to hand their links back.
        // SIMCTL_CHILD_EUC_HEADLESS=20 simulates that relaunch on the
        // simulator: no screen for 20 s, then the screen attaches.
        let headless = ProcessInfo.processInfo.environment["EUC_HEADLESS"].flatMap(Double.init)
        let relaunch = launchOptions?[.bluetoothCentrals] != nil
        IosAppStart.shared.start(bluetoothRelaunch: relaunch || headless != nil)
        if let secs = headless {
            uiHeld = true
            DispatchQueue.main.asyncAfter(deadline: .now() + secs) { [weak self] in
                self?.uiHeld = false
                self?.showUi()
            }
        } else if application.applicationState != .background {
            showUi()
        }
        return true
    }

    // A background launch has no screen until the rider opens the app.
    func applicationWillEnterForeground(_ application: UIApplication) {
        showUi()
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        showUi()
    }

    // The window and the shared Compose UI, once. MainViewController attaches
    // to the services already running.
    private func showUi() {
        guard window == nil, !uiHeld else { return }
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
    }

    // eucplanet://pair links (Info.plist CFBundleURLTypes): the crew pass.
    // Also called after a cold launch from a link.
    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        IosCrews.shared.handleUrl(url: url.absoluteString)
    }
}
