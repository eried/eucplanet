import CoreLocation
import Foundation
import Shared

// CoreLocation behind the shared LocationSdk interface. Fixes go to
// IosLocation, which the trip recorder and the dashboard read.
final class LocationBridge: NSObject, LocationSdk, CLLocationManagerDelegate {

    private let manager = CLLocationManager()
    private var started = false

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
        IosLocation.shared.onAuthorization(granted: authorized)
    }

    private var authorized: Bool {
        let s = manager.authorizationStatus
        return s == .authorizedWhenInUse || s == .authorizedAlways
    }

    // MARK: LocationSdk

    func start() {
        started = true
        if authorized {
            manager.startUpdatingLocation()
        } else {
            manager.requestWhenInUseAuthorization()
        }
    }

    func stop() {
        started = false
        manager.stopUpdatingLocation()
    }

    func keepInBackground(on: Bool) {
        manager.allowsBackgroundLocationUpdates = on
        manager.showsBackgroundLocationIndicator = on
    }

    // MARK: CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        IosLocation.shared.onAuthorization(granted: authorized)
        if started && authorized { manager.startUpdatingLocation() }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let l = locations.last else { return }
        IosLocation.shared.onFix(
            latitude: l.coordinate.latitude,
            longitude: l.coordinate.longitude,
            altitude: l.altitude,
            verticalAccuracy: l.verticalAccuracy,
            speed: l.speed,
            course: l.course,
            horizontalAccuracy: l.horizontalAccuracy,
            timeMs: Int64(l.timestamp.timeIntervalSince1970 * 1000)
        )
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}
