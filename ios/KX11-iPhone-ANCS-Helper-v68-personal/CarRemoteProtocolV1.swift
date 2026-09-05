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

/// Chunked ATT20 sideband for ActivityKit tokens/configuration. It rides only inside the same
/// encrypted and mutually-bound C5 characteristic as vehicle commands, but a distinct magic makes
/// cross-protocol interpretation impossible.
public enum LiveActivityPushProtocolV1 {
    public static let frameBytes = 20
    public static let version: UInt8 = 1
    public static let magic: UInt8 = 0x50
    public static let payloadBytes = 8
    public static let maximumChunks = 32

    public enum MessageType: UInt8 {
        case pushToStartToken = 1
        case configuration = 2
        case activityPushToken = 3
        case activityEnded = 4
    }

    public struct Frame: Equatable {
        public let type: MessageType
        public let messageID: UInt16
        public let chunkIndex: UInt8
        public let chunkCount: UInt8
        public let payload: Data
    }

    public static func frames(
        type: MessageType,
        messageID: UInt16,
        payload: Data
    ) -> [Data] {
        guard messageID != 0, !payload.isEmpty,
              payload.count <= payloadBytes * maximumChunks else { return [] }
        let count = (payload.count + payloadBytes - 1) / payloadBytes
        return (0..<count).map { index in
            let start = index * payloadBytes
            let end = min(payload.count, start + payloadBytes)
            return encode(.init(
                type: type,
                messageID: messageID,
                chunkIndex: UInt8(index),
                chunkCount: UInt8(count),
                payload: payload.subdata(in: start..<end)
            ))
        }
    }

    public static func encode(_ frame: Frame) -> Data {
        guard frame.messageID != 0,
              frame.chunkCount > 0, Int(frame.chunkCount) <= maximumChunks,
              frame.chunkIndex < frame.chunkCount,
              !frame.payload.isEmpty, frame.payload.count <= payloadBytes,
              frame.chunkIndex + 1 == frame.chunkCount
                || frame.payload.count == payloadBytes else { return Data() }
        var bytes = [UInt8](repeating: 0, count: frameBytes)
        bytes[0] = magic
        bytes[1] = version
        bytes[2] = frame.type.rawValue
        bytes[4] = UInt8(truncatingIfNeeded: frame.messageID)
        bytes[5] = UInt8(truncatingIfNeeded: frame.messageID >> 8)
        bytes[6] = frame.chunkIndex
        bytes[7] = frame.chunkCount
        bytes[8] = UInt8(frame.payload.count)
        for (offset, byte) in frame.payload.enumerated() { bytes[10 + offset] = byte }
        let checksum = CarRemoteProtocolV1.crc16(bytes[0..<18])
        bytes[18] = UInt8(truncatingIfNeeded: checksum)
        bytes[19] = UInt8(truncatingIfNeeded: checksum >> 8)
        return Data(bytes)
    }

    public static func decode(_ data: Data) -> Frame? {
        let bytes = [UInt8](data)
        guard bytes.count == frameBytes, bytes[0] == magic, bytes[1] == version,
              bytes[3] == 0, bytes[9] == 0,
              let type = MessageType(rawValue: bytes[2]),
              UInt16(bytes[18]) | UInt16(bytes[19]) << 8
                == CarRemoteProtocolV1.crc16(bytes[0..<18]) else { return nil }
        let messageID = UInt16(bytes[4]) | UInt16(bytes[5]) << 8
        let index = bytes[6]
        let count = bytes[7]
        let length = Int(bytes[8])
        guard messageID != 0, count > 0, Int(count) <= maximumChunks, index < count,
              length > 0, length <= payloadBytes,
              index + 1 == count || length == payloadBytes,
              bytes[(10 + length)..<18].allSatisfy({ $0 == 0 }) else { return nil }
        return .init(type: type, messageID: messageID, chunkIndex: index,
                     chunkCount: count, payload: Data(bytes[10..<(10 + length)]))
    }
}

