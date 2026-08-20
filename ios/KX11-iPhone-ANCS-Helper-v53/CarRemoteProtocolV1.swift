import Foundation

/// Fixed ATT20 codec shared with Natro. C5 carries only finite registry ids, never raw function ids.
public enum CarRemoteProtocolV1 {
    public static let frameBytes = 20
    public static let version: UInt8 = 1
    public static let magic: UInt8 = 0x4e

    public enum FrameType: UInt8 {
        case hello = 1
        case catalog = 2
        case state = 3
        case command = 4
        case result = 5
        case syncComplete = 6
    }

    public enum Operation: UInt8 {
        case none = 0
        case set = 1
        case toggle = 2
        case cycle = 3
        case activate = 4
    }

    public enum ResultCode: UInt8 {
        case ok = 0
        case rejected = 1
        case busy = 2
        case unsupported = 3
        case stale = 4
        case invalid = 5
        case timeout = 6

        var title: String {
            switch self {
            case .ok: return "Выполнено"
            case .rejected: return "Автомобиль отклонил команду"
            case .busy: return "Слишком много команд"
            case .unsupported: return "Функция недоступна"
            case .stale: return "Повтор команды заблокирован"
            case .invalid: return "Команда не поддерживается"
            case .timeout: return "Команда устарела"
            }
        }
    }

    public struct Flags {
        public static let available: UInt8 = 1
        public static let known: UInt8 = 1 << 1
        public static let active: UInt8 = 1 << 2
        public static let mechanical: UInt8 = 1 << 3
        public static let requiresConfirmation: UInt8 = 1 << 4
        public static let more: UInt8 = 1 << 5
        public static let confirmed: UInt8 = 1 << 6
        public static let error: UInt8 = 1 << 7
    }

    public struct Frame: Equatable {
        public let type: FrameType
        public let controlID: UInt8
        public let code: UInt8
        public let flags: UInt8
        public let transactionID: UInt16
        public let sequence: UInt32
        /// Exact signed integer. Each registry entry declares its public-unit scale.
        public let value: Int32
        public let maxAgeDeciseconds: UInt16
    }

    public static func encode(_ frame: Frame) -> Data {
        var bytes = [UInt8](repeating: 0, count: frameBytes)
        bytes[0] = magic
        bytes[1] = version
        bytes[2] = frame.type.rawValue
        bytes[3] = frame.controlID
        bytes[4] = frame.code
        bytes[5] = frame.flags
        put(UInt32(frame.transactionID), width: 2, at: 6, in: &bytes)
        put(frame.sequence, width: 4, at: 8, in: &bytes)
        put(UInt32(bitPattern: frame.value), width: 4, at: 12, in: &bytes)
        put(UInt32(frame.maxAgeDeciseconds), width: 2, at: 16, in: &bytes)
        let checksum = crc16(bytes[0..<18])
        bytes[18] = UInt8(truncatingIfNeeded: checksum)
        bytes[19] = UInt8(truncatingIfNeeded: checksum >> 8)
        return Data(bytes)
    }

    public static func decode(_ data: Data) -> Frame? {
        let bytes = [UInt8](data)
        guard bytes.count == frameBytes, bytes[0] == magic, bytes[1] == version,
              let type = FrameType(rawValue: bytes[2]),
              UInt16(bytes[18]) | UInt16(bytes[19]) << 8 == crc16(bytes[0..<18]) else {
            return nil
        }
        let frame = Frame(
            type: type,
            controlID: bytes[3],
            code: bytes[4],
            flags: bytes[5],
            transactionID: UInt16(read(bytes, at: 6, width: 2)),
            sequence: read(bytes, at: 8, width: 4),
            value: Int32(bitPattern: read(bytes, at: 12, width: 4)),
            maxAgeDeciseconds: UInt16(read(bytes, at: 16, width: 2))
        )
        guard frame.sequence != 0 else { return nil }
        switch type {
        case .hello, .syncComplete:
            guard frame.controlID == 0, frame.code == 0, frame.flags == 0,
                  frame.transactionID == 0, frame.value == 0,
                  frame.maxAgeDeciseconds == 0 else { return nil }
        case .catalog:
            let allowed = Flags.available | Flags.mechanical |
                Flags.requiresConfirmation | Flags.more
            guard frame.controlID != 0, (1...5).contains(frame.code),
                  frame.flags & ~allowed == 0, frame.transactionID == 0,
                  frame.value == 0, frame.maxAgeDeciseconds == 0 else { return nil }
        case .state:
            let allowed = Flags.available | Flags.known | Flags.active |
                Flags.mechanical | Flags.requiresConfirmation
            guard frame.controlID != 0, frame.code == 0,
                  frame.flags & ~allowed == 0, frame.transactionID == 0,
                  frame.maxAgeDeciseconds == 0 else { return nil }
        case .command:
            guard frame.controlID != 0, frame.transactionID != 0,
                  let operation = Operation(rawValue: frame.code), operation != .none,
                  frame.flags & ~Flags.confirmed == 0,
                  (1...50).contains(frame.maxAgeDeciseconds) else { return nil }
        case .result:
            guard frame.controlID != 0, frame.transactionID != 0,
                  ResultCode(rawValue: frame.code) != nil,
                  (frame.code == ResultCode.ok.rawValue
                    ? frame.flags == 0 : frame.flags == Flags.error),
                  frame.maxAgeDeciseconds == 0 else { return nil }
        }
        return frame
    }

