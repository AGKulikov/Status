import CryptoKit
import Foundation
import Security

/// Pre-SMP Route-A enrollment/authentication for KX11 firmware where the selected Classic
/// iPhone bond does not expose the iPhone's rotating LE address to Android.
///
/// C4 never carries H, CONTROL, telemetry, ANCS data, a Bluetooth address, a device name,
/// manufacturer data, or RSSI. Initial enrollment is authenticated by a user-confirmed SAS
/// derived from an ephemeral P-256 ECDH exchange. Routine handshakes use the high-entropy key
/// created by that exchange. H/CONTROL/telemetry remain separate encrypted characteristics.
public enum HelperEnrollmentV1 {
    public static let version: UInt8 = 1
    public static let nonceBytes = 16
    public static let uuidBytes = 16
    public static let p256PublicKeyBytes = 65
    public static let macBytes = 32
    public static let helloCoreBytes = 34
    public static let enrollmentHelloBytes = helloCoreBytes + p256PublicKeyBytes
    public static let proofBytes = helloCoreBytes + macBytes
    public static let enrollmentResponseBytes = enrollmentHelloBytes
    public static let confirmBytes = helloCoreBytes + macBytes
    /// Android must negotiate and observe at least this ATT MTU before sending a 99-byte frame.
    public static let requiredATTMTU = 103
    public static let sessionSeconds: TimeInterval = 60
    public static let maximumEnrollmentHelloAttempts = 5
    public static let minimumEnrollmentHelloInterval: TimeInterval = 2

    public static let transcriptDomain = Data("NATRO-F201-ENROLLMENT-V1".utf8)
    private static let sessionMasterInfo = Data("session-master".utf8)
    private static let longTermInfo = Data("long-term".utf8)
    private static let sasLabel = Data("sas".utf8)
    private static let enrollmentConfirmLabel = Data("android-confirm".utf8)
    private static let routineProofLabel = Data("routine-proof".utf8)
    private static let routineConfirmLabel = Data("android-routine-confirm".utf8)
    private static let helperWaitingSASLabel = Data("helper-waiting-sas".utf8)
    private static let helperAckLabel = Data("helper-ack".utf8)
    private static let enrollmentCommitLabel = Data("android-commit".utf8)
    private static let enrollmentCommitAckLabel = Data("helper-commit-ack".utf8)

    public enum Kind: UInt8, Equatable {
        case enrollmentHello = 0x01
        case routineHello = 0x02
        case enrollmentConfirm = 0x03
        case routineConfirm = 0x04
        case enrollmentCommit = 0x05
        case enrollmentWaitingSAS = 0x80
        case enrollmentResponse = 0x81
        case routineProof = 0x82
        case enrollmentAck = 0x83
        case routineAck = 0x84
        case enrollmentCommitAck = 0x85
    }

    public struct Hello: Equatable {
        public let kind: Kind
        public let androidInstallationID: UUID
        public let androidNonce: Data
        public let androidPublicKey: Data?
        public let encoded: Data
    }

    public struct Confirm: Equatable {
        public let kind: Kind
        public let androidInstallationID: UUID
        public let androidNonce: Data
        public let mac: Data
        public let core: Data
        public let encoded: Data
    }

    public struct EnrollmentExchange {
        public let hello: Hello
        public let response: Data
        public let transcript: Data
        public let sessionMaster: SymmetricKey
        public let longTermKey: Data
        public let sas: String
        public let helperNonce: Data
    }

    public struct RoutineExchange {
        public let hello: Hello
        public let response: Data
        public let transcript: Data
        public let longTermKey: SymmetricKey
        public let helperNonce: Data
    }

    public struct Binding: Equatable {
        public let androidInstallationID: UUID
        public let longTermKey: Data

        public init(androidInstallationID: UUID, longTermKey: Data) {
            self.androidInstallationID = androidInstallationID
            self.longTermKey = longTermKey
        }
    }

    public enum ProtocolError: Error, Equatable {
        case invalidFrame
        case invalidPublicKey
        case randomGenerationFailed
        case keyAgreementFailed
        case invalidConfirmation
        case invalidBinding
    }

