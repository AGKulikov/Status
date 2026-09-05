import ActivityKit
import AppIntents
import CoreFoundation
import Foundation

enum NatroLivePanel: String, Codable, Hashable, CaseIterable {
    case climate
    case functions

    var relevanceScore: Double { self == .climate ? 100 : 90 }
}

enum NatroLiveControlSection: String, Codable, CaseIterable {
    case climate = "Климат"
    case seats = "Сиденья"
    case vehicle = "Автомобиль"
    case comfort = "Комфорт"
    case media = "Медиа"
}

/// Tile-safe subset of the legacy 39-command C5 baseline plus capability-gated v56 entries.
/// Range controls remain available in the
/// full in-app dashboard; the Lock Screen uses temperature +/- and finite one-tap controls only.
enum NatroLiveControl: String, Codable, Hashable, CaseIterable {
    // Keep all v55 raw values stable so an in-place update can decode an existing activity.
    case climatePower
    case airConditioning
    case automaticClimate
    case frontGlassHeat
    case frontDefrost
    case rearDefrost
    case climateSync
    case recirculation
    case fan
    case airflow
    case rearClimate
    case rearAutomaticClimate
    case rearFan
    case rearPanelLock
    case driverSeatHeat
    case passengerSeatHeat
    case driverSeatVentilation
    case passengerSeatVentilation
    case steeringWheelHeat
    case rearLeftSeatHeat
    case rearRightSeatHeat
    case rearLeftSeatVentilation
    case rearRightSeatVentilation
    case trunk
    case driveMode
    case autoHold
    case startStop
    case fuelSave
    case wiperService
    case ambientLighting
    case ambientMode
    case ambientEffect
    case ambientColor
    case ambientTheme
    case passengerScreen
    case closeDriverWindow
    case closePassengerWindow
    case closeRearLeftWindow
    case closeRearRightWindow
    case closeSunroof
    case mediaPlayPause
    case mediaNext
    case mediaPrevious
    case mediaMute

    var title: String {
        switch self {
        case .climatePower: return "Климат"
        case .airConditioning: return "A/C"
        case .automaticClimate: return "AUTO"
        case .frontGlassHeat: return "Лобовое"
        case .frontDefrost: return "MAX"
        case .rearDefrost: return "Заднее"
        case .climateSync: return "SYNC"
        case .recirculation: return "Рецирк."
        case .fan: return "Вентил."
        case .airflow: return "Обдув"
        case .rearClimate: return "Климат 2"
        case .rearAutomaticClimate: return "AUTO 2"
        case .rearFan: return "Вентил. 2"
        case .rearPanelLock: return "Блок. 2"
        case .driverSeatHeat: return "Водит. тепло"
        case .passengerSeatHeat: return "Пасс. тепло"
        case .driverSeatVentilation: return "Водит. вент."
        case .passengerSeatVentilation: return "Пасс. вент."
        case .steeringWheelHeat: return "Руль"
        case .rearLeftSeatHeat: return "Сзади Л тепло"
        case .rearRightSeatHeat: return "Сзади П тепло"
        case .rearLeftSeatVentilation: return "Сзади Л вент."
        case .rearRightSeatVentilation: return "Сзади П вент."
        case .trunk: return "Багажник"
        case .driveMode: return "Режим"
        case .autoHold: return "Auto Hold"
        case .startStop: return "Start/Stop"
        case .fuelSave: return "Экономия"
        case .wiperService: return "Дворники"
        case .ambientLighting: return "Подсветка"
        case .ambientMode: return "Режим света"
        case .ambientEffect: return "Эффект света"
        case .ambientColor: return "Цвет света"
        case .ambientTheme: return "Тема света"
        case .passengerScreen: return "Экран"
        case .closeDriverWindow: return "Окно водит."
        case .closePassengerWindow: return "Окно пасс."
        case .closeRearLeftWindow: return "Окно зад. Л"
        case .closeRearRightWindow: return "Окно зад. П"
        case .closeSunroof: return "Закрыть люк"
        case .mediaPlayPause: return "Play/Pause"
        case .mediaNext: return "След."
        case .mediaPrevious: return "Назад"
        case .mediaMute: return "Без звука"
        }
    }

