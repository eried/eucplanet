import UIKit
import PhotosUI

/// PHPicker-backed avatar photo picker, bridged into the shared Kotlin
/// `AvatarPicker.nativeImpl` from `AppDelegate`. Presents the system photo
/// picker, then hands back a 256×256 PNG as base64 (or nil if cancelled).
final class AvatarPhotoPicker: NSObject, PHPickerViewControllerDelegate {
    private var completion: ((String?) -> Void)?

    /// Present the picker. Safe to call repeatedly; the latest completion wins.
    func present(_ onResult: @escaping (String?) -> Void) {
        guard let root = Self.topViewController() else { onResult(nil); return }
        completion = onResult
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = 1
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = self
        root.present(picker, animated: true)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let provider = results.first?.itemProvider, provider.canLoadObject(ofClass: UIImage.self) else {
            finish(nil); return
        }
        provider.loadObject(ofClass: UIImage.self) { [weak self] object, _ in
            let b64 = (object as? UIImage).flatMap { AvatarPhotoPicker.toBase64Png($0, side: 256) }
            DispatchQueue.main.async { self?.finish(b64) }
        }
    }

    private func finish(_ value: String?) {
        let c = completion
        completion = nil
        c?(value)
    }

    /// Square-crop-and-scale to `side`×`side`, then PNG → base64.
    static func toBase64Png(_ image: UIImage, side: CGFloat) -> String? {
        let size = CGSize(width: side, height: side)
        let renderer = UIGraphicsImageRenderer(size: size)
        let scaled = renderer.image { _ in
            // Aspect-fill into the square frame.
            let s = max(side / image.size.width, side / image.size.height)
            let w = image.size.width * s
            let h = image.size.height * s
            image.draw(in: CGRect(x: (side - w) / 2, y: (side - h) / 2, width: w, height: h))
        }
        return scaled.pngData()?.base64EncodedString()
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes
        let windowScene = (scenes.first { $0.activationState == .foregroundActive } ?? scenes.first) as? UIWindowScene
        let window = windowScene?.windows.first { $0.isKeyWindow } ?? windowScene?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
