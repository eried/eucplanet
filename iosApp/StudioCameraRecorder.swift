import AVFoundation
import Photos
import UIKit
import Shared

/// Camera video recorder for the Overlay Studio → shared `StudioRecorder`. The iOS
/// counterpart to Android's studio recording: presents a full-screen camera, draws
/// the live overlay (the Kotlin `StudioRecorder.drawList` op-list) on top, and on
/// record burns the overlay into each frame via AVAssetWriter, saving the MP4 to
/// Photos. Wired in AppDelegate; the studio's "● Rec" button calls start()/stop().
///
/// Requires NSCameraUsageDescription / NSMicrophoneUsageDescription /
/// NSPhotoLibraryAddUsageDescription in Info.plist.
final class StudioCameraRecorder {
    private var vc: StudioCameraViewController?

    func start() {
        DispatchQueue.main.async {
            guard self.vc == nil,
                  let root = UIApplication.shared.keyWindowRoot else { return }
            let cam = StudioCameraViewController()
            cam.modalPresentationStyle = .fullScreen
            cam.onClose = { [weak self] in self?.vc = nil }
            self.vc = cam
            root.present(cam, animated: true)
        }
    }

    func stop() {
        DispatchQueue.main.async { self.vc?.toggleRecording(forceStop: true) }
    }
}

private extension UIApplication {
    var keyWindowRoot: UIViewController? {
        connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first(where: { $0.isKeyWindow })?.rootViewController?.topMost
    }
}
private extension UIViewController {
    var topMost: UIViewController { presentedViewController?.topMost ?? self }
}