    public enum StorageError: Error, Equatable {
        case keychain(OSStatus)
        case invalidRecord
    }

    public static func decodeHello(_ data: Data) -> Hello? {
        guard data.count == helloCoreBytes || data.count == enrollmentHelloBytes,
              data[0] == version,
              let kind = Kind(rawValue: data[1]),
              kind == .enrollmentHello || kind == .routineHello else {
            return nil
        }
        if kind == .enrollmentHello {
            guard data.count == enrollmentHelloBytes else { return nil }
        } else {
            guard data.count == helloCoreBytes else { return nil }
        }
        let idBytes = Data(data[2..<18])
        let nonce = Data(data[18..<34])
        guard let androidID = uuidFromCanonicalBytes(idBytes),
              !allZero(idBytes), !allZero(nonce) else {
            return nil
        }
        let publicKey = kind == .enrollmentHello ? Data(data[34..<99]) : nil
        if let publicKey {
            guard publicKey.count == p256PublicKeyBytes,
                  publicKey.first == 0x04,
                  (try? P256.KeyAgreement.PublicKey(x963Representation: publicKey)) != nil else {
                return nil
            }
        }
        return Hello(
            kind: kind,
            androidInstallationID: androidID,
            androidNonce: nonce,
            androidPublicKey: publicKey,
            encoded: data
        )
    }

    public static func decodeConfirm(_ data: Data) -> Confirm? {
        guard data.count == confirmBytes,
              data[0] == version,
              let kind = Kind(rawValue: data[1]),
              kind == .enrollmentConfirm || kind == .routineConfirm ||
                kind == .enrollmentCommit else {
            return nil
        }
        let idBytes = Data(data[2..<18])
        let nonce = Data(data[18..<34])
        let mac = Data(data[34..<66])
        guard let androidID = uuidFromCanonicalBytes(idBytes),
              !allZero(idBytes), !allZero(nonce), !allZero(mac) else {
            return nil
        }
        return Confirm(
            kind: kind,
            androidInstallationID: androidID,
            androidNonce: nonce,
            mac: mac,
            core: Data(data[0..<34]),
            encoded: data
        )
    }

