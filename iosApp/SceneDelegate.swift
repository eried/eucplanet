import UIKit
import Shared

// The screen. Apps built with the iOS 27 SDK must use scenes (Info.plist
// UIApplicationSceneManifest); without one the app ends at launch. iOS
// connects the scene only when the app comes to the front, never on a
// Bluetooth relaunch in the background, so AppDelegate starts the services
// and this attaches the shared Compose UI to them.
class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }
        // EUC_HEADLESS (AppDelegate): the screen waits for its timer.
        AppDelegate.whenUiAllowed { [weak self] in self?.showUi(in: windowScene) }
        // A cold launch from a link brings the link here, not to openURLContexts.
        handle(connectionOptions.urlContexts)
    }

    // eucplanet:// links (Info.plist CFBundleURLTypes) while the app runs.
    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        handle(URLContexts)
    }

    // The window and the shared Compose UI, once. MainViewController attaches
    // to the services already running.
    private func showUi(in windowScene: UIWindowScene) {
        guard window == nil else { return }
        let w = UIWindow(windowScene: windowScene)
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

    // A tap on a weather widget, a crew pass link, or a share or route link
    // from the site's "Open in EUC Planet".
    private func handle(_ contexts: Set<UIOpenURLContext>) {
        for context in contexts {
            let url = context.url.absoluteString
            _ = IosWeatherWidgets.shared.handleUrl(url: url)
                || IosCrews.shared.handleUrl(url: url)
                || IosLinks.shared.handleUrl(url: url)
        }
    }
}