final class StudioCameraViewController: UIViewController,
    AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {

    var onClose: (() -> Void)?

    private let session = AVCaptureSession()
    private let videoOut = AVCaptureVideoDataOutput()
    private let audioOut = AVCaptureAudioDataOutput()
    private let queue = DispatchQueue(label: "studio.camera")
    private let ciContext = CIContext()

    private var writer: AVAssetWriter?
    private var videoIn: AVAssetWriterInput?
    private var audioIn: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var recording = false
    private var sessionStarted = false
    private var fileURL: URL?

    // Cached overlay image, refreshed off the capture loop.
    private var overlayImage: CGImage?
    private var overlaySize: CGSize = .zero
    private var displayLink: CADisplayLink?

    private let overlayView = UIImageView()
    private let recButton = UIButton(type: .system)
    private let closeButton = UIButton(type: .system)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureSession()

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)

        overlayView.frame = view.bounds
        overlayView.contentMode = .scaleToFill
        overlayView.isUserInteractionEnabled = false
        view.addSubview(overlayView)

        closeButton.setTitle("✕", for: .normal)
        closeButton.setTitleColor(.white, for: .normal)
        closeButton.titleLabel?.font = .systemFont(ofSize: 24, weight: .bold)
        closeButton.frame = CGRect(x: 16, y: 48, width: 44, height: 44)
        closeButton.addTarget(self, action: #selector(close), for: .touchUpInside)
        view.addSubview(closeButton)

        recButton.setTitle("● Record", for: .normal)
        recButton.setTitleColor(.white, for: .normal)
        recButton.titleLabel?.font = .systemFont(ofSize: 17, weight: .bold)
        recButton.backgroundColor = UIColor(red: 0.9, green: 0.2, blue: 0.2, alpha: 0.9)
        recButton.layer.cornerRadius = 24
        recButton.addTarget(self, action: #selector(recTapped), for: .touchUpInside)
        view.addSubview(recButton)

        queue.async { self.session.startRunning() }
        let link = CADisplayLink(target: self, selector: #selector(refreshOverlay))
        link.preferredFramesPerSecond = 15
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        (view.layer.sublayers?.first { $0 is AVCaptureVideoPreviewLayer })?.frame = view.bounds
        overlayView.frame = view.bounds
        recButton.frame = CGRect(x: view.bounds.midX - 80, y: view.bounds.height - 110, width: 160, height: 48)
    }

    private func configureSession() {
        session.beginConfiguration()
        session.sessionPreset = .high
        if let cam = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
           let input = try? AVCaptureDeviceInput(device: cam), session.canAddInput(input) {
            session.addInput(input)
        }
        if let mic = AVCaptureDevice.default(for: .audio),
           let ainput = try? AVCaptureDeviceInput(device: mic), session.canAddInput(ainput) {
            session.addInput(ainput)
        }
        videoOut.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        videoOut.setSampleBufferDelegate(self, queue: queue)
        if session.canAddOutput(videoOut) { session.addOutput(videoOut) }
        audioOut.setSampleBufferDelegate(self, queue: queue)
        if session.canAddOutput(audioOut) { session.addOutput(audioOut) }
        if let c = videoOut.connection(with: .video) { c.videoOrientation = .portrait }
        session.commitConfiguration()
    }

    // MARK: overlay rendering (off the capture loop)

    @objc private func refreshOverlay() {
        let size = overlaySize == .zero ? CGSize(width: 720, height: 1280) : overlaySize
        let json = StudioRecorder.shared.drawList(canvasW: Int32(size.width), canvasH: Int32(size.height))
        guard let data = json.data(using: .utf8),
              let ops = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return }
        let renderer = UIGraphicsImageRenderer(size: size)
        let img = renderer.image { rctx in StudioCameraViewController.drawOps(ops, in: rctx.cgContext) }
        overlayImage = img.cgImage
        overlayView.image = img
    }

    static func drawOps(_ ops: [[String: Any]], in ctx: CGContext) {
        func n(_ v: Any?) -> CGFloat { CGFloat((v as? NSNumber)?.doubleValue ?? 0) }
        func i(_ v: Any?) -> Int { (v as? NSNumber)?.intValue ?? 0 }
        func color(_ v: Any?) -> UIColor {
            let argb = (v as? NSNumber)?.int64Value ?? 0
            return UIColor(red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
                           blue: CGFloat(argb & 0xFF) / 255, alpha: CGFloat((argb >> 24) & 0xFF) / 255)
        }
        for op in ops {
            switch op["op"] as? String ?? "" {
            case "rect":
                let path = UIBezierPath(roundedRect: CGRect(x: n(op["x"]), y: n(op["y"]), width: n(op["w"]), height: n(op["h"])), cornerRadius: n(op["r"]))
                color(op["c"]).setFill(); path.fill()
            case "bar":
                let x = n(op["x"]); let y = n(op["y"]); let w = n(op["w"]); let h = n(op["h"]); let f = n(op["frac"])
                color(op["bg"]).setFill(); UIBezierPath(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerRadius: h/2).fill()
                color(op["fg"]).setFill(); UIBezierPath(roundedRect: CGRect(x: x, y: y, width: w * f, height: h), cornerRadius: h/2).fill()
            case "arc":
                let cx = n(op["cx"]); let cy = n(op["cy"]); let r = n(op["r"])
                let a0 = n(op["a0"]) * .pi / 180; let a1 = (n(op["a0"]) + n(op["sw"])) * .pi / 180
                let path = UIBezierPath(arcCenter: CGPoint(x: cx, y: cy), radius: r, startAngle: a0, endAngle: a1, clockwise: true)
                path.lineWidth = n(op["lw"]); path.lineCapStyle = .round
                color(op["c"]).setStroke(); path.stroke()
            case "text":
                let s = n(op["s"]); let t = op["t"] as? String ?? ""
                let font = UIFont.systemFont(ofSize: s, weight: i(op["b"]) == 1 ? .bold : .regular)
                let str = NSAttributedString(string: t, attributes: [.font: font, .foregroundColor: color(op["c"])])
                let sz = str.size()
                var x = n(op["x"]); let align = i(op["a"])
                if align == 1 { x -= sz.width / 2 } else if align == 2 { x -= sz.width }
                str.draw(at: CGPoint(x: x, y: n(op["y"]) - s))
            default: break
            }
        }
    }

    // MARK: recording

    @objc private func recTapped() { toggleRecording(forceStop: false) }

    func toggleRecording(forceStop: Bool) {
        if recording || forceStop { stopRecording() } else { startRecording() }
    }

    private func startRecording() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("euc_\(Int(Date().timeIntervalSince1970)).mp4")
        guard let w = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return }
        let vset: [String: Any] = [AVVideoCodecKey: AVVideoCodecType.h264,
                                   AVVideoWidthKey: 720, AVVideoHeightKey: 1280]
        let vin = AVAssetWriterInput(mediaType: .video, outputSettings: vset)
        vin.expectsMediaDataInRealTime = true
        let ada = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: vin, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: 720, kCVPixelBufferHeightKey as String: 1280])
        let aset: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVNumberOfChannelsKey: 1, AVSampleRateKey: 44100]
        let ain = AVAssetWriterInput(mediaType: .audio, outputSettings: aset)
        ain.expectsMediaDataInRealTime = true
        if w.canAdd(vin) { w.add(vin) }
        if w.canAdd(ain) { w.add(ain) }
        writer = w; videoIn = vin; audioIn = ain; adaptor = ada; fileURL = url
        sessionStarted = false
        recording = true
        DispatchQueue.main.async { self.recButton.setTitle("■ Stop", for: .normal); self.recButton.backgroundColor = UIColor(white: 0.2, alpha: 0.9) }
        StudioRecorder.shared.setRecording(value: true)
    }

    private func stopRecording() {
        guard recording, let w = writer else { return }
        recording = false
        StudioRecorder.shared.setRecording(value: false)
        DispatchQueue.main.async { self.recButton.setTitle("● Record", for: .normal); self.recButton.backgroundColor = UIColor(red: 0.9, green: 0.2, blue: 0.2, alpha: 0.9) }
        videoIn?.markAsFinished(); audioIn?.markAsFinished()
        let url = fileURL
        w.finishWriting { [weak self] in
            self?.writer = nil
            if let url = url { self?.saveToPhotos(url) }
        }
    }

    private func saveToPhotos(_ url: URL) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized else { return }
            PHPhotoLibrary.shared().performChanges({
                PHAssetCreationRequest.forAsset().addResource(with: .video, fileURL: url, options: nil)
            }, completionHandler: { _, _ in })
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard recording, let w = writer else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        if !sessionStarted {
            w.startWriting(); w.startSession(atSourceTime: pts); sessionStarted = true
        }
        if output == videoOut {
            guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer), let adaptor = adaptor,
                  let vin = videoIn, vin.isReadyForMoreMediaData,
                  let pool = adaptor.pixelBufferPool else { return }
            overlaySize = CGSize(width: CVPixelBufferGetWidth(pb), height: CVPixelBufferGetHeight(pb))
            var out: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &out)
            guard let outPB = out else { return }
            var image = CIImage(cvPixelBuffer: pb)
            if let ov = overlayImage {
                let ovCI = CIImage(cgImage: ov)
                let scaled = ovCI.transformed(by: CGAffineTransform(scaleX: image.extent.width / ovCI.extent.width,
                                                                    y: image.extent.height / ovCI.extent.height))
                image = scaled.composited(over: image)
            }
            ciContext.render(image, to: outPB)
            adaptor.append(outPB, withPresentationTime: pts)
        } else if output == audioOut, let ain = audioIn, ain.isReadyForMoreMediaData {
            ain.append(sampleBuffer)
        }
    }

    @objc private func close() {
        if recording { stopRecording() }
        session.stopRunning()
        displayLink?.invalidate(); displayLink = nil
        dismiss(animated: true) { self.onClose?() }
    }
}
