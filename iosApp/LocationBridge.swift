import CoreLocation
import Shared

/// CoreLocation → shared `LocationService`. Started/stopped via the shared hooks
/// wired in `AppDelegate`; pushes every fix into the Kotlin `LocationService` so
/// the shared auto-lights / HUD GPS / trip-track consumers see it.
final class LocationBridge: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.activityType = .fitness
        manager.distanceFilter = 3
    }

    func start() {
        manager.requestWhenInUseAuthorization()
        manager.startUpdatingLocation()
    }

    func stop() {
        manager.stopUpdatingLocation()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        let speedKmh: Float = loc.speed >= 0 ? Float(loc.speed * 3.6) : -1
        let bearing: Float = loc.course >= 0 ? Float(loc.course) : -1
        let alt = Float(loc.altitude)
        let ts = Int64(loc.timestamp.timeIntervalSince1970 * 1000)
        LocationService.shared.update(
            lat: loc.coordinate.latitude,
            lng: loc.coordinate.longitude,
            speedKmh: speedKmh,
            bearingDeg: bearing,
            altitudeM: alt,
            timestamp: ts
        )
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Keep the last fix; a transient failure shouldn't blank the GPS UI.
    }
}