    public static func crc16<C: Collection>(_ bytes: C) -> UInt16 where C.Element == UInt8 {
        var crc: UInt16 = 0xffff
        for byte in bytes {
            crc ^= UInt16(byte) << 8
            for _ in 0..<8 {
                crc = crc & 0x8000 != 0 ? (crc << 1) ^ 0x1021 : crc << 1
            }
        }
        return crc
    }

    private static func put(_ value: UInt32, width: Int, at offset: Int,
                            in bytes: inout [UInt8]) {
        for index in 0..<width {
            bytes[offset + index] = UInt8(truncatingIfNeeded: value >> UInt32(index * 8))
        }
    }

    private static func read(_ bytes: [UInt8], at offset: Int, width: Int) -> UInt32 {
        var value: UInt32 = 0
        for index in 0..<width {
            value |= UInt32(bytes[offset + index]) << UInt32(index * 8)
        }
        return value
    }
}

public struct CarRemoteControlDefinition: Equatable {
    public enum Section: String, CaseIterable {
        case climate = "Климат"
        case seats = "Сиденья"
        case media = "Медиа"
        case comfort = "Комфорт"
    }

    public enum Kind: Equatable { case toggle, levels, options, range, action }

    public let id: UInt8
    public let title: String
    public let section: Section
    public let kind: Kind
    public let scale: Int32
    public let minimum: Int32
    public let maximum: Int32
    public let step: Int32
    public let unit: String
    public let directValues: [(Int32, String)]
    public let requiresConfirmation: Bool

    public static func == (lhs: Self, rhs: Self) -> Bool { lhs.id == rhs.id }

    public func publicValue(_ wire: Int32) -> Double {
        Double(wire) / Double(scale)
    }

    public func displayValue(_ wire: Int32) -> String {
        if let exact = directValues.first(where: { $0.0 == wire }) { return exact.1 }
        if id == 10 && wire == 0 { return "Выкл" }
        if id == 10 && wire == Int32(0x10070108) { return "Auto" }
        let value = publicValue(wire)
        if scale == 1 { return "\(wire)\(unit)" }
        return String(format: value.rounded() == value ? "%.0f%@" : "%.1f%@", value, unit)
    }
}

