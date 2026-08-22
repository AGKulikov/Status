// Fixed ATT20 control and ATT8 telemetry codec shared with Android transport v2.
// This layer is pure bytes: no CoreBluetooth, names, addresses, or MTU negotiation.

public enum IphoneBleWireProtocolV2 {
    public static let version: UInt8 = 2
    public static let controlFrameBytes = 20
    public static let controlPayloadBytes = 16
    public static let telemetryFrameBytes = 8

    public enum Mode: UInt8, Equatable {
        case androidCentral = 1
        case androidPeripheral = 2
    }

    public enum ControlType: UInt8, Equatable {
        case peerProof = 0x48       // H
        case roleClose = 0x43       // C
        case roleCloseAck = 0x41    // A
        case telemetryRefresh = 0x52 // R
    }

    public struct ControlFrame: Equatable {
        public let type: ControlType
        /// For C/A this is the desired target mode, not the route being stopped. For H/R it is
        /// the currently selected Android role. R is accepted only from the authenticated CONTROL
        /// owner and asks for one fresh telemetry sample; it never changes route state.
        public let mode: Mode
        public let flags: UInt8
        /// Stable installation UUID bytes for H; exact random switch token for C/A; one-shot
        /// non-zero request token for R.
        public let payload: [UInt8]

        public var telemetrySupported: Bool {
            type == .peerProof && (flags & Flag.telemetry) != 0
        }

        public var ancsSupported: Bool {
            type == .peerProof && (flags & Flag.ancs) != 0
        }
    }

    public enum ChargeState: UInt8, Equatable {
        case unknown = 0
        case discharging = 1
        case charging = 2
        case full = 3
    }

    public enum Network: UInt8, Equatable {
        case unknown = 0
        case offline = 1
        case wifi = 2
        case lte = 3
        case nr5G = 4
        case cellular3G = 5
        case cellular2G = 6
    }

    public struct Telemetry: Equatable {
        public let batteryPercent: UInt8?
        public let externalPower: Bool
        public let chargeState: ChargeState
        public let network: Network
        public let locked: Bool
        public let sequence: UInt16

        public init(
            batteryPercent: UInt8?,
            externalPower: Bool,
            chargeState: ChargeState,
            network: Network,
            locked: Bool,
            sequence: UInt16
        ) {
            self.batteryPercent = batteryPercent
            self.externalPower = externalPower
            self.chargeState = chargeState
            self.network = network
            self.locked = locked
            self.sequence = sequence
        }
    }

    public enum CodecError: Error, Equatable {
        case payloadMustBeNonZero16Bytes
        case peerProofRequiresAncs
        case batteryOutOfRange
    }

    public static func encodePeerProof(
        mode: Mode,
        installationID: [UInt8],
        telemetrySupported: Bool,
        ancsSupported: Bool
    ) throws -> [UInt8] {
        guard ancsSupported else { throw CodecError.peerProofRequiresAncs }
        let flags = (telemetrySupported ? Flag.telemetry : 0) | Flag.ancs
        return try encodeControl(.peerProof, mode: mode, flags: flags, payload: installationID)
    }

    /// C byte 2 names the desired target mode. The token is echoed byte-for-byte by A.
    public static func encodeRoleClose(
        targetMode: Mode,
        switchToken: [UInt8]
    ) throws -> [UInt8] {
        return try encodeControl(.roleClose, mode: targetMode, flags: 0, payload: switchToken)
    }

    public static func encodeRoleCloseAck(
        targetMode: Mode,
        switchToken: [UInt8]
    ) throws -> [UInt8] {
        return try encodeControl(.roleCloseAck, mode: targetMode, flags: 0, payload: switchToken)
    }

    /// One-shot, authenticated refresh request. Android creates a fresh non-zero token for each
    /// request after its 30-second quiet window. Helper does not poll and does not echo the token;
    /// the next increasing T sequence is the response.
    public static func encodeTelemetryRefresh(
        activeMode: Mode,
        requestToken: [UInt8]
    ) throws -> [UInt8] {
        return try encodeControl(
            .telemetryRefresh,
            mode: activeMode,
            flags: 0,
            payload: requestToken
        )
    }