    var settingsTitle: String {
        switch self {
        case .climatePower: return "Климат — питание"
        case .airConditioning: return "Кондиционер"
        case .automaticClimate: return "Автоматический климат"
        case .frontGlassHeat: return "Обогрев лобового стекла"
        case .frontDefrost: return "Максимальный обдув лобового"
        case .rearDefrost: return "Обогрев заднего стекла"
        case .climateSync: return "Синхронизация зон"
        case .recirculation: return "Рециркуляция"
        case .fan: return "Передний вентилятор"
        case .airflow: return "Направление обдува"
        case .rearClimate: return "Задний климат"
        case .rearAutomaticClimate: return "AUTO задней зоны"
        case .rearFan: return "Задний вентилятор"
        case .rearPanelLock: return "Блокировка задней панели"
        case .driverSeatHeat: return "Подогрев сиденья водителя"
        case .passengerSeatHeat: return "Подогрев сиденья пассажира"
        case .driverSeatVentilation: return "Вентиляция сиденья водителя"
        case .passengerSeatVentilation: return "Вентиляция сиденья пассажира"
        case .steeringWheelHeat: return "Подогрев руля"
        case .rearLeftSeatHeat: return "Подогрев заднего левого сиденья"
        case .rearRightSeatHeat: return "Подогрев заднего правого сиденья"
        case .rearLeftSeatVentilation: return "Вентиляция заднего левого сиденья"
        case .rearRightSeatVentilation: return "Вентиляция заднего правого сиденья"
        case .trunk: return "Открытие/закрытие багажника"
        case .driveMode: return "Режим движения"
        case .autoHold: return "Auto Hold"
        case .startStop: return "Start/Stop"
        case .fuelSave: return "Экономия топлива"
        case .wiperService: return "Сервисное положение дворников"
        case .ambientLighting: return "Атмосферная подсветка"
        case .ambientMode: return "Режим атмосферной подсветки"
        case .ambientEffect: return "Эффект атмосферной подсветки"
        case .ambientColor: return "Цвет атмосферной подсветки"
        case .ambientTheme: return "Тема атмосферной подсветки"
        case .passengerScreen: return "Экран пассажира"
        case .closeDriverWindow: return "Закрыть окно водителя"
        case .closePassengerWindow: return "Закрыть окно пассажира"
        case .closeRearLeftWindow: return "Закрыть заднее левое окно"
        case .closeRearRightWindow: return "Закрыть заднее правое окно"
        case .closeSunroof: return "Закрыть наклон люка"
        case .mediaPlayPause: return "Воспроизведение / пауза"
        case .mediaNext: return "Следующий трек"
        case .mediaPrevious: return "Предыдущий трек"
        case .mediaMute: return "Без звука"
        }
    }

    var systemImage: String {
        switch self {
        case .climatePower: return "power"
        case .airConditioning: return "snowflake"
        case .automaticClimate: return "a.circle"
        case .frontGlassHeat, .frontDefrost: return "windshield.front.and.heat.waves"
        case .rearDefrost: return "windshield.rear.and.heat.waves"
        case .climateSync, .recirculation: return "arrow.triangle.2.circlepath"
        case .fan, .rearFan: return "fanblades"
        case .airflow: return "arrow.up.forward"
        case .rearClimate, .rearAutomaticClimate: return "rectangle.split.3x1"
        case .rearPanelLock: return "lock.rectangle"
        case .driverSeatHeat, .passengerSeatHeat,
             .driverSeatVentilation, .passengerSeatVentilation,
             .rearLeftSeatHeat, .rearRightSeatHeat,
             .rearLeftSeatVentilation, .rearRightSeatVentilation: return "seat.max"
        case .steeringWheelHeat: return "steeringwheel"
        case .trunk: return "car.side.rear.open"
        case .driveMode: return "gauge.medium"
        case .autoHold: return "parkingsign.circle"
        case .startStop: return "arrow.counterclockwise.circle"
        case .fuelSave: return "leaf"
        case .wiperService: return "windshield.front.and.wiper"
        case .ambientLighting, .ambientMode, .ambientEffect, .ambientColor,
             .ambientTheme: return "lightbulb"
        case .passengerScreen: return "display"
        case .closeDriverWindow, .closePassengerWindow,
             .closeRearLeftWindow, .closeRearRightWindow: return "window.shade.closed"
        case .closeSunroof: return "sun.max"
        case .mediaPlayPause: return "playpause"
        case .mediaNext: return "forward.end"
        case .mediaPrevious: return "backward.end"
        case .mediaMute: return "speaker.slash"
        }
    }

    var section: NatroLiveControlSection {
        switch self {
        case .climatePower, .airConditioning, .automaticClimate, .frontGlassHeat,
             .frontDefrost, .rearDefrost, .climateSync, .recirculation, .fan,
             .airflow, .rearClimate, .rearAutomaticClimate, .rearFan, .rearPanelLock:
            return .climate
        case .driverSeatHeat, .passengerSeatHeat, .driverSeatVentilation,
             .passengerSeatVentilation, .steeringWheelHeat,
             .rearLeftSeatHeat, .rearRightSeatHeat,
             .rearLeftSeatVentilation, .rearRightSeatVentilation:
            return .seats
        case .trunk, .driveMode, .autoHold, .startStop, .fuelSave, .wiperService,
             .closeDriverWindow, .closePassengerWindow, .closeRearLeftWindow,
             .closeRearRightWindow, .closeSunroof:
            return .vehicle
        case .ambientLighting, .ambientMode, .ambientEffect, .ambientColor,
             .ambientTheme, .passengerScreen:
            return .comfort
        case .mediaPlayPause, .mediaNext, .mediaPrevious, .mediaMute:
            return .media
        }
    }