public enum CarRemoteCatalogV1 {
    private static let offOn: [(Int32, String)] = [(0, "Выкл"), (1, "Вкл")]
    private static let manualFan: [(Int32, String)] = [(0, "Выкл")] + (1...9).map {
        (Int32(0x10020100 + $0), "\($0)")
    }
    private static let frontFan: [(Int32, String)] = manualFan + [
        (Int32(0x10020204), "AUTO · тише"),
        (Int32(0x10020201), "AUTO · тихо"),
        (Int32(0x10020202), "AUTO · обычно"),
        (Int32(0x10020203), "AUTO · интенсивно"),
        (Int32(0x10020205), "AUTO · выше")
    ]
    private static let circulation: [(Int32, String)] = [
        (0, "Выкл"),
        (Int32(0x10030101), "Рециркуляция"),
        (Int32(0x10030102), "Свежий воздух"),
        (Int32(0x10030103), "Auto")
    ]
    private static let heat: [(Int32, String)] = [
        (0, "Выкл"), (Int32(0x10050201), "1"),
        (Int32(0x10050202), "2"), (Int32(0x10050203), "3"),
        (Int32(0x1005020f), "Auto")
    ]
    private static let ventilation: [(Int32, String)] = [
        (0, "Выкл"), (Int32(0x10050101), "1"),
        (Int32(0x10050102), "2"), (Int32(0x10050103), "3"),
        (Int32(0x1005010f), "Auto")
    ]
    private static let steeringHeat: [(Int32, String)] = [
        (0, "Выкл"), (Int32(0x10090101), "1"),
        (Int32(0x10090102), "2"), (Int32(0x10090103), "3"),
        (Int32(0x1009010f), "Auto")
    ]
    private static let airflow: [(Int32, String)] = [
        (Int32(0x10070101), "Лицо"),
        (Int32(0x10070102), "Ноги"), (Int32(0x10070103), "Лицо + ноги"),
        (Int32(0x10070104), "Стекло"), (Int32(0x10070105), "Лицо + стекло"),
        (Int32(0x10070106), "Ноги + стекло"),
        (Int32(0x10070107), "Лицо + ноги + стекло")
    ]
    private static let driveModes: [(Int32, String)] = [
        (Int32(0x22010101), "Eco"), (Int32(0x22010102), "Comfort"),
        (Int32(0x22010103), "Dynamic"), (Int32(0x22010104), "XC"),
        (Int32(0x22010105), "HDC"), (Int32(0x22010106), "Pure"),
        (Int32(0x22010107), "Hybrid"), (Int32(0x22010108), "Power"),
        (Int32(0x22010109), "Snow"), (Int32(0x2201010a), "Mud"),
        (Int32(0x2201010b), "Rock"), (Int32(0x2201010c), "PHEV"),
        (Int32(0x2201010d), "Sand"), (Int32(0x2201010e), "AWD"),
        (Int32(0x2201010f), "Save"), (Int32(0x22010110), "Eco HEV"),
        (Int32(0x22010111), "Normal"), (Int32(0x22010112), "eAWD"),
        (Int32(0x22010113), "Offroad"), (Int32(0x22010116), "Adaptive"),
        (Int32(0x22010140), "Custom")
    ]

    public static let controls: [CarRemoteControlDefinition] = [
        toggle(1, "Климат", .climate), toggle(2, "Кондиционер", .climate),
        toggle(3, "AUTO", .climate), toggle(4, "Обогрев лобового", .climate),
        toggle(5, "Обдув лобового MAX", .climate),
        toggle(6, "Обогрев заднего стекла", .climate),
        toggle(7, "SYNC", .climate), options(8, "Рециркуляция", .climate, circulation),
        options(9, "Вентилятор", .climate, frontFan),
        options(10, "Направление обдува", .climate, airflow),
        range(11, "Температура водителя", .climate, 1600, 3000, 50, 100, "°C"),
        range(12, "Температура пассажира", .climate, 1600, 3000, 50, 100, "°C"),
        toggle(13, "Задний климат", .climate), toggle(14, "AUTO задней зоны", .climate),
        options(15, "Задний вентилятор", .climate, manualFan),
        range(16, "Сзади слева", .climate, 1600, 3000, 50, 100, "°C"),
        range(17, "Сзади справа", .climate, 1600, 3000, 50, 100, "°C"),
        toggle(18, "Блокировка задней панели", .climate),
        options(20, "Подогрев водителя", .seats, heat),
        options(21, "Подогрев пассажира", .seats, heat),
        options(22, "Вентиляция водителя", .seats, ventilation),
        options(23, "Вентиляция пассажира", .seats, ventilation),
        options(24, "Подогрев руля", .seats, steeringHeat),
        toggle(30, "Багажник", .comfort, confirmation: true),
        options(31, "Режим движения", .comfort, driveModes),
        toggle(32, "Auto Hold", .comfort),
        toggle(33, "Start/Stop", .comfort), toggle(34, "Экономия топлива", .comfort),
        action(35, "Сервисное положение дворников", .comfort, confirmation: true),
        toggle(40, "Атмосферная подсветка", .comfort),
        range(41, "Яркость подсветки", .comfort, 0, 10000, 500, 100, "%"),
        toggle(42, "Экран пассажира", .comfort),
        range(43, "Дневная яркость экрана", .comfort, 0, 10000, 500, 100, "%"),
        range(44, "Ночная яркость экрана", .comfort, 0, 10000, 500, 100, "%"),
        action(50, "Воспроизведение / пауза", .media), action(51, "Следующий трек", .media),
        action(52, "Предыдущий трек", .media), action(53, "Без звука", .media),
        range(54, "Громкость", .media, 0, 10000, 500, 100, "%")
    ]