    public static func makeEnrollmentExchange(
        hello: Hello,
        helperInstallationID: UUID,
        helperPrivateKey: P256.KeyAgreement.PrivateKey,
        helperNonce: Data
    ) throws -> EnrollmentExchange {
        guard hello.kind == .enrollmentHello,
              let androidPublicBytes = hello.androidPublicKey,
              helperNonce.count == nonceBytes, !allZero(helperNonce) else {
            throw ProtocolError.invalidFrame
        }
        let androidPublicKey: P256.KeyAgreement.PublicKey
        do {
            androidPublicKey = try P256.KeyAgreement.PublicKey(
                x963Representation: androidPublicBytes
            )
        } catch {
            throw ProtocolError.invalidPublicKey
        }
        let response = responseCore(
            kind: .enrollmentResponse,
            helperInstallationID: helperInstallationID,
            helperNonce: helperNonce,
            publicKey: helperPrivateKey.publicKey.x963Representation
        )
        guard response.count == enrollmentResponseBytes else {
            throw ProtocolError.invalidFrame
        }
        let transcript = transcriptDomain + hello.encoded + response
        let transcriptHash = Data(SHA256.hash(data: transcript))
        let sharedSecret: SharedSecret
        do {
            sharedSecret = try helperPrivateKey.sharedSecretFromKeyAgreement(with: androidPublicKey)
        } catch {
            throw ProtocolError.keyAgreementFailed
        }
        let sessionMaster = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: transcriptHash,
            sharedInfo: sessionMasterInfo,
            outputByteCount: 32
        )
        let sasNumber = unbiasedSASNumber(key: sessionMaster, transcript: transcript)
        let sas = String(format: "%08llu", CUnsignedLongLong(sasNumber))
        let longTerm = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: sessionMaster,
            salt: transcriptHash,
            info: longTermInfo,
            outputByteCount: 32
        )
        return EnrollmentExchange(
            hello: hello,
            response: response,
            transcript: transcript,
            sessionMaster: sessionMaster,
            longTermKey: keyData(longTerm),
            sas: sas,
            helperNonce: helperNonce
        )
    }

    public static func makeRoutineExchange(
        hello: Hello,
        helperInstallationID: UUID,
        longTermKey: Data,
        helperNonce: Data
    ) throws -> RoutineExchange {
        guard hello.kind == .routineHello,
              longTermKey.count == macBytes,
              helperNonce.count == nonceBytes, !allZero(helperNonce) else {
            throw ProtocolError.invalidBinding
        }
        let key = SymmetricKey(data: longTermKey)
        let core = responseCore(
            kind: .routineProof,
            helperInstallationID: helperInstallationID,
            helperNonce: helperNonce,
            publicKey: nil
        )
        let proof = authenticationCode(
            key: key,
            data: transcriptDomain + hello.encoded + core + routineProofLabel
        )
        let response = core + proof
        return RoutineExchange(
            hello: hello,
            response: response,
            transcript: transcriptDomain + hello.encoded + response,
            longTermKey: key,
            helperNonce: helperNonce
        )
    }

    public static func validateEnrollmentConfirm(
        _ confirm: Confirm,
        exchange: EnrollmentExchange
    ) -> Bool {
        guard confirm.kind == .enrollmentConfirm,
              confirm.androidInstallationID == exchange.hello.androidInstallationID,
              confirm.androidNonce == exchange.hello.androidNonce else {
            return false
        }
        return validAuthenticationCode(
            confirm.mac,
            key: exchange.sessionMaster,
            data: exchange.transcript + confirm.core + enrollmentConfirmLabel
        )
    }

    public static func validateRoutineConfirm(
        _ confirm: Confirm,
        exchange: RoutineExchange
    ) -> Bool {
        guard confirm.kind == .routineConfirm,
              confirm.androidInstallationID == exchange.hello.androidInstallationID,
              confirm.androidNonce == exchange.hello.androidNonce else {
            return false
        }
        return validAuthenticationCode(
            confirm.mac,
            key: exchange.longTermKey,
            data: exchange.transcript + confirm.core + routineConfirmLabel
        )
    }

    public static func validateEnrollmentCommit(
        _ commit: Confirm,
        exchange: EnrollmentExchange,
        acceptedConfirm: Confirm
    ) -> Bool {
        guard commit.kind == .enrollmentCommit,
              acceptedConfirm.kind == .enrollmentConfirm,
              commit.androidInstallationID == exchange.hello.androidInstallationID,
              commit.androidNonce == exchange.hello.androidNonce else {
            return false
        }
        return validAuthenticationCode(
            commit.mac,
            key: exchange.sessionMaster,
            data: exchange.transcript + acceptedConfirm.encoded + commit.core +
                enrollmentCommitLabel
        )
    }

    public static func makeEnrollmentAck(
        exchange: EnrollmentExchange,
        confirm: Confirm,
        helperInstallationID: UUID
    ) -> Data {
        return makeAck(
            kind: .enrollmentAck,
            helperInstallationID: helperInstallationID,
            helperNonce: exchange.helperNonce,
            key: exchange.sessionMaster,
            transcript: exchange.transcript,
            confirm: confirm
        )
    }

    /// Authenticated, authority-free polling response used after Android has sent a valid
    /// CONFIRM but before the foreground iPhone user has confirmed the displayed SAS. Returning
    /// this frame with ATT success is deliberate: an Android 9 stack may interpret ATT
    /// authentication/authorization errors as permission to start SMP before both users agree.
    public static func makeEnrollmentWaitingSAS(
        exchange: EnrollmentExchange,
        confirm: Confirm,
        helperInstallationID: UUID
    ) -> Data {
        let core = responseCore(
            kind: .enrollmentWaitingSAS,
            helperInstallationID: helperInstallationID,
            helperNonce: exchange.helperNonce,
            publicKey: nil
        )
        let mac = authenticationCode(
            key: exchange.sessionMaster,
            data: exchange.transcript + confirm.encoded + core + helperWaitingSASLabel
        )
        return core + mac
    }

    public static func makeRoutineAck(
        exchange: RoutineExchange,
        confirm: Confirm,
        helperInstallationID: UUID
    ) -> Data {
        return makeAck(
            kind: .routineAck,
            helperInstallationID: helperInstallationID,
            helperNonce: exchange.helperNonce,
            key: exchange.longTermKey,
            transcript: exchange.transcript,
            confirm: confirm
        )
    }

    public static func makeEnrollmentCommitAck(
        exchange: EnrollmentExchange,
        acceptedConfirm: Confirm,
        commit: Confirm,
        helperInstallationID: UUID
    ) -> Data {
        let core = responseCore(
            kind: .enrollmentCommitAck,
            helperInstallationID: helperInstallationID,
            helperNonce: exchange.helperNonce,
            publicKey: nil
        )
        let transactionID = enrollmentTransactionID(
            exchange: exchange,
            acceptedConfirm: acceptedConfirm,
            commit: commit
        )
        let mac = authenticationCode(
            key: SymmetricKey(data: exchange.longTermKey),
            data: transcriptDomain + transactionID + core + enrollmentCommitAckLabel
        )
        return core + mac
    }

    public static func enrollmentTransactionID(
        exchange: EnrollmentExchange,
        acceptedConfirm: Confirm,
        commit: Confirm
    ) -> Data {
        Data(SHA256.hash(
            data: exchange.transcript + acceptedConfirm.encoded + commit.encoded
        ))
    }

    public static func randomNonce() throws -> Data {
        var data = Data(count: nonceBytes)
        let status = data.withUnsafeMutableBytes { bytes -> OSStatus in
            guard let baseAddress = bytes.baseAddress else { return errSecAllocate }
            return SecRandomCopyBytes(kSecRandomDefault, nonceBytes, baseAddress)
        }
        guard status == errSecSuccess, !allZero(data) else {
            throw ProtocolError.randomGenerationFailed
        }
        return data
    }

    public static func canonicalBytes(_ uuid: UUID) -> Data {
        var raw = uuid.uuid
        return withUnsafeBytes(of: &raw) { Data($0) }
    }

    public static func uuidFromCanonicalBytes(_ data: Data) -> UUID? {
        guard data.count == uuidBytes else { return nil }
        let bytes = [UInt8](data)
        let tuple: uuid_t = (
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15]
        )
        return UUID(uuid: tuple)
    }

    private static func responseCore(
        kind: Kind,
        helperInstallationID: UUID,
        helperNonce: Data,
        publicKey: Data?
    ) -> Data {
        var data = Data([version, kind.rawValue])
        data.append(canonicalBytes(helperInstallationID))
        data.append(helperNonce)
        if let publicKey { data.append(publicKey) }
        return data
    }

    private static func makeAck(
        kind: Kind,
        helperInstallationID: UUID,
        helperNonce: Data,
        key: SymmetricKey,
        transcript: Data,
        confirm: Confirm
    ) -> Data {
        let core = responseCore(
            kind: kind,
            helperInstallationID: helperInstallationID,
            helperNonce: helperNonce,
            publicKey: nil
        )
        let mac = authenticationCode(
            key: key,
            data: transcript + confirm.encoded + core + helperAckLabel
        )
        return core + mac
    }

    private static func authenticationCode(key: SymmetricKey, data: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: data, using: key))
    }

    private static func validAuthenticationCode(
        _ candidate: Data,
        key: SymmetricKey,
        data: Data
    ) -> Bool {
        HMAC<SHA256>.isValidAuthenticationCode(candidate, authenticating: data, using: key)
    }

    private static func keyData(_ key: SymmetricKey) -> Data {
        key.withUnsafeBytes { Data($0) }
    }

    private static func firstUInt64BigEndian(_ data: Data) -> UInt64 {
        data.prefix(8).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
    }

    /// Deterministic rejection sampling avoids modulo bias while keeping both peers wire-exact.
    /// Counter 0 authenticates `transcript || "sas" || 00000000`, then increments big-endian.
    private static func unbiasedSASNumber(
        key: SymmetricKey,
        transcript: Data
    ) -> UInt64 {
        let modulus: UInt64 = 100_000_000
        let discardedTail = (UInt64.max % modulus + 1) % modulus
        let maximumAccepted = UInt64.max - discardedTail
        var counter: UInt32 = 0
        while true {
            var bigEndian = counter.bigEndian
            let counterData = withUnsafeBytes(of: &bigEndian) { Data($0) }
            let mac = authenticationCode(
                key: key,
                data: transcript + sasLabel + counterData
            )
            let value = firstUInt64BigEndian(mac)
            if value <= maximumAccepted { return value % modulus }
            counter &+= 1
        }
    }

    private static func allZero(_ data: Data) -> Bool {
        data.reduce(UInt8(0), |) == 0
    }

}

