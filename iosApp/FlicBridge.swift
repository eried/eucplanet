import Foundation
import Shared
import flic2lib

// Flic's iOS SDK (flic2lib) behind the shared FlicSdk interface. Presses and
// pairing results go back to IosFlic, which runs them through the same slots
// and action executor Android uses.
final class FlicBridge: NSObject, FlicSdk, FLICManagerDelegate, FLICButtonDelegate {

    private var manager: FLICManager?

    func start() {
        manager = FLICManager.configure(with: self, buttonDelegate: self, background: true)
    }

    private func report() {
        let list = (manager?.buttons() ?? []).map {
            FlicButtonInfo(address: $0.bluetoothAddress, name: $0.nickname ?? $0.name)
        }
        IosFlic.shared.onButtons(buttons: list)
    }

    private func button(_ address: String) -> FLICButton? {
        manager?.buttons().first { $0.bluetoothAddress.uppercased() == address.uppercased() }
    }

    // MARK: FlicSdk

    func startScan() {
        guard let manager = manager else { return }
        manager.scanForButtons(stateChangeHandler: { event in
            switch event {
            case .discovered: IosFlic.shared.onScanProgress(event: "discovered")
            case .connected: IosFlic.shared.onScanProgress(event: "connected")
            default: IosFlic.shared.onScanProgress(event: "verified")
            }
        }, completion: { [weak self] button, error in
            if let button = button {
                button.triggerMode = .clickAndDoubleClickAndHold
                button.connect()
                IosFlic.shared.onScanComplete(
                    address: button.bluetoothAddress, name: button.nickname ?? button.name, errorCode: 0)
            } else {
                let code = (error as NSError?)?.code ?? -1
                IosFlic.shared.onScanComplete(address: nil, name: nil, errorCode: Int32(code))
            }
            self?.report()
        })
    }

    func stopScan() {
        manager?.stopScan()
    }

    func forget(address: String) {
        guard let manager = manager, let b = button(address) else { return }
        manager.forgetButton(b) { [weak self] _, _ in self?.report() }
    }

    // MARK: FLICManagerDelegate

    func managerDidRestoreState(_ manager: FLICManager) {
        for b in manager.buttons() {
            b.triggerMode = .clickAndDoubleClickAndHold
            b.connect()
        }
        report()
    }

    func manager(_ manager: FLICManager, didUpdate state: FLICManagerState) {}

    // MARK: FLICButtonDelegate

    func buttonDidConnect(_ button: FLICButton) {}
    func buttonIsReady(_ button: FLICButton) {}
    func button(_ button: FLICButton, didDisconnectWithError error: Error?) {}
    func button(_ button: FLICButton, didFailToConnectWithError error: Error?) {}

    func button(_ button: FLICButton, didReceiveButtonClick queued: Bool, age: Int) {
        IosFlic.shared.onPress(address: button.bluetoothAddress, gesture: "click")
    }

    func button(_ button: FLICButton, didReceiveButtonDoubleClick queued: Bool, age: Int) {
        IosFlic.shared.onPress(address: button.bluetoothAddress, gesture: "doubleClick")
    }

    func button(_ button: FLICButton, didReceiveButtonHold queued: Bool, age: Int) {
        IosFlic.shared.onPress(address: button.bluetoothAddress, gesture: "hold")
    }

    func button(_ button: FLICButton, didUnpairWithError error: Error?) {
        report()
    }
}