    public static let byID = Dictionary(uniqueKeysWithValues: controls.map { ($0.id, $0) })

    private static func toggle(_ id: UInt8, _ title: String,
                               _ section: CarRemoteControlDefinition.Section,
                               confirmation: Bool = false) -> CarRemoteControlDefinition {
        .init(id: id, title: title, section: section, kind: .toggle, scale: 1,
              minimum: 0, maximum: 1, step: 1, unit: "", directValues: offOn,
              requiresConfirmation: confirmation)
    }

    private static func options(_ id: UInt8, _ title: String,
                                _ section: CarRemoteControlDefinition.Section,
                                _ values: [(Int32, String)]) -> CarRemoteControlDefinition {
        .init(id: id, title: title, section: section, kind: .levels, scale: 1,
              minimum: 0, maximum: 0, step: 0, unit: "", directValues: values,
              requiresConfirmation: false)
    }

    private static func cycle(_ id: UInt8, _ title: String,
                              _ section: CarRemoteControlDefinition.Section) -> CarRemoteControlDefinition {
        .init(id: id, title: title, section: section, kind: .options, scale: 1,
              minimum: 0, maximum: 0, step: 0, unit: "", directValues: [],
              requiresConfirmation: false)
    }

    private static func range(_ id: UInt8, _ title: String,
                              _ section: CarRemoteControlDefinition.Section,
                              _ minimum: Int32, _ maximum: Int32, _ step: Int32,
                              _ scale: Int32, _ unit: String) -> CarRemoteControlDefinition {
        .init(id: id, title: title, section: section, kind: .range, scale: scale,
              minimum: minimum, maximum: maximum, step: step, unit: unit, directValues: [],
              requiresConfirmation: false)
    }

    private static func action(_ id: UInt8, _ title: String,
                               _ section: CarRemoteControlDefinition.Section,
                               confirmation: Bool = false) -> CarRemoteControlDefinition {
        .init(id: id, title: title, section: section, kind: .action, scale: 1,
              minimum: 0, maximum: 1, step: 1, unit: "", directValues: [],
              requiresConfirmation: confirmation)
    }
}

public final class CarRemoteClient {
    public struct State: Equatable {
        public let available: Bool
        public let known: Bool
        public let active: Bool
        public let value: Int32
    }

    public struct SceneCommand {
        public let controlID: UInt8
        public let operation: CarRemoteProtocolV1.Operation
        public let value: Int32
    }

    public var onChange: (() -> Void)?
    public private(set) var isSynced = false
    public private(set) var statusText = "Ожидание защищённого Bluetooth‑канала…"
    public private(set) var available = Set<UInt8>()
    public private(set) var states: [UInt8: State] = [:]

    private let sender: (Data) -> Void
    private var sequence = UInt32.random(in: 1...(UInt32.max - 1))
    private var transaction: UInt16 = 0
    private var retryTimer: Timer?
    private var transportReady = false
    private var started = false
    private var pendingResults: [UInt16: PendingResult] = [:]
    private var sceneGeneration: UInt64 = 0

    private struct PendingResult {
        let controlID: UInt8
        let completion: (CarRemoteProtocolV1.ResultCode) -> Void
    }

    public init(sender: @escaping (Data) -> Void) {
        self.sender = sender
    }

    deinit { retryTimer?.invalidate() }