public enum AuthenticatedC5FrameV1 {
    public static func isValid(_ data: Data) -> Bool {
        CarRemoteProtocolV1.decode(data) != nil || LiveActivityPushProtocolV1.decode(data) != nil
    }
}

public struct CarRemoteControlDefinition: Equatable {
    public enum Section: String, CaseIterable {
        case climate = "Климат"
        case seats = "Сиденья"
        case vehicle = "Автомобиль"
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
    private static let ambientModes: [(Int32, String)] = [
        (Int32(0x200a0203), "Свой цвет"), (Int32(0x200a0204), "В ритм музыке")
    ]
    private static let ambientEffects: [(Int32, String)] = [
        (Int32(0x2a080101), "Статичный"), (Int32(0x2a080102), "Градиент"),
        (Int32(0x2a080103), "Дыхание")
    ]
    private static let ambientColors: [(Int32, String)] = [
        (Int32(0x2a010201), "Красный"), (Int32(0x2a01020a), "Закатный"),
        (Int32(0x2a01020b), "Гранатовый"), (Int32(0x2a010202), "Оранжевый"),
        (Int32(0x2a010203), "Жёлтый"), (Int32(0x2a010204), "Зелёный"),
        (Int32(0x2a01020c), "Лаймовый"), (Int32(0x2a010209), "Ледяной"),
        (Int32(0x2a010206), "Синий"), (Int32(0x2a010205), "Индиго"),
        (Int32(0x2a010207), "Фиолетовый"), (Int32(0x2a01020d), "Розовый"),
        (Int32(0x2a010208), "Белый")
    ]
    private static let ambientThemes: [(Int32, String)] = [
        (Int32(0x2a080101), "Динамичный"), (Int32(0x2a080102), "Спокойный"),
        (Int32(0x2a080103), "Свободный"), (Int32(0x2a080104), "Живой"),
        (Int32(0x2a080105), "Модный"), (Int32(0x2a080106), "Электро")
    ]
    private static let fragranceLevels: [(Int32, String)] = [
        (0, "Выкл"), (1, "Слабый"), (2, "Средний"), (3, "Сильный")
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
        options(25, "Подогрев сзади слева", .seats, heat),
        options(26, "Подогрев сзади справа", .seats, heat),
        options(27, "Вентиляция сзади слева", .seats, ventilation),
        options(28, "Вентиляция сзади справа", .seats, ventilation),
        toggle(30, "Багажник", .vehicle, confirmation: true),
        options(31, "Режим движения", .vehicle, driveModes),
        toggle(32, "Auto Hold", .vehicle),
        toggle(33, "Start/Stop", .vehicle), toggle(34, "Экономия топлива", .vehicle),
        action(35, "Сервисное положение дворников", .vehicle, confirmation: true),
        toggle(40, "Атмосферная подсветка", .comfort),
        range(41, "Яркость подсветки", .comfort, 0, 10000, 500, 100, "%"),
        toggle(42, "Экран пассажира", .comfort),
        range(43, "Дневная яркость экрана", .comfort, 0, 10000, 500, 100, "%"),
        range(44, "Ночная яркость экрана", .comfort, 0, 10000, 500, 100, "%"),
        options(45, "Режим подсветки", .comfort, ambientModes),
        options(46, "Эффект подсветки", .comfort, ambientEffects),
        options(47, "Цвет подсветки", .comfort, ambientColors),
        options(48, "Тема подсветки", .comfort, ambientThemes),
        action(50, "Воспроизведение / пауза", .media), action(51, "Следующий трек", .media),
        action(52, "Предыдущий трек", .media), action(53, "Без звука", .media),
        range(54, "Громкость", .media, 0, 10000, 500, 100, "%"),
        action(55, "Закрыть окно водителя", .vehicle, confirmation: true),
        action(56, "Закрыть окно пассажира", .vehicle, confirmation: true),
        action(57, "Закрыть заднее левое окно", .vehicle, confirmation: true),
        action(58, "Закрыть заднее правое окно", .vehicle, confirmation: true),
        action(59, "Закрыть наклон люка", .vehicle, confirmation: true),
        toggle(60, "Лампа спереди слева", .comfort),
        toggle(61, "Лампа спереди справа", .comfort),
        toggle(62, "Лампа сзади слева", .comfort),
        toggle(63, "Лампа сзади справа", .comfort),
        toggle(64, "Все лампы салона", .comfort),
        options(65, "Ароматизатор", .comfort, fragranceLevels)
    ]