    public static func decodeControl(_ bytes: [UInt8]) -> ControlFrame? {
        guard bytes.count == controlFrameBytes,
              let type = ControlType(rawValue: bytes[0]),
              bytes[1] == version,
              let mode = Mode(rawValue: bytes[2]) else {
            return nil
        }
        let flags = bytes[3]
        if type == .peerProof {
            guard flags & ~Flag.peerProofKnown == 0, flags & Flag.ancs != 0 else { return nil }
        } else {
            guard flags == 0 else { return nil }
        }
        let payload = Array(bytes[4..<controlFrameBytes])
        guard isNonZero16(payload) else { return nil }
        return ControlFrame(type: type, mode: mode, flags: flags, payload: payload)
    }

    public static func encodeTelemetry(
        batteryPercent: UInt8?,
        externalPower: Bool,
        chargeState: ChargeState,
        network: Network,
        locked: Bool,
        sequence: UInt16
    ) throws -> [UInt8] {
        if let batteryPercent, batteryPercent > 100 { throw CodecError.batteryOutOfRange }
        let flags = (batteryPercent == nil ? 0 : Flag.batteryValid)
            | (externalPower ? Flag.externalPower : 0)
            | (locked ? Flag.locked : 0)
            | (chargeState.rawValue << Flag.chargeShift)
        var frame: [UInt8] = [
            0x54, // T
            version,
            flags,
            batteryPercent ?? 0xff,
            network.rawValue,
            UInt8(truncatingIfNeeded: sequence),
            UInt8(truncatingIfNeeded: sequence >> 8),
            0
        ]
        frame[7] = crc8(frame[0..<7])
        return frame
    }

    public static func decodeTelemetry(_ bytes: [UInt8]) -> Telemetry? {
        guard bytes.count == telemetryFrameBytes,
              bytes[0] == 0x54,
              bytes[1] == version,
              bytes[7] == crc8(bytes[0..<7]) else {
            return nil
        }
        let flags = bytes[2]
        guard flags & ~Flag.telemetryKnown == 0 else { return nil }
        let batteryValid = flags & Flag.batteryValid != 0
        let rawBattery = bytes[3]
        guard batteryValid ? rawBattery <= 100 : rawBattery == 0xff,
              let charge = ChargeState(rawValue: (flags & Flag.chargeMask) >> Flag.chargeShift),
              let network = Network(rawValue: bytes[4]) else {
            return nil
        }
        return Telemetry(
            batteryPercent: batteryValid ? rawBattery : nil,
            externalPower: flags & Flag.externalPower != 0,
            chargeState: charge,
            network: network,
            locked: flags & Flag.locked != 0,
            sequence: UInt16(bytes[5]) | UInt16(bytes[6]) << 8
        )
    }

    /// CRC-8/ATM: polynomial 0x07, initial value 0.
    public static func crc8<C: Collection>(_ bytes: C) -> UInt8 where C.Element == UInt8 {
        var crc: UInt8 = 0
        for byte in bytes {
            crc ^= byte
            for _ in 0..<8 {
                crc = crc & 0x80 != 0 ? (crc << 1) ^ 0x07 : crc << 1
            }
        }
        return crc
    }

    private static func encodeControl(
        _ type: ControlType,
        mode: Mode,
        flags: UInt8,
        payload: [UInt8]
    ) throws -> [UInt8] {
        guard isNonZero16(payload) else { throw CodecError.payloadMustBeNonZero16Bytes }
        if type == .peerProof {
            guard flags & ~Flag.peerProofKnown == 0, flags & Flag.ancs != 0 else {
                throw CodecError.peerProofRequiresAncs
            }
        } else {
            precondition(flags == 0, "C/A/R flags must be zero")
        }
        return [type.rawValue, version, mode.rawValue, flags] + payload
    }

    private static func isNonZero16(_ bytes: [UInt8]) -> Bool {
        bytes.count == controlPayloadBytes && bytes.reduce(UInt8(0), |) != 0
    }

    private enum Flag {
        static let telemetry: UInt8 = 1
        static let ancs: UInt8 = 1 << 1
        static let peerProofKnown = telemetry | ancs

        static let batteryValid: UInt8 = 1
        static let externalPower: UInt8 = 1 << 1
        static let locked: UInt8 = 1 << 2
        static let chargeShift: UInt8 = 3
        static let chargeMask: UInt8 = 3 << chargeShift
        static let telemetryKnown = batteryValid | externalPower | locked | chargeMask
    }
}
