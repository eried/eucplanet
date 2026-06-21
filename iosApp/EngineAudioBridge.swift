import AVFoundation
import Shared

/// AVAudioEngine → shared `EngineSoundController`. The iOS counterpart to Android's
/// AudioTrack producer: an `AVAudioSourceNode` pulls mono Float32 PCM from the shared
/// synth on the realtime audio thread (via `EngineAudioNative`). Started/stopped
/// through the `EngineSoundController` hooks wired in `AppDelegate`.
final class EngineAudioBridge {
    private let engine = AVAudioEngine()
    private var sourceNode: AVAudioSourceNode?
    private let sampleRate = 44100.0

    func start() {
        DispatchQueue.main.async {
            guard !self.engine.isRunning else { return }
            guard let fmt = AVAudioFormat(standardFormatWithSampleRate: self.sampleRate, channels: 1) else { return }
            let node = AVAudioSourceNode(format: fmt) { _, _, frameCount, ablPtr -> OSStatus in
                let abl = UnsafeMutableAudioBufferListPointer(ablPtr)
                guard let mData = abl[0].mData else { return noErr }
                let ptr = mData.assumingMemoryBound(to: Float.self)
                let addr = Int64(Int(bitPattern: ptr))
                EngineAudioNative.shared.render(bufferAddr: addr, count: Int32(frameCount))
                return noErr
            }
            self.sourceNode = node
            self.engine.attach(node)
            self.engine.connect(node, to: self.engine.mainMixerNode, format: fmt)
            do {
                try AVAudioSession.sharedInstance().setCategory(
                    .playback, mode: .default, options: [.mixWithOthers, .duckOthers])
                try AVAudioSession.sharedInstance().setActive(true)
                try self.engine.start()
            } catch {
                print("EngineAudio start failed: \(error)")
            }
        }
    }

    func stop() {
        DispatchQueue.main.async {
            guard self.engine.isRunning else { return }
            self.engine.stop()
            if let n = self.sourceNode {
                self.engine.detach(n)
                self.sourceNode = nil
            }
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        }
    }
}
