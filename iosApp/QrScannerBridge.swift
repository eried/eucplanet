import AVFoundation
import UIKit
import Shared

// The crew pass camera: AVFoundation behind the shared QrCameraSdk interface,
// as LocationBridge does for CoreLocation. Each decoded QR goes to Kotlin,
// which decides whether it is a pairing code.
final class QrScannerBridge: NSObject, QrCameraSdk {

    func cameraAvailable() -> Bool {
        AVCaptureDevice.default(for: .video) != nil
    }

    // 0 not asked yet, 1 refused (or restricted), 2 allowed.
    func authorization() -> Int32 {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return 2
        case .notDetermined: return 0
        default: return 1
        }
    }

    func requestAccess(onResult: @escaping (KotlinBoolean) -> Void) {
        AVCaptureDevice.requestAccess(for: .video) { ok in
            DispatchQueue.main.async { onResult(KotlinBoolean(value: ok)) }
        }
    }

    func makeScannerView(onCode: @escaping (String) -> Void) -> UIView {
        QrPreviewView(onCode: onCode)
    }

    func stopScannerView(view: UIView) {
        (view as? QrPreviewView)?.stop()
    }
}

/// A live back-camera preview that reports every QR it reads.
private final class QrPreviewView: UIView, AVCaptureMetadataOutputObjectsDelegate {
    private let session = AVCaptureSession()
    private let preview: AVCaptureVideoPreviewLayer
    private let onCode: (String) -> Void
    private let queue = DispatchQueue(label: "eucplanet.qr")

    init(onCode: @escaping (String) -> Void) {
        self.onCode = onCode
        preview = AVCaptureVideoPreviewLayer(session: session)
        super.init(frame: .zero)
        preview.videoGravity = .resizeAspectFill
        layer.addSublayer(preview)
        configure()
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    private func configure() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }
        session.beginConfiguration()
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            if output.availableMetadataObjectTypes.contains(.qr) {
                output.metadataObjectTypes = [.qr]
            }
        }
        session.commitConfiguration()
        // startRunning blocks, so off the main thread.
        queue.async { [session] in session.startRunning() }
    }

    func stop() {
        queue.async { [session] in session.stopRunning() }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        preview.frame = bounds
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        for case let code as AVMetadataMachineReadableCodeObject in metadataObjects {
            if let text = code.stringValue { onCode(text) }
        }
    }
}
