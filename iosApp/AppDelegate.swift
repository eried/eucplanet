import UIKit
import Shared
import AuthenticationServices

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
    // Retained AVAudioEngine bridge driven by the shared EngineSoundController hooks
    // — synthesizes the motor/engine sound from live telemetry.
    let engineAudio = EngineAudioBridge()
    // Retained camera recorder driven by the shared StudioRecorder hooks — records
    // the camera with the overlay burned in.
    let studioRecorder = StudioCameraRecorder()

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
        // Wire the shared engine-sound seam to AVAudioEngine.
        EngineSoundController.shared.nativeStart = { [weak self] in self?.engineAudio.start() }
        EngineSoundController.shared.nativeStop = { [weak self] in self?.engineAudio.stop() }
        // Wire the shared studio recorder seam to the camera recorder.
        StudioRecorder.shared.nativeStart = { [weak self] in self?.studioRecorder.start() }
        StudioRecorder.shared.nativeStop = { [weak self] in self?.studioRecorder.stop() }

        // Dropbox OAuth: run the consent page in a web-auth session and hand the
        // `?code=` from the db-<appkey>:// redirect back to the shared client.
        DropboxLink.shared.nativeAuthorize = { [weak self] urlStr, scheme, onResult in
            guard let self = self, let authURL = URL(string: urlStr) else { onResult(nil); return }
            let session = ASWebAuthenticationSession(url: authURL, callbackURLScheme: scheme) { callbackURL, _ in
                let code = callbackURL
                    .flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false) }?
                    .queryItems?.first(where: { $0.name == "code" })?.value
                onResult(code)
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            self.dropboxAuthSession = session
            DispatchQueue.main.async { session.start() }
        }
        return true
    }

    /// Retains the in-flight Dropbox auth session (else it deallocates mid-flow).
    private var dropboxAuthSession: ASWebAuthenticationSession?
}

extension AppDelegate: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        return window ?? ASPresentationAnchor()
    }
}
