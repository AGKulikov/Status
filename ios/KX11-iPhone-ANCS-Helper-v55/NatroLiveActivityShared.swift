import ActivityKit
import AppIntents
import CoreFoundation
import Foundation

enum NatroLiveControl: String, Codable, Hashable, CaseIterable {
    case airConditioning
    case automaticClimate
    case frontDefrost
    case recirculation
    case fan
    case driverSeatHeat

    var title: String {
        switch self {
        case .airConditioning: return "A/C"
        case .automaticClimate: return "Климат"
        case .frontDefrost: return "Стекло"
        case .recirculation: return "Рецирк."
        case .fan: return "Вентилятор"
        case .driverSeatHeat: return "Сиденье"
        }
    }

    var systemImage: String {
        switch self {
        case .airConditioning: return "snowflake"
        case .automaticClimate: return "a.circle"
        case .frontDefrost: return "windshield.front.and.heat.waves"
        case .recirculation: return "arrow.triangle.2.circlepath"
        case .fan: return "fanblades"
        case .driverSeatHeat: return "seat.max"
        }
    }

    var controlID: UInt8 {
        switch self {
        case .airConditioning: return 2
        case .automaticClimate: return 3
        case .frontDefrost: return 5
        case .recirculation: return 8
        case .fan: return 9
        case .driverSeatHeat: return 20
        }
    }

    var commandAction: String {
        switch self {
        case .airConditioning, .automaticClimate, .frontDefrost:
            return "toggle:\(controlID)"
        case .recirculation, .fan, .driverSeatHeat:
            return "cycle:\(controlID)"
        }
    }
}

enum NatroLiveActivityPreferences {
    static let changed = Notification.Name("NatroLiveActivityPreferencesChanged")

    private enum Key {
        static let automaticStart = "liveActivity.automaticStart"
        static let automaticStartConfigured = "liveActivity.automaticStartConfigured"
        static let showVehicle = "liveActivity.showVehicle"
        static let showVehicleConfigured = "liveActivity.showVehicleConfigured"
        static let demoMode = "liveActivity.demoMode"
        static let vehicleName = "liveActivity.vehicleName"
        static let controls = "liveActivity.controls"
    }

    static var automaticStart: Bool {
        get {
            let defaults = UserDefaults.standard
            return defaults.bool(forKey: Key.automaticStartConfigured)
                ? defaults.bool(forKey: Key.automaticStart) : true
        }
        set {
            let defaults = UserDefaults.standard
            defaults.set(true, forKey: Key.automaticStartConfigured)
            defaults.set(newValue, forKey: Key.automaticStart)
            notify()
        }
    }

    static var showVehicle: Bool {
        get {
            let defaults = UserDefaults.standard
            return defaults.bool(forKey: Key.showVehicleConfigured)
                ? defaults.bool(forKey: Key.showVehicle) : true
        }
        set {
            let defaults = UserDefaults.standard
            defaults.set(true, forKey: Key.showVehicleConfigured)
            defaults.set(newValue, forKey: Key.showVehicle)
            notify()
        }
    }

    static var demoMode: Bool {
        get { UserDefaults.standard.bool(forKey: Key.demoMode) }
        set {
            UserDefaults.standard.set(newValue, forKey: Key.demoMode)
            notify()
        }
    }

    static var vehicleName: String {
        get {
            let stored = UserDefaults.standard.string(forKey: Key.vehicleName)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return stored.isEmpty ? "GEELY MONJARO" : String(stored.prefix(32))
        }
        set {
            let clean = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            UserDefaults.standard.set(String(clean.prefix(32)), forKey: Key.vehicleName)
            notify()
        }
    }

    static var controls: [NatroLiveControl] {
        get {
            let defaults: [NatroLiveControl] = [
                .airConditioning, .automaticClimate, .frontDefrost, .recirculation
            ]
            guard let raw = UserDefaults.standard.array(forKey: Key.controls) as? [String] else {
                return defaults
            }
            var result: [NatroLiveControl] = []
            for value in raw {
                guard let control = NatroLiveControl(rawValue: value),
                      !result.contains(control) else { continue }
                result.append(control)
            }
            for fallback in defaults + NatroLiveControl.allCases where result.count < 4 {
                if !result.contains(fallback) { result.append(fallback) }
            }
            return Array(result.prefix(4))
        }
        set {
            var unique: [NatroLiveControl] = []
            for control in newValue + NatroLiveControl.allCases where unique.count < 4 {
                if !unique.contains(control) { unique.append(control) }
            }
            UserDefaults.standard.set(unique.map(\.rawValue), forKey: Key.controls)
            notify()
        }
    }

    static func setControl(_ control: NatroLiveControl, at slot: Int) {
        guard (0..<4).contains(slot) else { return }
        var current = controls
        if let other = current.firstIndex(of: control), other != slot {
            current.swapAt(other, slot)
        } else {
            current[slot] = control
        }
        controls = current
    }

    private static func notify() {
        NotificationCenter.default.post(name: changed, object: nil)
    }
}

@available(iOS 16.1, *)
struct NatroLiveActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var vehicleName: String
        var status: String
        var ancsConnected: Bool
        var isDemo: Bool
        var targetTemperatureHundredths: Int?
        var cabinTemperatureTenths: Int?
        var outdoorTemperatureTenths: Int?
        var activeControlIDs: [Int]
        var availableControlIDs: [Int]
        var controls: [NatroLiveControl]
        var showVehicle: Bool
        var updatedAt: Date
    }
}

enum NatroLiveActivityCommandMailbox {
    static let suiteName = "group.ru.natro.kx11ancshelper"
    static let notification = "ru.natro.kx11ancshelper.live-command" as CFString
    private static let actionKey = "liveCommand.action"
    private static let nonceKey = "liveCommand.nonce"

    static func enqueue(_ action: String) {
        guard !action.isEmpty, let defaults = UserDefaults(suiteName: suiteName) else { return }
        defaults.set(action, forKey: actionKey)
        defaults.set(UUID().uuidString, forKey: nonceKey)
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName(notification),
            nil,
            nil,
            true
        )
    }

    static func consume() -> String? {
        guard let defaults = UserDefaults(suiteName: suiteName),
              let action = defaults.string(forKey: actionKey), !action.isEmpty else { return nil }
        defaults.removeObject(forKey: actionKey)
        defaults.removeObject(forKey: nonceKey)
        return action
    }
}

@available(iOS 17.0, *)
struct NatroLiveControlIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Команда автомобилю"
    static var description = IntentDescription("Передаёт выбранную команду в Natro по Bluetooth.")
    static var openAppWhenRun = false

    @Parameter(title: "Действие") var action: String

    init() { action = "" }
    init(action: String) { self.action = action }

    func perform() async throws -> some IntentResult {
        NatroLiveActivityCommandMailbox.enqueue(action)
        return .result()
    }
}