    /// Capability-gated v56 additions. A mixed-version session may legitimately omit all of them.
    public static let optionalV56IDs: Set<UInt8> = [
        25, 26, 27, 28, 45, 46, 47, 48, 55, 56, 57, 58, 59
    ]

    /// Passenger 1.14.0 PA-manager controls introduced by Natro 2.2.5 / Helper 58.
    public static let optionalV57IDs: Set<UInt8> = [60, 61, 62, 63, 64, 65]

    /// Required for mixed-version operation. The original 39 IDs remain the sync boundary.
    public static let requiredLegacyIDs = Set(
        controls.filter {
            !optionalV56IDs.contains($0.id) && !optionalV57IDs.contains($0.id)
        }.map(\.id)
    )

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
    /// Read-only state ids carried beside the legacy 39-control baseline and optional v56 entries.
    /// They are never accepted
    /// as COMMAND ids and therefore remain compatible with older Helper/Natro builds.
    public enum CompanionStateID {
        public static let liveActivityProviderReady: UInt8 = 0xfb
        public static let cabinTemperature: UInt8 = 0xfc
        public static let outdoorTemperature: UInt8 = 0xfd
        public static let ancsConnected: UInt8 = 0xfe
    }

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
    public var onDiagnostic: ((String) -> Void)?
    public private(set) var isSynced = false
    public private(set) var statusText = "Ожидание защищённого Bluetooth‑канала…"
    public private(set) var available = Set<UInt8>()
    public private(set) var states: [UInt8: State] = [:]
    public private(set) var ancsStateKnown = false
    public private(set) var ancsConnected = false
    public private(set) var liveActivityProviderReady = false
    /// Exact tenths of a degree Celsius supplied by Natro vehicle telemetry.
    public private(set) var cabinTemperatureTenths: Int32?
    /// Exact tenths of a degree Celsius supplied by Natro vehicle telemetry.
    public private(set) var outdoorTemperatureTenths: Int32?

    private let sender: (Data) -> Void
    private var sequence = UInt32.random(in: 1...(UInt32.max - 1))
    private var transaction: UInt16 = 0
    private var retryTimer: Timer?
    private var transportReady = false
    private var started = false
    private let expectedCatalogIDs = CarRemoteCatalogV1.requiredLegacyIDs
    private var catalogSeen = Set<UInt8>()
    private var catalogTailSeen = false
    private var pendingResults: [UInt16: PendingResult] = [:]
    private var sceneGeneration: UInt64 = 0
    private var provisioningMessageID: UInt16 = 0

    private struct PendingResult {
        let controlID: UInt8
        let previousState: State?
        let predictedState: State?
        let completion: ((CarRemoteProtocolV1.ResultCode) -> Void)?
    }

    public init(sender: @escaping (Data) -> Void) {
        self.sender = sender
    }

    deinit { retryTimer?.invalidate() }