    var controlID: UInt8 {
        switch self {
        case .climatePower: return 1
        case .airConditioning: return 2
        case .automaticClimate: return 3
        case .frontGlassHeat: return 4
        case .frontDefrost: return 5
        case .rearDefrost: return 6
        case .climateSync: return 7
        case .recirculation: return 8
        case .fan: return 9
        case .airflow: return 10
        case .rearClimate: return 13
        case .rearAutomaticClimate: return 14
        case .rearFan: return 15
        case .rearPanelLock: return 18
        case .driverSeatHeat: return 20
        case .passengerSeatHeat: return 21
        case .driverSeatVentilation: return 22
        case .passengerSeatVentilation: return 23
        case .steeringWheelHeat: return 24
        case .rearLeftSeatHeat: return 25
        case .rearRightSeatHeat: return 26
        case .rearLeftSeatVentilation: return 27
        case .rearRightSeatVentilation: return 28
        case .trunk: return 30
        case .driveMode: return 31
        case .autoHold: return 32
        case .startStop: return 33
        case .fuelSave: return 34
        case .wiperService: return 35
        case .ambientLighting: return 40
        case .passengerScreen: return 42
        case .ambientMode: return 45
        case .ambientEffect: return 46
        case .ambientColor: return 47
        case .ambientTheme: return 48
        case .mediaPlayPause: return 50
        case .mediaNext: return 51
        case .mediaPrevious: return 52
        case .mediaMute: return 53
        case .closeDriverWindow: return 55
        case .closePassengerWindow: return 56
        case .closeRearLeftWindow: return 57
        case .closeRearRightWindow: return 58
        case .closeSunroof: return 59
        }
    }

    var commandAction: String {
        switch self {
        case .recirculation, .fan, .airflow, .rearFan, .driverSeatHeat,
             .passengerSeatHeat, .driverSeatVentilation, .passengerSeatVentilation,
             .steeringWheelHeat, .rearLeftSeatHeat, .rearRightSeatHeat,
             .rearLeftSeatVentilation, .rearRightSeatVentilation, .driveMode,
             .ambientMode, .ambientEffect, .ambientColor, .ambientTheme:
            return "cycle:\(controlID)"
        case .wiperService, .mediaPlayPause, .mediaNext, .mediaPrevious, .mediaMute,
             .closeDriverWindow, .closePassengerWindow, .closeRearLeftWindow,
             .closeRearRightWindow, .closeSunroof:
            return "activate:\(controlID)"
        default:
            return "toggle:\(controlID)"
        }
    }

    var requiresConfirmation: Bool {
        switch self {
        case .trunk, .wiperService, .closeDriverWindow, .closePassengerWindow,
             .closeRearLeftWindow, .closeRearRightWindow, .closeSunroof:
            return true
        default:
            return false
        }
    }

    var isThreeStage: Bool {
        switch self {
        case .driverSeatHeat, .passengerSeatHeat, .driverSeatVentilation,
             .passengerSeatVentilation, .steeringWheelHeat, .rearLeftSeatHeat,
             .rearRightSeatHeat, .rearLeftSeatVentilation, .rearRightSeatVentilation:
            return true
        default:
            return false
        }
    }

    var isVentilation: Bool {
        self == .driverSeatVentilation || self == .passengerSeatVentilation
            || self == .rearLeftSeatVentilation || self == .rearRightSeatVentilation
    }

    static let defaultClimateControls: [NatroLiveControl] = [
        .climatePower, .airConditioning, .automaticClimate, .frontDefrost
    ]

    static let defaultFunctionControls: [NatroLiveControl] = [
        .driverSeatHeat, .driverSeatVentilation, .passengerSeatHeat,
        .passengerSeatVentilation, .steeringWheelHeat, .rearLeftSeatHeat,
        .rearRightSeatHeat, .rearDefrost, .trunk, .ambientLighting
    ]

    static var climateCandidates: [NatroLiveControl] {
        allCases.filter { $0.section == .climate }
    }

    static var functionCandidates: [NatroLiveControl] { allCases }
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
        static let legacyControls = "liveActivity.controls"
        static let climateControls = "liveActivity.climateControls.v56"
        static let functionControls = "liveActivity.functionControls.v56"
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