    public func start() {
        dispatchPrecondition(condition: .onQueue(.main))
        started = true
        retryTimer?.invalidate()
        retryTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) {
            [weak self] _ in
            guard let self, self.transportReady, !self.isSynced else { return }
            self.sendHello()
        }
        if transportReady { sendHello() }
    }

    public func setTransportReady(_ ready: Bool) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let changed = self.transportReady != ready
            self.transportReady = ready
            if !changed && (ready || !self.isSynced) { return }
            self.isSynced = false
            self.available.removeAll()
            self.states.removeAll()
            self.pendingResults.removeAll()
            self.sceneGeneration &+= 1
            self.statusText = ready
                ? "Синхронизация функций автомобиля…"
                : "Ожидание защищённого Bluetooth‑канала…"
            self.onChange?()
            if ready && self.started { self.sendHello() }
        }
    }

    public func invalidate() { setTransportReady(false) }

    public func accept(_ data: Data) {
        guard let frame = CarRemoteProtocolV1.decode(data) else { return }
        DispatchQueue.main.async { [weak self] in self?.acceptOnMain(frame) }
    }

    public func send(
        controlID: UInt8,
        operation: CarRemoteProtocolV1.Operation,
        value: Int32,
        confirmed: Bool = false,
        completion: ((CarRemoteProtocolV1.ResultCode) -> Void)? = nil
    ) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard isSynced, available.contains(controlID), operation != .none else {
            statusText = "Функция пока недоступна"
            onChange?()
            completion?(.unsupported)
            return
        }
        transaction &+= 1
        if transaction == 0 { transaction = 1 }
        var flags: UInt8 = 0
        if confirmed { flags |= CarRemoteProtocolV1.Flags.confirmed }
        let exactTransaction = transaction
        sender(CarRemoteProtocolV1.encode(.init(
            type: .command, controlID: controlID, code: operation.rawValue,
            flags: flags, transactionID: exactTransaction, sequence: nextSequence(),
            value: value, maxAgeDeciseconds: 30
        )))
        if let completion {
            pendingResults[exactTransaction] = PendingResult(
                controlID: controlID,
                completion: completion
            )
            DispatchQueue.main.asyncAfter(deadline: .now() + 7) { [weak self] in
                guard let self,
                      let pending = self.pendingResults.removeValue(
                        forKey: exactTransaction
                      ) else { return }
                self.statusText = "Автомобиль не ответил на команду"
                self.onChange?()
                pending.completion(.timeout)
            }
        }
        statusText = "Команда отправлена по Bluetooth"
        onChange?()
    }

    public func runScene(_ commands: [SceneCommand]) {
        let supported = commands.filter { available.contains($0.controlID) }
        guard !supported.isEmpty else {
            statusText = "Команды сценария пока недоступны"
            onChange?()
            return
        }
        sceneGeneration &+= 1
        let exactGeneration = sceneGeneration
        sendSceneCommand(supported, index: 0, generation: exactGeneration)
    }

    private func sendSceneCommand(
        _ commands: [SceneCommand],
        index: Int,
        generation: UInt64
    ) {
        guard generation == sceneGeneration else { return }
        guard index < commands.count else {
            statusText = "Сценарий выполнен"
            onChange?()
            return
        }
        let command = commands[index]
        send(controlID: command.controlID, operation: command.operation,
             value: command.value) { [weak self] result in
            guard let self, generation == self.sceneGeneration else { return }
            guard result.rawValue == CarRemoteProtocolV1.ResultCode.ok.rawValue else {
                self.statusText = "Сценарий остановлен: \(result.title)"
                self.onChange?()
                return
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
                self?.sendSceneCommand(commands, index: index + 1, generation: generation)
            }
        }
    }

    private func sendHello() {
        guard transportReady else { return }
        sender(CarRemoteProtocolV1.encode(.init(
            type: .hello, controlID: 0, code: 0, flags: 0, transactionID: 0,
            sequence: sequence, value: 0, maxAgeDeciseconds: 0
        )))
    }

    private func acceptOnMain(_ frame: CarRemoteProtocolV1.Frame) {
        switch frame.type {
        case .catalog:
            if frame.flags & CarRemoteProtocolV1.Flags.available != 0 {
                available.insert(frame.controlID)
            } else {
                available.remove(frame.controlID)
            }
        case .state:
            states[frame.controlID] = State(
                available: frame.flags & CarRemoteProtocolV1.Flags.available != 0,
                known: frame.flags & CarRemoteProtocolV1.Flags.known != 0,
                active: frame.flags & CarRemoteProtocolV1.Flags.active != 0,
                value: frame.value
            )
        case .result:
            let result = CarRemoteProtocolV1.ResultCode(rawValue: frame.code)
            statusText = result?.title ?? "Неизвестный ответ автомобиля"
            if let result, let pending = pendingResults[frame.transactionID],
               pending.controlID == frame.controlID {
                pendingResults.removeValue(forKey: frame.transactionID)
                pending.completion(result)
            }
        case .syncComplete:
            isSynced = true
            statusText = "Автомобиль подключён по Bluetooth"
        default:
            break
        }
        onChange?()
    }

    private func nextSequence() -> UInt32 {
        sequence &+= 1
        if sequence == 0 { sequence = 1 }
        return sequence
    }
}