    public func start() {
        dispatchPrecondition(condition: .onQueue(.main))
        started = true
        onDiagnostic?("C5-клиент запущен; ожидается защищённый CONTROL owner")
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
            self.ancsStateKnown = false
            self.ancsConnected = false
            self.liveActivityProviderReady = false
            self.cabinTemperatureTenths = nil
            self.outdoorTemperatureTenths = nil
            self.catalogSeen.removeAll()
            self.catalogTailSeen = false
            self.pendingResults.removeAll()
            self.sceneGeneration &+= 1
            self.statusText = ready
                ? "Синхронизация функций автомобиля…"
                : "Ожидание защищённого Bluetooth‑канала…"
            self.onDiagnostic?(ready
                ? "маршрут аутентифицирован; начинаем C5 HELLO/каталог"
                : "маршрут потерян; C5-каталог и состояния сброшены")
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
        let previous = states[controlID]
        let predicted = optimisticState(
            controlID: controlID, operation: operation, requestedValue: value
        )
        if let predicted { states[controlID] = predicted }
        sender(CarRemoteProtocolV1.encode(.init(
            type: .command, controlID: controlID, code: operation.rawValue,
            flags: flags, transactionID: exactTransaction, sequence: nextSequence(),
            value: value, maxAgeDeciseconds: 30
        )))
        pendingResults[exactTransaction] = PendingResult(
            controlID: controlID, previousState: previous,
            predictedState: predicted, completion: completion
        )
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
            guard let self,
                  let pending = self.pendingResults.removeValue(
                    forKey: exactTransaction
                  ) else { return }
            self.restorePredictionIfCurrent(pending)
            self.statusText = "Автомобиль не ответил на команду"
            self.onChange?()
            pending.completion?(.timeout)
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
            self.sendSceneCommand(commands, index: index + 1, generation: generation)
        }
    }

