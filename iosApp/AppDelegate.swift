import UIKit
import Shared

// UIKit host for the shared Compose UI. Everything on screen comes from the
// shared module; this file starts the services (IosAppStart) and
// SceneDelegate creates the window, once a screen is wanted.
@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    private let flic = FlicBridge()
    private let location = LocationBridge()
    private let qrCamera = QrScannerBridge()
    // EUC_HEADLESS: no screen until its timer, whatever the app's state.
    private static var uiHeld = false
    private static var pendingUi: (() -> Void)?

    // Runs show now, or when the EUC_HEADLESS timer ends.
    static func whenUiAllowed(_ show: @escaping () -> Void) {
        if uiHeld { pendingUi = show } else { show() }
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        RideActivity.shared.bridge = RideLiveActivity()
        IosWidgets.shared.bridge = WidgetReloader()
        // The Live Activity's quick-action buttons land here (RideActionIntent),
        // and the home screen widgets' buttons.
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
            AppDelegate.uiHeld = true
            DispatchQueue.main.asyncAfter(deadline: .now() + secs) {
                AppDelegate.uiHeld = false
                AppDelegate.pendingUi?()
                AppDelegate.pendingUi = nil
            }
        }
        return true
    }
}
