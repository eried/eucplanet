import Shared
import WidgetKit

// WidgetKit side of IosWidgets.kt and IosWeatherWidgets.kt: Kotlin writes
// what the home screen widgets say and calls this to have iOS draw them again.
final class WidgetReloader: NSObject, WidgetBridge {
    // The kinds in iosApp/Widgets/WeatherWidgets.swift.
    private static let weatherKinds = ["EucWeatherScore", "EucWeatherForecast"]

    func reload() {
        WidgetCenter.shared.reloadAllTimelines()
    }

    // The weather widgets alone: iOS counts each widget's reloads, and a new
    // forecast is no reason to spend the wheel widget's.
    func reloadWeather() {
        Self.weatherKinds.forEach { WidgetCenter.shared.reloadTimelines(ofKind: $0) }
    }

    func weatherPlaced(done: @escaping (KotlinBoolean) -> Void) {
        WidgetCenter.shared.getCurrentConfigurations { result in
            let kinds = (try? result.get())?.map(\.kind) ?? []
            done(KotlinBoolean(bool: kinds.contains { Self.weatherKinds.contains($0) }))
        }
    }
}
