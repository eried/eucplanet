import Foundation
import Shared

/// Bonjour (`_eucplanet._tcp`) → shared `HudDiscoveryClient`. The iOS counterpart
/// to Android's JmDNS browse in `HudServer.resolveViaMdns`: when the rider leaves
/// the HUD IP blank, the shared dialer starts this; the first resolved IPv4
/// host:port is pushed back so the app dials the HUD with no typing.
///
/// Requires `NSLocalNetworkUsageDescription` + `NSBonjourServices` (`_eucplanet._tcp`)
/// in Info.plist and the rider granting the Local Network permission — same
/// prerequisites as any LAN connection on iOS 14+.
final class HudDiscoveryBridge: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    private let browser = NetServiceBrowser()
    private var resolving: [NetService] = []

    func start() {
        DispatchQueue.main.async {
            self.browser.stop()
            self.browser.delegate = self
            self.browser.searchForServices(ofType: "_eucplanet._tcp.", inDomain: "local.")
        }
    }

    func stop() {
        DispatchQueue.main.async {
            self.browser.stop()
            self.resolving.forEach { $0.stop() }
            self.resolving.removeAll()
        }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        service.delegate = self
        resolving.append(service)
        service.resolve(withTimeout: 5.0)
    }

    func netServiceDidResolveAddress(_ service: NetService) {
        guard let addresses = service.addresses else { return }
        for data in addresses {
            let ip: String? = data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> String? in
                guard let base = raw.baseAddress else { return nil }
                let sa = base.assumingMemoryBound(to: sockaddr.self)
                guard sa.pointee.sa_family == sa_family_t(AF_INET) else { return nil }
                var addr = base.assumingMemoryBound(to: sockaddr_in.self).pointee.sin_addr
                var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                inet_ntop(AF_INET, &addr, &buf, socklen_t(INET_ADDRSTRLEN))
                return String(cString: buf)
            }
            if let ip = ip {
                HudDiscoveryClient.shared.onResolved(host: ip, port: Int32(service.port))
                return
            }
        }
    }
}