    /// Existing `controls` field remains the climate row to preserve v55 Activity decoding.
    static var climateControls: [NatroLiveControl] {
        get {
            let defaults = UserDefaults.standard
            let stored = defaults.array(forKey: Key.climateControls) as? [String]
                ?? defaults.array(forKey: Key.legacyControls) as? [String]
            return normalized(
                stored,
                count: 4,
                preferred: NatroLiveControl.defaultClimateControls,
                candidates: NatroLiveControl.climateCandidates
            )
        }
        set {
            let value = normalized(
                newValue.map(\.rawValue),
                count: 4,
                preferred: NatroLiveControl.defaultClimateControls,
                candidates: NatroLiveControl.climateCandidates
            )
            UserDefaults.standard.set(value.map(\.rawValue), forKey: Key.climateControls)
            notify()
        }
    }

    static var functionControls: [NatroLiveControl] {
        get {
            normalized(
                UserDefaults.standard.array(forKey: Key.functionControls) as? [String],
                count: 10,
                preferred: NatroLiveControl.defaultFunctionControls,
                candidates: NatroLiveControl.functionCandidates
            )
        }
        set {
            let value = normalized(
                newValue.map(\.rawValue),
                count: 10,
                preferred: NatroLiveControl.defaultFunctionControls,
                candidates: NatroLiveControl.functionCandidates
            )
            UserDefaults.standard.set(value.map(\.rawValue), forKey: Key.functionControls)
            notify()
        }
    }

    static func setClimateControl(_ control: NatroLiveControl, at slot: Int) {
        guard NatroLiveControl.climateCandidates.contains(control) else { return }
        var current = climateControls
        replace(control, at: slot, in: &current)
        climateControls = current
    }

    static func setFunctionControl(_ control: NatroLiveControl, at slot: Int) {
        var current = functionControls
        replace(control, at: slot, in: &current)
        functionControls = current
    }

    private static func normalized(
        _ raw: [String]?,
        count: Int,
        preferred: [NatroLiveControl],
        candidates: [NatroLiveControl]
    ) -> [NatroLiveControl] {
        var result: [NatroLiveControl] = []
        for value in raw ?? [] {
            guard let control = NatroLiveControl(rawValue: value),
                  candidates.contains(control), !result.contains(control) else { continue }
            result.append(control)
        }
        for control in preferred + candidates where result.count < count {
            if !result.contains(control) { result.append(control) }
        }
        return Array(result.prefix(count))
    }

    private static func replace(
        _ control: NatroLiveControl,
        at slot: Int,
        in current: inout [NatroLiveControl]
    ) {
        guard current.indices.contains(slot) else { return }
        if let other = current.firstIndex(of: control), other != slot {
            current.swapAt(other, slot)
        } else {
            current[slot] = control
        }
    }

    private static func notify() {
        NotificationCenter.default.post(name: changed, object: nil)
    }
}

struct NatroLiveControlSnapshot: Codable, Hashable {
    var controlID: Int
    var known: Bool
    var value: Int
    var valueText: String
    var level: Int?
    var automatic: Bool
}

@available(iOS 16.1, *)
struct NatroLiveActivityAttributes: ActivityAttributes {
    /// Optional for in-place decoding of a v55 activity whose attributes object was empty.
    var panel: NatroLivePanel?

    init(panel: NatroLivePanel? = nil) { self.panel = panel }

    var resolvedPanel: NatroLivePanel { panel ?? .climate }

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
        /// v55-compatible upper climate row.
        var controls: [NatroLiveControl]
        /// Optional so an already-running v55 activity can still decode after an in-place update.
        var functionControls: [NatroLiveControl]?
        var controlSnapshots: [NatroLiveControlSnapshot]?
        var showVehicle: Bool
        var updatedAt: Date

        func snapshot(for controlID: UInt8) -> NatroLiveControlSnapshot? {
            controlSnapshots?.first { $0.controlID == Int(controlID) }
        }

        var resolvedFunctionControls: [NatroLiveControl] {
            let source = functionControls ?? NatroLiveControl.defaultFunctionControls
            var result: [NatroLiveControl] = []
            for control in source + NatroLiveControl.defaultFunctionControls where result.count < 10 {
                if !result.contains(control) { result.append(control) }
            }
            return Array(result.prefix(10))
        }
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

    static func requiresConfirmation(_ action: String) -> Bool {
        let plain = action.hasPrefix("confirmed|")
            ? String(action.dropFirst("confirmed|".count)) : action
        let parts = plain.split(separator: ":", maxSplits: 1)
        guard parts.count == 2, let id = UInt8(parts[1]) else { return false }
        return [30, 35, 55, 56, 57, 58, 59].contains(id)
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
        var delivered = action
        if NatroLiveActivityCommandMailbox.requiresConfirmation(action) {
            try await requestConfirmation()
            delivered = "confirmed|\(action)"
        }
        NatroLiveActivityCommandMailbox.enqueue(delivered)
        return .result()
    }
}
