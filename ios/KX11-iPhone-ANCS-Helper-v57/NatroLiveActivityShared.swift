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
    case readingLampFrontLeft
    case readingLampFrontRight
    case readingLampRearLeft
    case readingLampRearRight
    case readingLampsAll
    case fragranceLevel
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
        case .readingLampFrontLeft: return "Лампа водит."
        case .readingLampFrontRight: return "Лампа пасс."
        case .readingLampRearLeft: return "Лампа сзади Л"
        case .readingLampRearRight: return "Лампа сзади П"
        case .readingLampsAll: return "Все лампы"
        case .fragranceLevel: return "Аромат"
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
        case .readingLampFrontLeft: return "Лампа спереди слева"
        case .readingLampFrontRight: return "Лампа спереди справа"
        case .readingLampRearLeft: return "Лампа сзади слева"
        case .readingLampRearRight: return "Лампа сзади справа"
        case .readingLampsAll: return "Все лампы салона"
        case .fragranceLevel: return "Уровень ароматизатора"
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
        case .readingLampFrontLeft, .readingLampFrontRight,
             .readingLampRearLeft, .readingLampRearRight,
             .readingLampsAll: return "lightbulb.2"
        case .fragranceLevel: return "aqi.medium"
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
             .ambientTheme, .passengerScreen, .readingLampFrontLeft,
             .readingLampFrontRight, .readingLampRearLeft, .readingLampRearRight,
             .readingLampsAll, .fragranceLevel:
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
        case .readingLampFrontLeft: return 60
        case .readingLampFrontRight: return 61
        case .readingLampRearLeft: return 62
        case .readingLampRearRight: return 63
        case .readingLampsAll: return 64
        case .fragranceLevel: return 65
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
             .ambientMode, .ambientEffect, .ambientColor, .ambientTheme,
             .fragranceLevel:
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
        case .fragranceLevel:
            return true
        default:
            return false
        }
    }

    var isVentilation: Bool {
        self == .driverSeatVentilation || self == .passengerSeatVentilation
            || self == .rearLeftSeatVentilation || self == .rearRightSeatVentilation
    }

    /// Short, truthful state text for the Lock Screen. Finite controls must not collapse to the
    /// same generic on/off caption used by ordinary switches.
    func compactValue(_ value: Int32, active: Bool) -> String {
        let suffix = Int(value & 0xff)
        switch self {
        case .recirculation:
            return [1: "В салоне", 2: "С улицы", 3: "Auto"][suffix]
                ?? (active ? "Включено" : "Выключено")
        case .fan, .rearFan:
            if value >> 8 == Int32(0x100202) { return "Auto \(suffix)" }
            return value == 0 ? "Выключено" : "Скорость \(suffix)"
        case .airflow:
            return [1: "Лицо", 2: "Ноги", 3: "Лицо + ноги", 4: "Стекло",
                    5: "Лицо + стекло", 6: "Ноги + стекло", 7: "Все"][suffix]
                ?? "Режим \(suffix)"
        case .driveMode:
            return [1: "Eco", 2: "Comfort", 3: "Dynamic", 4: "XC", 9: "Snow",
                    10: "Mud", 11: "Rock", 13: "Sand", 19: "Offroad",
                    22: "Adaptive", 64: "Custom"][suffix] ?? "Режим \(suffix)"
        case .ambientMode:
            return suffix == 4 ? "Музыка" : "Свой цвет"
        case .ambientEffect:
            return [1: "Статичный", 2: "Градиент", 3: "Дыхание"][suffix]
                ?? "Эффект \(suffix)"
        case .ambientColor:
            return [1: "Красный", 2: "Оранжевый", 3: "Жёлтый", 4: "Зелёный",
                    5: "Индиго", 6: "Синий", 7: "Фиолетовый", 8: "Белый",
                    9: "Ледяной", 10: "Закатный", 11: "Гранатовый",
                    12: "Лаймовый", 13: "Розовый"][suffix] ?? "Цвет \(suffix)"
        case .ambientTheme:
            return [1: "Динамичный", 2: "Спокойный", 3: "Свободный", 4: "Живой",
                    5: "Модный", 6: "Электро"][suffix] ?? "Тема \(suffix)"
        case .wiperService, .closeDriverWindow, .closePassengerWindow,
             .closeRearLeftWindow, .closeRearRightWindow, .closeSunroof,
             .mediaPlayPause, .mediaNext, .mediaPrevious, .mediaMute:
            return active ? "Выполнено" : "Готово"
        case .trunk:
            return active ? "Открыт" : "Закрыт"
        default:
            return active ? "Включено" : "Выключено"
        }
    }

    static let defaultClimateControls: [NatroLiveControl] = [
        .climatePower, .airConditioning, .automaticClimate, .frontDefrost
    ]

    static let defaultFunctionControls: [NatroLiveControl] = [
        .driverSeatHeat, .driverSeatVentilation, .rearDefrost, .readingLampsAll
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
        static let functionControls = "liveActivity.functionControls.v57"
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
                count: 4,
                preferred: NatroLiveControl.defaultFunctionControls,
                candidates: NatroLiveControl.functionCandidates
            )
        }
        set {
            let value = normalized(
                newValue.map(\.rawValue),
                count: 4,
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

struct NatroLiveControlSnapshot: Hashable {
    var known: Bool
    var value: Int32
    var level: Int?
    var automatic: Bool
}

@available(iOS 16.1, *)
struct NatroLiveActivityAttributes: ActivityAttributes {
    /// All presentation choices are static. They are encoded once when ActivityKit creates the
    /// card and never consume the 4 KB dynamic-state budget on every BLE update.
    var panel: NatroLivePanel?
    var controlIDs: [UInt8]?
    var vehicleName: String?
    var showVehicle: Bool?

    init(
        panel: NatroLivePanel? = nil,
        controlIDs: [UInt8]? = nil,
        vehicleName: String? = nil,
        showVehicle: Bool? = nil
    ) {
        self.panel = panel
        self.controlIDs = controlIDs
        self.vehicleName = vehicleName
        self.showVehicle = showVehicle
    }

    var resolvedPanel: NatroLivePanel { panel ?? .climate }
    var resolvedControls: [NatroLiveControl] {
        let fallback = resolvedPanel == .climate
            ? NatroLiveControl.defaultClimateControls
            : Array(NatroLiveControl.defaultFunctionControls.prefix(4))
        var result: [NatroLiveControl] = []
        for id in controlIDs ?? fallback.map(\.controlID) {
            guard let control = NatroLiveControl.allCases.first(where: { $0.controlID == id }),
                  !result.contains(control) else { continue }
            result.append(control)
        }
        for control in fallback where result.count < 4 && !result.contains(control) {
            result.append(control)
        }
        return Array(result.prefix(4))
    }
    var resolvedVehicleName: String {
        let clean = vehicleName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return clean.isEmpty ? "GEELY MONJARO" : String(clean.prefix(32))
    }
    var resolvedShowVehicle: Bool { showVehicle ?? true }

    struct ContentState: Codable, Hashable {
        /// 0 waiting, 1 synchronising, 2 disconnected, 3 connected, 4 demo.
        var statusCode: UInt8
        var targetTemperatureHundredths: Int16?
        var cabinTemperatureTenths: Int16?
        var outdoorTemperatureTenths: Int16?
        /// Values align with the four static control IDs in the attributes object.
        var values: [Int32]
        /// Bit 0 available, bit 1 known, bit 2 active; one byte per value.
        var valueFlags: [UInt8]
        var updatedAtEpoch: UInt32
        /// Decode-only bridge for a card left running by Helper 56. Never encoded by Helper 57.
        private var legacyValues: [Int: LegacyValue]?

        private struct LegacyValue: Codable, Hashable {
            var controlID: Int
            var known: Bool
            var value: Int
            var valueText: String?
            var level: Int?
            var automatic: Bool
        }

        private enum CodingKeys: String, CodingKey {
            case statusCode = "s", targetTemperatureHundredths = "t"
            case cabinTemperatureTenths = "c", outdoorTemperatureTenths = "o"
            case values = "v", valueFlags = "f", updatedAtEpoch = "u"
            // Helper 55/56 decode-only keys.
            case status, ancsConnected, isDemo, activeControlIDs, availableControlIDs
            case controlSnapshots, updatedAt
            case legacyTargetTemperatureHundredths = "targetTemperatureHundredths"
            case legacyCabinTemperatureTenths = "cabinTemperatureTenths"
            case legacyOutdoorTemperatureTenths = "outdoorTemperatureTenths"
        }

        init(
            statusCode: UInt8,
            targetTemperatureHundredths: Int16?,
            cabinTemperatureTenths: Int16?,
            outdoorTemperatureTenths: Int16?,
            values: [Int32],
            valueFlags: [UInt8],
            updatedAtEpoch: UInt32
        ) {
            self.statusCode = statusCode
            self.targetTemperatureHundredths = targetTemperatureHundredths
            self.cabinTemperatureTenths = cabinTemperatureTenths
            self.outdoorTemperatureTenths = outdoorTemperatureTenths
            self.values = Array(values.prefix(4))
            self.valueFlags = Array(valueFlags.prefix(4))
            self.updatedAtEpoch = updatedAtEpoch
            self.legacyValues = nil
        }

        init(from decoder: Decoder) throws {
            let box = try decoder.container(keyedBy: CodingKeys.self)
            if box.contains(.statusCode) {
                statusCode = try box.decode(UInt8.self, forKey: .statusCode)
                targetTemperatureHundredths = try box.decodeIfPresent(
                    Int16.self, forKey: .targetTemperatureHundredths)
                cabinTemperatureTenths = try box.decodeIfPresent(
                    Int16.self, forKey: .cabinTemperatureTenths)
                outdoorTemperatureTenths = try box.decodeIfPresent(
                    Int16.self, forKey: .outdoorTemperatureTenths)
                values = try box.decode([Int32].self, forKey: .values)
                valueFlags = try box.decode([UInt8].self, forKey: .valueFlags)
                updatedAtEpoch = try box.decode(UInt32.self, forKey: .updatedAtEpoch)
                legacyValues = nil
                return
            }
            let demo = try box.decodeIfPresent(Bool.self, forKey: .isDemo) ?? false
            let connected = try box.decodeIfPresent(Bool.self, forKey: .ancsConnected) ?? false
            let text = try box.decodeIfPresent(String.self, forKey: .status) ?? ""
            statusCode = demo ? 4 : connected ? 3 : text.contains("отключ") ? 2 : 0
            targetTemperatureHundredths = Self.int16(
                try box.decodeIfPresent(Int.self, forKey: .legacyTargetTemperatureHundredths))
            cabinTemperatureTenths = Self.int16(
                try box.decodeIfPresent(Int.self, forKey: .legacyCabinTemperatureTenths))
            outdoorTemperatureTenths = Self.int16(
                try box.decodeIfPresent(Int.self, forKey: .legacyOutdoorTemperatureTenths))
            values = []
            valueFlags = []
            let old = try box.decodeIfPresent([LegacyValue].self, forKey: .controlSnapshots) ?? []
            legacyValues = Dictionary(uniqueKeysWithValues: old.map { ($0.controlID, $0) })
            let date = try box.decodeIfPresent(Date.self, forKey: .updatedAt) ?? Date()
            updatedAtEpoch = UInt32(clamping: Int64(date.timeIntervalSince1970))
        }

        func encode(to encoder: Encoder) throws {
            var box = encoder.container(keyedBy: CodingKeys.self)
            try box.encode(statusCode, forKey: .statusCode)
            try box.encodeIfPresent(targetTemperatureHundredths,
                                    forKey: .targetTemperatureHundredths)
            try box.encodeIfPresent(cabinTemperatureTenths,
                                    forKey: .cabinTemperatureTenths)
            try box.encodeIfPresent(outdoorTemperatureTenths,
                                    forKey: .outdoorTemperatureTenths)
            try box.encode(values, forKey: .values)
            try box.encode(valueFlags, forKey: .valueFlags)
            try box.encode(updatedAtEpoch, forKey: .updatedAtEpoch)
        }

        var isDemo: Bool { statusCode == 4 }
        var ancsConnected: Bool { statusCode == 3 || statusCode == 4 }
        var status: String {
            switch statusCode {
            case 4: return "ДЕМО · ANCS подключён"
            case 3: return "ANCS подключён"
            case 2: return "ANCS отключён"
            case 1: return "Состояние ANCS уточняется"
            default: return "Ожидание Natro"
            }
        }

        func snapshot(for controlID: UInt8, at index: Int) -> NatroLiveControlSnapshot? {
            if let legacy = legacyValues?[Int(controlID)] {
                return NatroLiveControlSnapshot(
                    known: legacy.known,
                    value: Int32(clamping: legacy.value),
                    level: legacy.level,
                    automatic: legacy.automatic
                )
            }
            guard values.indices.contains(index), valueFlags.indices.contains(index) else {
                return nil
            }
            let flags = valueFlags[index]
            let value = values[index]
            let suffix = Int(value & 0xff)
            return NatroLiveControlSnapshot(
                known: flags & 0x02 != 0,
                value: value,
                level: suffix == 0x0f ? 3 : ((1...3).contains(suffix) ? suffix : 0),
                automatic: suffix == 0x0f
            )
        }

        func isAvailable(at index: Int) -> Bool {
            guard valueFlags.indices.contains(index) else { return legacyValues != nil }
            return valueFlags[index] & 0x01 != 0
        }

        func isActive(at index: Int) -> Bool {
            guard valueFlags.indices.contains(index) else { return false }
            return valueFlags[index] & 0x04 != 0
        }

        private static func int16(_ value: Int?) -> Int16? {
            value.map { Int16(clamping: $0) }
        }
    }
}

enum NatroLiveActivityCommandMailbox {
    static let suiteName = "group.ru.natro.kx11ancshelper"
    static let notification = "ru.natro.kx11ancshelper.live-command" as CFString
    private static let actionKey = "liveCommand.action"
    private static let nonceKey = "liveCommand.nonce"

    private static func defaults() -> UserDefaults {
        // A development provisioning profile may not contain the App Group yet. Calling the
        // suite initializer in that state produces the CFPrefs AnyUser/container warning seen in
        // Xcode. The app-domain fallback keeps previews/demo deterministic without pretending it
        // can bridge a command between two independently sandboxed processes.
        guard FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: suiteName
        ) != nil else { return .standard }
        return UserDefaults(suiteName: suiteName) ?? .standard
    }

    static func enqueue(_ action: String) {
        guard !action.isEmpty else { return }
        let defaults = defaults()
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
        let defaults = defaults()
        guard let action = defaults.string(forKey: actionKey), !action.isEmpty else { return nil }
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