/// One device-only Keychain item stores the bound Android installation UUID and its independent,
/// high-entropy long-term key. Re-enrollment replaces the item only after mutual confirmation;
/// an interrupted enrollment therefore cannot destroy the last working binding.
public final class HelperEnrollmentKeychainStore {
    public struct PendingBinding: Equatable {
        public let binding: HelperEnrollmentV1.Binding
        public let transactionID: Data
    }

    public struct State: Equatable {
        public let active: HelperEnrollmentV1.Binding?
        public let pending: PendingBinding?
    }

    private static let legacyRecordVersion: UInt8 = 1
    private static let recordVersion: UInt8 = 2
    private static let bindingBytes = HelperEnrollmentV1.uuidBytes + HelperEnrollmentV1.macBytes
    private static let transactionBytes = 32

    private let service: String
    private let account: String
    private let installationAccount = "helper-installation-uuid"

    public init(
        service: String = "ru.natro.kx11ancshelper.enrollment.v1",
        account: String = "route-a-android-owner"
    ) {
        self.service = service
        self.account = account
    }

    public func loadState() throws -> State {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return State(active: nil, pending: nil) }
        guard status == errSecSuccess, let data = result as? Data else {
            throw HelperEnrollmentV1.StorageError.keychain(status)
        }
        if data.first == Self.legacyRecordVersion {
            guard data.count == 1 + Self.bindingBytes else {
                throw HelperEnrollmentV1.StorageError.invalidRecord
            }
            return State(active: try decodeBinding(Data(data[1...])), pending: nil)
        }
        guard data.count >= 2, data[0] == Self.recordVersion,
              data[1] & ~UInt8(0x03) == 0 else {
            throw HelperEnrollmentV1.StorageError.invalidRecord
        }
        let flags = data[1]
        var offset = 2
        var active: HelperEnrollmentV1.Binding?
        var pending: PendingBinding?
        if flags & 0x01 != 0 {
            guard data.count >= offset + Self.bindingBytes else {
                throw HelperEnrollmentV1.StorageError.invalidRecord
            }
            active = try decodeBinding(Data(data[offset..<(offset + Self.bindingBytes)]))
            offset += Self.bindingBytes
        }
        if flags & 0x02 != 0 {
            guard data.count >= offset + Self.bindingBytes + Self.transactionBytes else {
                throw HelperEnrollmentV1.StorageError.invalidRecord
            }
            let binding = try decodeBinding(Data(data[offset..<(offset + Self.bindingBytes)]))
            offset += Self.bindingBytes
            let transactionID = Data(data[offset..<(offset + Self.transactionBytes)])
            guard transactionID.reduce(UInt8(0), |) != 0 else {
                throw HelperEnrollmentV1.StorageError.invalidRecord
            }
            offset += Self.transactionBytes
            pending = PendingBinding(binding: binding, transactionID: transactionID)
        }
        guard offset == data.count, active != nil || pending != nil else {
            throw HelperEnrollmentV1.StorageError.invalidRecord
        }
        return State(active: active, pending: pending)
    }

    public func load() throws -> HelperEnrollmentV1.Binding? {
        return try loadState().active
    }

    public func stagePending(
        _ binding: HelperEnrollmentV1.Binding,
        transactionID: Data
    ) throws -> State {
        guard transactionID.count == Self.transactionBytes,
              transactionID.reduce(UInt8(0), |) != 0 else {
            throw HelperEnrollmentV1.StorageError.invalidRecord
        }
        let current = try loadState()
        let next = State(
            active: current.active,
            pending: PendingBinding(binding: binding, transactionID: transactionID)
        )
        try writeState(next)
        return next
    }

    public func promotePending(transactionID: Data) throws -> State {
        let current = try loadState()
        guard let pending = current.pending,
              pending.transactionID == transactionID else {
            throw HelperEnrollmentV1.StorageError.invalidRecord
        }
        let next = State(active: pending.binding, pending: nil)
        try writeState(next)
        return next
    }

    private func writeState(_ state: State) throws {
        var flags: UInt8 = 0
        var record = Data([Self.recordVersion, 0])
        if let active = state.active {
            flags |= 0x01
            record.append(try encodeBinding(active))
        }
        if let pending = state.pending {
            guard pending.transactionID.count == Self.transactionBytes,
                  pending.transactionID.reduce(UInt8(0), |) != 0 else {
                throw HelperEnrollmentV1.StorageError.invalidRecord
            }
            flags |= 0x02
            record.append(try encodeBinding(pending.binding))
            record.append(pending.transactionID)
        }
        guard flags != 0 else { throw HelperEnrollmentV1.StorageError.invalidRecord }
        record[1] = flags
        let match: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let update: [String: Any] = [kSecValueData as String: record]
        let updateStatus = SecItemUpdate(match as CFDictionary, update as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw HelperEnrollmentV1.StorageError.keychain(updateStatus)
        }
        var add = match
        add[kSecValueData as String] = record
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw HelperEnrollmentV1.StorageError.keychain(addStatus)
        }
    }

    private func encodeBinding(_ binding: HelperEnrollmentV1.Binding) throws -> Data {
        guard binding.longTermKey.count == HelperEnrollmentV1.macBytes,
              binding.longTermKey.reduce(UInt8(0), |) != 0 else {
            throw HelperEnrollmentV1.StorageError.invalidRecord
        }
        var data = HelperEnrollmentV1.canonicalBytes(binding.androidInstallationID)
        data.append(binding.longTermKey)
        return data
    }

    private func decodeBinding(_ data: Data) throws -> HelperEnrollmentV1.Binding {
        guard data.count == Self.bindingBytes,
              let androidID = HelperEnrollmentV1.uuidFromCanonicalBytes(Data(data[0..<16])),
              HelperEnrollmentV1.canonicalBytes(androidID).reduce(UInt8(0), |) != 0 else {
            throw HelperEnrollmentV1.StorageError.invalidRecord
        }
        let key = Data(data[16..<48])
        guard key.reduce(UInt8(0), |) != 0 else {
            throw HelperEnrollmentV1.StorageError.invalidRecord
        }
        return HelperEnrollmentV1.Binding(
            androidInstallationID: androidID,
            longTermKey: key
        )
    }

    public func delete() throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw HelperEnrollmentV1.StorageError.keychain(status)
        }
    }

    /// Loads the v51 device-only UUID or generates a fresh one, then removes the legacy v50
    /// backup-restorable UserDefaults copy. v50 had no enrollment LTK to preserve, so importing its
    /// public UUID would let one old backup clone the protocol installation identity onto another
    /// iPhone for no compatibility benefit.
    public func loadOrCreateInstallationID(
        legacyDefaults: UserDefaults,
        legacyDefaultsKey: String
    ) throws -> UUID {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: installationAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess {
            guard let data = result as? Data,
                  let existing = HelperEnrollmentV1.uuidFromCanonicalBytes(data),
                  data.reduce(UInt8(0), |) != 0 else {
                throw HelperEnrollmentV1.StorageError.invalidRecord
            }
            legacyDefaults.removeObject(forKey: legacyDefaultsKey)
            return existing
        }
        guard status == errSecItemNotFound else {
            throw HelperEnrollmentV1.StorageError.keychain(status)
        }

        var candidate = UUID()
        while HelperEnrollmentV1.canonicalBytes(candidate).reduce(UInt8(0), |) == 0 {
            candidate = UUID()
        }
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: installationAccount,
            kSecValueData as String: HelperEnrollmentV1.canonicalBytes(candidate),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw HelperEnrollmentV1.StorageError.keychain(addStatus)
        }
        legacyDefaults.removeObject(forKey: legacyDefaultsKey)
        return candidate
    }
}