    /// Sends token/config chunks only after C5 sync, preserving the command catalog boundary.
    public func sendLiveActivityProvisioning(
        type: LiveActivityPushProtocolV1.MessageType,
        payload: Data
    ) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard isSynced, !payload.isEmpty else { return }
        provisioningMessageID &+= 1
        if provisioningMessageID == 0 { provisioningMessageID = 1 }
        for frame in LiveActivityPushProtocolV1.frames(
            type: type, messageID: provisioningMessageID, payload: payload
        ) {
            sender(frame)
        }
    }

    private func optimisticState(
        controlID: UInt8,
        operation: CarRemoteProtocolV1.Operation,
        requestedValue: Int32
    ) -> State? {
        guard let definition = CarRemoteCatalogV1.byID[controlID],
              definition.kind != .action else { return nil }
        let current = states[controlID]
        let nextValue: Int32
        switch operation {
        case .set:
            if !definition.directValues.isEmpty {
                guard definition.directValues.contains(where: { $0.0 == requestedValue })
                    else { return nil }
                nextValue = requestedValue
            } else {
                nextValue = min(definition.maximum, max(definition.minimum, requestedValue))
            }
        case .toggle:
            nextValue = current?.active == true || current?.value != 0
                ? 0 : (definition.directValues.first(where: { $0.0 != 0 })?.0 ?? 1)
        case .cycle:
            let values = definition.directValues.map(\.0)
            guard !values.isEmpty else { return nil }
            let index = values.firstIndex(of: current?.value ?? values[0]) ?? -1
            nextValue = values[(index + 1) % values.count]
        case .activate, .none:
            return nil
        }
        return State(available: true, known: true, active: nextValue != 0, value: nextValue)
    }

    private func restorePredictionIfCurrent(_ pending: PendingResult) {
        guard let predicted = pending.predictedState,
              states[pending.controlID] == predicted else { return }
        states[pending.controlID] = pending.previousState
    }

    private func sendHello() {
        guard transportReady else { return }
        onDiagnostic?("C5 HELLO отправлен; ждём полный базовый каталог и границу")
        sender(CarRemoteProtocolV1.encode(.init(
            type: .hello, controlID: 0, code: 0, flags: 0, transactionID: 0,
            sequence: sequence, value: 0, maxAgeDeciseconds: 0
        )))
    }

    private func acceptOnMain(_ frame: CarRemoteProtocolV1.Frame) {
        var shouldNotify = false
        switch frame.type {
        case .catalog:
            if frame.controlID == CarRemoteCatalogV1.controls.first?.id {
                // A HELLO retry starts a new complete snapshot. Never merge a torn catalog with
                // a later generation and then claim that the union is complete.
                catalogSeen.removeAll()
                catalogTailSeen = false
                available.removeAll()
                states.removeAll()
                onDiagnostic?("получено начало нового C5-каталога")
            }
            catalogSeen.insert(frame.controlID)
            if frame.flags & CarRemoteProtocolV1.Flags.more == 0 {
                catalogTailSeen = true
            }
            if frame.flags & CarRemoteProtocolV1.Flags.available != 0 {
                available.insert(frame.controlID)
            } else {
                available.remove(frame.controlID)
            }
        case .state:
            let known = frame.flags & CarRemoteProtocolV1.Flags.known != 0
            let active = frame.flags & CarRemoteProtocolV1.Flags.active != 0
            switch frame.controlID {
            case CompanionStateID.liveActivityProviderReady:
                let next = known && (active || frame.value != 0)
                shouldNotify = liveActivityProviderReady != next
                liveActivityProviderReady = next
            case CompanionStateID.ancsConnected:
                let previousKnown = ancsStateKnown
                let previousConnected = ancsConnected
                ancsStateKnown = known
                ancsConnected = known && (active || frame.value != 0)
                if previousKnown != ancsStateKnown || previousConnected != ancsConnected {
                    shouldNotify = true
                    onDiagnostic?(ancsStateKnown
                        ? (ancsConnected ? "ANCS подтверждён Natro как подключённый"
                            : "ANCS подтверждён Natro как отключённый")
                        : "состояние ANCS стало неизвестным")
                }
            case CompanionStateID.cabinTemperature:
                let next: Int32? = known ? frame.value : nil
                shouldNotify = cabinTemperatureTenths != next
                cabinTemperatureTenths = next
            case CompanionStateID.outdoorTemperature:
                let next: Int32? = known ? frame.value : nil
                shouldNotify = outdoorTemperatureTenths != next
                outdoorTemperatureTenths = next
            default:
                let next = State(
                    available: frame.flags & CarRemoteProtocolV1.Flags.available != 0,
                    known: known,
                    active: active,
                    value: frame.value
                )
                shouldNotify = states[frame.controlID] != next
                states[frame.controlID] = next
            }
        case .result:
            shouldNotify = true
            let result = CarRemoteProtocolV1.ResultCode(rawValue: frame.code)
            statusText = result?.title ?? "Неизвестный ответ автомобиля"
            if let result, let pending = pendingResults[frame.transactionID],
               pending.controlID == frame.controlID {
                pendingResults.removeValue(forKey: frame.transactionID)
                if result != .ok { restorePredictionIfCurrent(pending) }
                pending.completion?(result)
            }
        case .syncComplete:
            shouldNotify = true
            let missing = expectedCatalogIDs.subtracting(catalogSeen)
            if catalogTailSeen && missing.isEmpty {
                isSynced = true
                statusText = "Автомобиль подключён по Bluetooth"
                onDiagnostic?("C5 синхронизирован: базовых функций \(expectedCatalogIDs.count), всего получено \(catalogSeen.count), доступно \(available.count)")
            } else {
                isSynced = false
                statusText = "Каталог получен не полностью (\(catalogSeen.count)/\(expectedCatalogIDs.count)), повтор…"
                onDiagnostic?("C5 SYNC_COMPLETE отклонён: нет границы или не хватает \(missing.count) базовых функций")
            }
        default:
            break
        }
        if shouldNotify { onChange?() }
    }

    private func nextSequence() -> UInt32 {
        sequence &+= 1
        if sequence == 0 { sequence = 1 }
        return sequence
    }
}
