// Pure confirmed-quiescence reducer kept transition-for-transition with Android's
// BleRoleSwitchReducer. CoreBluetooth objects and callbacks are deliberately excluded.

public struct BleRoleSwitchPolicy {
    public typealias Milliseconds = UInt64

    public enum Role: String, Equatable, Codable {
        case helperPeripheralAndroidCentral
        case helperCentralAndroidPeripheral
    }

    public enum Phase: String, Equatable, Codable {
        case active
        case freezing
        case waitingControlHandshake
        case waitingLocalTerminal
        case waitingRemoteAck
        case draining
        case quiescent
        case starting
        case failed
        case closed
    }

    public enum Outcome: Equatable {
        case applied
        case coalesced
        case rejectedConflict
        case rejectedTerminal
        case staleCallback
        case notDue
    }

    public enum Failure: String, Equatable, Codable {
        case none
        case stopTimeout
        case ingressFreezeFailed
        case contradictoryRemoteEvidence
        case contradictoryLocalOwnerEvidence
        case impossibleLocalOwnerCount
        case controlTransmitFailed
        case targetStartFailed
    }

    public enum ControlFrame: String, Equatable, Codable {
        case closeRequest
        case closeAck
    }

    public enum ControlTransmitResult: Equatable {
        case accepted
        case retryableFailure
        case terminalFailure
    }

    public enum RemoteCloseEvidence: String, Equatable, Codable {
        case confirmedAck
        case peerCommittedIntent
        case peerSameRoleRetained
        case noRemoteOwner
        case remoteAlreadyTerminal
        case radioOrPowerLoss
    }

    public enum EffectType: Equatable {
        case freezeSourceIngress
        case armStopTimeout
        case stopLocalSource
        case requestRemoteStop
        case acknowledgeRemoteStop
        case scheduleControlRetry
        case cancelControlRetry
        case verifyLocalOwners
        case cancelStopTimeout
        case armDrainDeadline
        case cancelDrainDeadline
        case quiescentReached
        case startTarget
        case targetActive
        case failClosed
        case closeAll
    }

    private enum ControlTransmitStatus: Equatable {
        case idle
        case inFlight
        case retryWait
        case accepted
    }

    /// Normalized, unbounded decimal ownership token. Fixed-width wrapping is forbidden.
    public struct Sequence: Equatable, Codable, CustomStringConvertible {
        public static let zero = Sequence(unchecked: "0")
        public static let one = Sequence(unchecked: "1")
        public let description: String

        public init?(_ decimal: String) {
            let bytes = decimal.utf8
            guard !bytes.isEmpty,
                  bytes.allSatisfy({ $0 >= 48 && $0 <= 57 }),
                  bytes.count == 1 || bytes.first != 48 else {
                return nil
            }
            description = decimal
        }

        private init(unchecked decimal: String) {
            description = decimal
        }

        public func next() -> Sequence {
            var digits = Array(description.utf8)
            var index = digits.count
            while index > 0 {
                index -= 1
                if digits[index] < 57 {
                    digits[index] += 1
                    return Sequence(unchecked: String(decoding: digits, as: UTF8.self))
                }
                digits[index] = 48
            }
            digits.insert(49, at: 0)
            return Sequence(unchecked: String(decoding: digits, as: UTF8.self))
        }

        public var isZero: Bool { self == .zero }

        public init(from decoder: Decoder) throws {
            let value = try decoder.singleValueContainer().decode(String.self)
            guard let normalized = Sequence(value) else {
                throw DecodingError.dataCorrupted(
                    .init(codingPath: decoder.codingPath, debugDescription: "invalid sequence")
                )
            }
            self = normalized
        }

        public func encode(to encoder: Encoder) throws {
            var container = encoder.singleValueContainer()
            try container.encode(description)
        }
    }

    public struct Effect: Equatable {
        public let type: EffectType
        public let epoch: Sequence
        public let generation: Sequence
        public let role: Role
        public let deadlineMs: Milliseconds?
        public let controlFrame: ControlFrame?
        public let controlAttempt: Sequence
    }

    public struct State: Equatable {
        public fileprivate(set) var phase: Phase
        public fileprivate(set) var epoch: Sequence
        public fileprivate(set) var desiredRole: Role?
        public fileprivate(set) var activeRole: Role?
        public fileprivate(set) var activeGeneration: Sequence?
        public fileprivate(set) var sourceRole: Role?
        public fileprivate(set) var sourceGeneration: Sequence?
        public fileprivate(set) var targetRole: Role?
        public fileprivate(set) var targetGeneration: Sequence?
        public fileprivate(set) var ingressFrozen: Bool
        public fileprivate(set) var localTerminal: Bool
        public fileprivate(set) var localOwnersZero: Bool
        public fileprivate(set) var remoteCloseEvidence: RemoteCloseEvidence?
        public fileprivate(set) var stopDeadlineMs: Milliseconds?
        public fileprivate(set) var drainDurationMs: Milliseconds?
        public fileprivate(set) var drainDeadlineMs: Milliseconds?
        public fileprivate(set) var failure: Failure
        public fileprivate(set) var controlFrame: ControlFrame?
        public fileprivate(set) var controlAttempt: Sequence
        fileprivate var controlTransmitStatus: ControlTransmitStatus
        public fileprivate(set) var controlTransmitAccepted: Bool
        public fileprivate(set) var localStopRequested: Bool

        public static func active(
            _ role: Role,
            lastEpoch: Sequence = .zero,
            generation: Sequence = .one
        ) -> State {
            precondition(!generation.isZero, "a real active owner requires non-zero generation")
            return State(
                phase: .active,
                epoch: lastEpoch,
                desiredRole: role,
                activeRole: role,
                activeGeneration: generation,
                sourceRole: nil,
                sourceGeneration: nil,
                targetRole: nil,
                targetGeneration: nil,
                ingressFrozen: false,
                localTerminal: false,
                localOwnersZero: false,
                remoteCloseEvidence: nil,
                stopDeadlineMs: nil,
                drainDurationMs: nil,
                drainDeadlineMs: nil,
                failure: .none,
                controlFrame: nil,
                controlAttempt: .zero,
                controlTransmitStatus: .idle,
                controlTransmitAccepted: false,
                localStopRequested: false
            )
        }

        public var remoteAcknowledged: Bool { remoteCloseEvidence != nil }
    }

    public struct Reduction: Equatable {
        public let state: State
        public let outcome: Outcome
        public let effects: [Effect]
    }

    public private(set) var state: State

    public init(activeRole: Role) {
        state = .active(activeRole)
    }

    public init(state: State) {
        self.state = state
    }

    @discardableResult
    public mutating func requestSwitch(
        to target: Role,
        nowMs: Milliseconds,
        stopTimeoutMs: Milliseconds,
        drainDurationMs: Milliseconds
    ) -> Reduction {
        requestSwitchInternal(
            to: target,
            nowMs: nowMs,
            stopTimeoutMs: stopTimeoutMs,
            drainDurationMs: drainDurationMs,
            initialRemoteEvidence: nil,
            controlFrame: .closeRequest,
            allowSameRoleRestart: false
        )
    }

    /// Restarts the exact selected topology after its former owner is already terminal. This is
    /// local-only: it emits no C/A and still requires freeze, terminal, owner-zero, and drain.
    @discardableResult
    public mutating func requestSameRoleRestart(
        nowMs: Milliseconds,
        stopTimeoutMs: Milliseconds,
        drainDurationMs: Milliseconds
    ) -> Reduction {
        guard let role = state.activeRole else { return unchanged(.rejectedConflict) }
        return requestSwitchInternal(
            to: role,
            nowMs: nowMs,
            stopTimeoutMs: stopTimeoutMs,
            drainDurationMs: drainDurationMs,
            initialRemoteEvidence: .peerSameRoleRetained,
            controlFrame: nil,
            allowSameRoleRestart: true
        )
    }

    @discardableResult
    public mutating func requestSwitchFromRemoteIntent(
        to target: Role,
        nowMs: Milliseconds,
        stopTimeoutMs: Milliseconds,
        drainDurationMs: Milliseconds
    ) -> Reduction {
        requestSwitchInternal(
            to: target,
            nowMs: nowMs,
            stopTimeoutMs: stopTimeoutMs,
            drainDurationMs: drainDurationMs,
            initialRemoteEvidence: .peerCommittedIntent,
            controlFrame: .closeAck,
            allowSameRoleRestart: false
        )
    }

    private mutating func requestSwitchInternal(
        to target: Role,
        nowMs: Milliseconds,
        stopTimeoutMs: Milliseconds,
        drainDurationMs: Milliseconds,
        initialRemoteEvidence: RemoteCloseEvidence?,
        controlFrame: ControlFrame?,
        allowSameRoleRestart: Bool
    ) -> Reduction {
        precondition(stopTimeoutMs > 0, "stopTimeoutMs must be positive")
        precondition(drainDurationMs > 0, "drainDurationMs must be positive")
        if state.phase == .failed || state.phase == .closed {
            return unchanged(.rejectedTerminal)
        }
        if state.phase != .active {
            return unchanged(target == state.targetRole ? .coalesced : .rejectedConflict)
        }
        guard target != state.activeRole || allowSameRoleRestart else {
            return unchanged(initialRemoteEvidence == .peerCommittedIntent ? .rejectedConflict : .coalesced)
        }

        let epoch = state.epoch.next()
        let targetGeneration = state.activeGeneration!.next()
        let stopDeadline = Self.addingClamped(nowMs, stopTimeoutMs)
        let next = State(
            phase: .freezing,
            epoch: epoch,
            desiredRole: target,
            activeRole: nil,
            activeGeneration: nil,
            sourceRole: state.activeRole,
            sourceGeneration: state.activeGeneration,
            targetRole: target,
            targetGeneration: targetGeneration,
            ingressFrozen: false,
            localTerminal: false,
            localOwnersZero: false,
            remoteCloseEvidence: initialRemoteEvidence,
            stopDeadlineMs: stopDeadline,
            drainDurationMs: drainDurationMs,
            drainDeadlineMs: nil,
            failure: .none,
            controlFrame: controlFrame,
            controlAttempt: .zero,
            controlTransmitStatus: .idle,
            controlTransmitAccepted: false,
            localStopRequested: false
        )
        return applied(next, [
            effect(.freezeSourceIngress, in: next),
            effect(.armStopTimeout, in: next, deadlineMs: stopDeadline)
        ])
    }

    @discardableResult
    public mutating func restoreDrain(
        sourceRole: Role,
        desiredRole: Role,
        epoch: Sequence,
        sourceGeneration: Sequence,
        targetGeneration: Sequence,
        nowMs: Milliseconds,
        stopTimeoutMs: Milliseconds,
        drainDurationMs: Milliseconds
    ) -> Reduction {
        restoreDrainInternal(
            sourceRole: sourceRole,
            desiredRole: desiredRole,
            epoch: epoch,
            sourceGeneration: sourceGeneration,
            targetGeneration: targetGeneration,
            nowMs: nowMs,
            stopTimeoutMs: stopTimeoutMs,
            drainDurationMs: drainDurationMs,
            initialRemoteEvidence: nil,
            controlFrame: .closeRequest
        )
    }

    @discardableResult
    public mutating func restoreDrainFromRemoteIntent(
        sourceRole: Role,
        desiredRole: Role,
        epoch: Sequence,
        sourceGeneration: Sequence,
        targetGeneration: Sequence,
        nowMs: Milliseconds,
        stopTimeoutMs: Milliseconds,
        drainDurationMs: Milliseconds
    ) -> Reduction {
        restoreDrainInternal(
            sourceRole: sourceRole,
            desiredRole: desiredRole,
            epoch: epoch,
            sourceGeneration: sourceGeneration,
            targetGeneration: targetGeneration,
            nowMs: nowMs,
            stopTimeoutMs: stopTimeoutMs,
            drainDurationMs: drainDurationMs,
            initialRemoteEvidence: .peerCommittedIntent,
            controlFrame: .closeAck
        )
    }

    @discardableResult
    public mutating func restoreDrainLocalOnly(
        role: Role,
        epoch: Sequence,
        sourceGeneration: Sequence,
        targetGeneration: Sequence,
        nowMs: Milliseconds,
        stopTimeoutMs: Milliseconds,
        drainDurationMs: Milliseconds
    ) -> Reduction {
        restoreDrainInternal(
            sourceRole: role,
            desiredRole: role,
            epoch: epoch,
            sourceGeneration: sourceGeneration,
            targetGeneration: targetGeneration,
            nowMs: nowMs,
            stopTimeoutMs: stopTimeoutMs,
            drainDurationMs: drainDurationMs,
            initialRemoteEvidence: .peerSameRoleRetained,
            controlFrame: nil
        )
    }

    private mutating func restoreDrainInternal(
        sourceRole: Role,
        desiredRole: Role,
        epoch: Sequence,
        sourceGeneration: Sequence,
        targetGeneration: Sequence,
        nowMs: Milliseconds,
        stopTimeoutMs: Milliseconds,
        drainDurationMs: Milliseconds,
        initialRemoteEvidence: RemoteCloseEvidence?,
        controlFrame: ControlFrame?
    ) -> Reduction {
        precondition(!epoch.isZero, "restored epoch must be non-zero")
        precondition(!sourceGeneration.isZero, "source generation must be non-zero")
        precondition(!targetGeneration.isZero, "target generation must be non-zero")
        precondition(stopTimeoutMs > 0 && drainDurationMs > 0, "restoration deadlines must be positive")

        let stopDeadline = Self.addingClamped(nowMs, stopTimeoutMs)
        let restored = State(
            phase: .freezing,
            epoch: epoch,
            desiredRole: desiredRole,
            activeRole: nil,
            activeGeneration: nil,
            sourceRole: sourceRole,
            sourceGeneration: sourceGeneration,
            targetRole: desiredRole,
            targetGeneration: targetGeneration,
            ingressFrozen: false,
            localTerminal: false,
            localOwnersZero: false,
            remoteCloseEvidence: initialRemoteEvidence,
            stopDeadlineMs: stopDeadline,
            drainDurationMs: drainDurationMs,
            drainDeadlineMs: nil,
            failure: .none,
            controlFrame: controlFrame,
            controlAttempt: .zero,
            controlTransmitStatus: .idle,
            controlTransmitAccepted: false,
            localStopRequested: false
        )
        return applied(restored, [
            effect(.freezeSourceIngress, in: restored),
            effect(.armStopTimeout, in: restored, deadlineMs: stopDeadline)
        ])
    }

    @discardableResult
    public mutating func onIngressFrozen(
        epoch: Sequence,
        sourceGeneration: Sequence,
        sourceRole: Role,
        nowMs: Milliseconds
    ) -> Reduction {
        guard ownsSource(epoch, sourceGeneration, sourceRole), Self.isStopping(state.phase) else {
            return unchanged(.staleCallback)
        }
        if let timedOut = timeoutIfDue(nowMs) { return timedOut }
        guard state.phase == .freezing else {
            return unchanged(state.ingressFrozen ? .coalesced : .staleCallback)
        }

        var next = state
        next.ingressFrozen = true
        var effects: [Effect] = []
        if !next.localTerminal,
           next.controlFrame == .closeRequest,
           next.remoteCloseEvidence == nil {
            beginControlTransmit(&next)
            effects.append(controlEffect(.requestRemoteStop, in: next))
        } else if !next.localTerminal,
                  next.controlFrame == .closeAck,
                  !next.controlTransmitAccepted {
            beginControlTransmit(&next)
            effects.append(controlEffect(.acknowledgeRemoteStop, in: next))
        } else if !next.localTerminal, !next.localStopRequested {
            next.localStopRequested = true
            effects.append(effect(.stopLocalSource, in: next))
        }
        return settleEvidence(next, nowMs: nowMs, effects: effects)
    }

    @discardableResult
    public mutating func onIngressFreezeFailed(
        epoch: Sequence,
        sourceGeneration: Sequence,
        sourceRole: Role
    ) -> Reduction {
        guard ownsSource(epoch, sourceGeneration, sourceRole), state.phase == .freezing else {
            return unchanged(.staleCallback)
        }
        var failed = state
        failed.phase = .failed
        failed.failure = .ingressFreezeFailed
        return applied(failed, [effect(.failClosed, in: failed)])
    }

    /// Atomically fences acquisition and records that the frozen route had no exact v2 control
    /// owner. This is deliberately not expressed as `onIngressFrozen` followed by separate remote
    /// evidence: the first input could otherwise emit C/A on a route that has no peer.
    @discardableResult
    public mutating func onIngressFrozenWithoutRemoteOwner(
        epoch: Sequence,
        sourceGeneration: Sequence,
        sourceRole: Role,
        nowMs: Milliseconds
    ) -> Reduction {
        guard ownsSource(epoch, sourceGeneration, sourceRole), state.phase == .freezing else {
            return unchanged(.staleCallback)
        }
        if let timedOut = timeoutIfDue(nowMs) { return timedOut }

        var next = state
        next.ingressFrozen = true
        if next.remoteCloseEvidence == nil { next.remoteCloseEvidence = .noRemoteOwner }
        next.controlTransmitStatus = .accepted
        next.controlTransmitAccepted = true
        next.localStopRequested = true
        let effects = next.localTerminal ? [] : [effect(.stopLocalSource, in: next)]
        return settleEvidence(next, nowMs: nowMs, effects: effects)
    }

    @discardableResult
    public mutating func onControlTransmitResult(
        epoch: Sequence,
        sourceGeneration: Sequence,
        sourceRole: Role,
        frame: ControlFrame,
        attempt: Sequence,
        result: ControlTransmitResult,
        nowMs: Milliseconds
    ) -> Reduction {
        guard ownsSource(epoch, sourceGeneration, sourceRole),
              Self.isStopping(state.phase),
              state.controlFrame == frame,
              state.controlAttempt == attempt,
              state.controlTransmitStatus == .inFlight else {
            return unchanged(.staleCallback)
        }
        if let timedOut = timeoutIfDue(nowMs) { return timedOut }

        switch result {
        case .terminalFailure:
            guard state.controlTransmitAccepted else {
                var failed = state
                failed.phase = .failed
                failed.failure = .controlTransmitFailed
                return applied(failed, [effect(.failClosed, in: failed)])
            }
            var retained = state
            retained.controlTransmitStatus = .accepted
            return settleEvidence(retained, nowMs: nowMs, effects: [])

        case .accepted:
            var next = state
            next.controlTransmitStatus = .accepted
            next.controlTransmitAccepted = true
            var effects: [Effect] = []
            if frame == .closeAck, !next.localTerminal, !next.localStopRequested {
                next.localStopRequested = true
                effects.append(effect(.stopLocalSource, in: next))
            }
            return settleEvidence(next, nowMs: nowMs, effects: effects)

        case .retryableFailure:
            var retry = state
            retry.controlAttempt = retry.controlAttempt.next()
            retry.controlTransmitStatus = .retryWait
            return settleEvidence(
                retry,
                nowMs: nowMs,
                effects: [controlEffect(.scheduleControlRetry, in: retry, deadlineMs: retry.stopDeadlineMs)]
            )
        }
    }

    @discardableResult
    public mutating func onControlTransmitRetry(
        epoch: Sequence,
        sourceGeneration: Sequence,
        sourceRole: Role,
        frame: ControlFrame,
        attempt: Sequence,
        nowMs: Milliseconds
    ) -> Reduction {
        guard ownsSource(epoch, sourceGeneration, sourceRole),
              Self.isStopping(state.phase),
              state.controlFrame == frame,
              state.controlAttempt == attempt,
              state.controlTransmitStatus == .retryWait else {
            return unchanged(.staleCallback)
        }
        if let timedOut = timeoutIfDue(nowMs) { return timedOut }
        var next = state
        next.controlTransmitStatus = .inFlight
        let effectType: EffectType = frame == .closeRequest ? .requestRemoteStop : .acknowledgeRemoteStop
        return applied(next, [controlEffect(effectType, in: next)])
    }

    @discardableResult
    public mutating func onDuplicateRemoteIntent(
        epoch: Sequence,
        sourceGeneration: Sequence,
        sourceRole: Role,
        nowMs: Milliseconds
    ) -> Reduction {
        guard ownsSource(epoch, sourceGeneration, sourceRole),
              Self.isStopping(state.phase),
              state.controlFrame == .closeAck else {
            return unchanged(.staleCallback)
        }
        if let timedOut = timeoutIfDue(nowMs) { return timedOut }
        guard state.ingressFrozen, !state.localTerminal else { return unchanged(.coalesced) }
        // Core Bluetooth completion callbacks identify the characteristic, not an application
        // attempt. Never overlap two A frames: the in-flight result either accepts this duplicate
        // too or schedules the same-token retry without callback ambiguity.
        guard state.controlTransmitStatus != .inFlight else { return unchanged(.coalesced) }

        var next = state
        next.controlAttempt = state.controlAttempt.isZero ? .one : state.controlAttempt.next()
        next.controlTransmitStatus = .inFlight
        var effects: [Effect] = []
        if state.controlTransmitStatus == .retryWait {
            effects.append(controlEffect(.cancelControlRetry, in: state))
        }
        effects.append(controlEffect(.acknowledgeRemoteStop, in: next))
        return applied(next, effects)
    }

    @discardableResult
    public mutating func onLocalTerminal(
        epoch: Sequence,
        sourceGeneration: Sequence,
        sourceRole: Role,
        nowMs: Milliseconds
    ) -> Reduction {
        guard ownsSource(epoch, sourceGeneration, sourceRole), Self.isStopping(state.phase) else {
            return unchanged(.staleCallback)
        }
        if let timedOut = timeoutIfDue(nowMs) { return timedOut }
        guard !state.localTerminal else { return unchanged(.coalesced) }
        var next = state
        next.localTerminal = true
        next.localStopRequested = true
        let effects = state.localOwnersZero ? [] : [effect(.verifyLocalOwners, in: next)]
        return settleEvidence(next, nowMs: nowMs, effects: effects)
    }

    @discardableResult
    public mutating func onLocalOwnerCount(
        epoch: Sequence,
        sourceGeneration: Sequence,
        sourceRole: Role,
        ownerCount: Int,
        nowMs: Milliseconds
    ) -> Reduction {
        precondition(ownerCount >= 0, "ownerCount must be non-negative")
        guard ownsSource(epoch, sourceGeneration, sourceRole),
              Self.acceptsLocalOwnerEvidence(state.phase) else {
            return unchanged(.staleCallback)
        }
        if Self.isStopping(state.phase), let timedOut = timeoutIfDue(nowMs) { return timedOut }
        if ownerCount > 1 {
            var failed = state
            failed.phase = .failed
            failed.failure = .impossibleLocalOwnerCount
            return applied(failed, [effect(.failClosed, in: failed)])
        }
        if state.localOwnersZero, ownerCount != 0 {
            var failed = state
            failed.phase = .failed
            failed.failure = .contradictoryLocalOwnerEvidence
            return applied(failed, [effect(.failClosed, in: failed)])
        }
        guard ownerCount == 0, !state.localOwnersZero else { return unchanged(.coalesced) }
        var next = state
        next.localOwnersZero = true
        return settleEvidence(next, nowMs: nowMs, effects: [])
    }

    @discardableResult
    public mutating func onRemoteClosedEvidence(
        epoch: Sequence,
        sourceGeneration: Sequence,
        stoppedRole: Role,
        evidence: RemoteCloseEvidence,
        nowMs: Milliseconds
    ) -> Reduction {
        guard ownsSourceTokens(epoch, sourceGeneration), Self.isStopping(state.phase) else {
            return unchanged(.staleCallback)
        }
        if let timedOut = timeoutIfDue(nowMs) { return timedOut }
        guard stoppedRole == state.sourceRole else {
            var failed = state
            failed.phase = .failed
            failed.failure = .contradictoryRemoteEvidence
            return applied(failed, [effect(.failClosed, in: failed)])
        }
        if let accepted = state.remoteCloseEvidence {
            guard accepted == evidence else {
                var failed = state
                failed.phase = .failed
                failed.failure = .contradictoryRemoteEvidence
                return applied(failed, [effect(.failClosed, in: failed)])
            }
            return unchanged(.coalesced)
        }

        var next = state
        next.remoteCloseEvidence = evidence
        var effects: [Effect] = []
        if state.controlFrame == .closeRequest {
            if state.controlTransmitStatus == .retryWait {
                effects.append(controlEffect(.cancelControlRetry, in: state))
            }
            next.controlTransmitStatus = .accepted
            next.controlTransmitAccepted = true
            if next.ingressFrozen, !next.localTerminal, !next.localStopRequested {
                next.localStopRequested = true
                effects.append(effect(.stopLocalSource, in: next))
            }
        }
        return settleEvidence(next, nowMs: nowMs, effects: effects)
    }

    @discardableResult
    public mutating func onRadioOrPowerLoss(
        epoch: Sequence,
        sourceGeneration: Sequence,
        sourceRole: Role,
        nowMs: Milliseconds
    ) -> Reduction {
        guard ownsSource(epoch, sourceGeneration, sourceRole), Self.isStopping(state.phase) else {
            return unchanged(.staleCallback)
        }
        if let timedOut = timeoutIfDue(nowMs) { return timedOut }
        guard !state.localTerminal || state.remoteCloseEvidence == nil else {
            return unchanged(.coalesced)
        }
        var next = state
        next.localTerminal = true
        if next.remoteCloseEvidence == nil { next.remoteCloseEvidence = .radioOrPowerLoss }
        next.controlTransmitStatus = .accepted
        next.controlTransmitAccepted = true
        next.localStopRequested = true
        let effects = state.localOwnersZero ? [] : [effect(.verifyLocalOwners, in: next)]
        return settleEvidence(next, nowMs: nowMs, effects: effects)
    }

    @discardableResult
    public mutating func onStopTimeout(
        epoch: Sequence,
        sourceGeneration: Sequence,
        sourceRole: Role,
        nowMs: Milliseconds
    ) -> Reduction {
        guard ownsSource(epoch, sourceGeneration, sourceRole), Self.isStopping(state.phase) else {
            return unchanged(.staleCallback)
        }
        guard nowMs >= state.stopDeadlineMs! else { return unchanged(.notDue) }
        var failed = state
        failed.phase = .failed
        failed.failure = .stopTimeout
        return applied(failed, [effect(.failClosed, in: failed)])
    }

    @discardableResult
    public mutating func onDrainDeadline(
        epoch: Sequence,
        sourceGeneration: Sequence,
        sourceRole: Role,
        nowMs: Milliseconds
    ) -> Reduction {
        guard ownsSource(epoch, sourceGeneration, sourceRole), state.phase == .draining else {
            return unchanged(.staleCallback)
        }
        guard nowMs >= state.drainDeadlineMs! else { return unchanged(.notDue) }
        var quiescent = state
        quiescent.phase = .quiescent
        return applied(quiescent, [
            effect(.cancelDrainDeadline, in: quiescent),
            effect(.quiescentReached, in: quiescent, target: true)
        ])
    }

    @discardableResult
    public mutating func beginTargetStart(
        epoch: Sequence,
        targetGeneration: Sequence,
        targetRole: Role
    ) -> Reduction {
        guard ownsTarget(epoch, targetGeneration, targetRole), state.phase == .quiescent else {
            return unchanged(.staleCallback)
        }
        var starting = state
        starting.phase = .starting
        return applied(starting, [effect(.startTarget, in: starting, target: true)])
    }

    @discardableResult
    public mutating func onTargetActive(
        epoch: Sequence,
        targetGeneration: Sequence,
        targetRole: Role
    ) -> Reduction {
        guard ownsTarget(epoch, targetGeneration, targetRole), state.phase == .starting else {
            return unchanged(.staleCallback)
        }
        let active = State.active(targetRole, lastEpoch: state.epoch, generation: targetGeneration)
        let result = Effect(
            type: .targetActive,
            epoch: state.epoch,
            generation: targetGeneration,
            role: targetRole,
            deadlineMs: nil,
            controlFrame: nil,
            controlAttempt: .zero
        )
        return applied(active, [result])
    }

    @discardableResult
    public mutating func onTargetStartFailed(
        epoch: Sequence,
        targetGeneration: Sequence,
        targetRole: Role
    ) -> Reduction {
        guard ownsTarget(epoch, targetGeneration, targetRole), state.phase == .starting else {
            return unchanged(.staleCallback)
        }
        var failed = state
        failed.phase = .failed
        failed.failure = .targetStartFailed
        return applied(failed, [effect(.failClosed, in: failed, target: true)])
    }

    @discardableResult
    public mutating func close() -> Reduction {
        guard state.phase != .closed else { return unchanged(.coalesced) }
        let wasActive = state.phase == .active
        let sourceRole = wasActive ? state.activeRole! : state.sourceRole!
        let sourceGeneration = wasActive ? state.activeGeneration! : state.sourceGeneration!
        let targetRole = wasActive ? state.activeRole! : state.targetRole!
        let targetGeneration = wasActive ? state.activeGeneration!.next() : state.targetGeneration!
        let closeEpoch = wasActive ? state.epoch.next() : state.epoch
        var closed = state
        closed.phase = .closed
        closed.epoch = closeEpoch
        closed.activeRole = nil
        closed.activeGeneration = nil
        closed.sourceRole = sourceRole
        closed.sourceGeneration = sourceGeneration
        closed.targetRole = targetRole
        closed.targetGeneration = targetGeneration
        closed.ingressFrozen = true
        closed.stopDeadlineMs = nil
        closed.drainDurationMs = nil
        closed.drainDeadlineMs = nil
        closed.localStopRequested = true
        let closeEffect = Effect(
            type: .closeAll,
            epoch: closeEpoch,
            generation: sourceGeneration,
            role: sourceRole,
            deadlineMs: nil,
            controlFrame: nil,
            controlAttempt: .zero
        )
        return applied(closed, [closeEffect])
    }

    private mutating func settleEvidence(
        _ candidate: State,
        nowMs: Milliseconds,
        effects: [Effect]
    ) -> Reduction {
        var next = candidate
        if !next.ingressFrozen {
            next.phase = .freezing
            return applied(next, effects)
        }
        if !controlHandshakeSatisfied(next) || !next.localStopRequested {
            next.phase = .waitingControlHandshake
            return applied(next, effects)
        }
        if !next.localTerminal || !next.localOwnersZero {
            next.phase = .waitingLocalTerminal
            return applied(next, effects)
        }
        if next.remoteCloseEvidence == nil {
            next.phase = .waitingRemoteAck
            return applied(next, effects)
        }

        let drainDeadline = Self.addingClamped(nowMs, next.drainDurationMs!)
        next.phase = .draining
        next.drainDeadlineMs = drainDeadline
        var nextEffects = effects
        if candidate.controlTransmitStatus == .retryWait {
            nextEffects.append(controlEffect(.cancelControlRetry, in: candidate))
        }
        nextEffects.append(effect(.cancelStopTimeout, in: next))
        nextEffects.append(effect(.armDrainDeadline, in: next, deadlineMs: drainDeadline))
        return applied(next, nextEffects)
    }

    private func ownsSource(_ epoch: Sequence, _ generation: Sequence, _ role: Role) -> Bool {
        ownsSourceTokens(epoch, generation) && role == state.sourceRole
    }

    private func ownsSourceTokens(_ epoch: Sequence, _ generation: Sequence) -> Bool {
        epoch == state.epoch && generation == state.sourceGeneration
    }

    private func ownsTarget(_ epoch: Sequence, _ generation: Sequence, _ role: Role) -> Bool {
        epoch == state.epoch && generation == state.targetGeneration && role == state.targetRole
    }

    private mutating func timeoutIfDue(_ nowMs: Milliseconds) -> Reduction? {
        guard let deadline = state.stopDeadlineMs, nowMs >= deadline else { return nil }
        var failed = state
        failed.phase = .failed
        failed.failure = .stopTimeout
        return applied(failed, [effect(.failClosed, in: failed)])
    }

    private mutating func applied(_ next: State, _ effects: [Effect]) -> Reduction {
        state = next
        return Reduction(state: next, outcome: .applied, effects: effects)
    }

    private func unchanged(_ outcome: Outcome) -> Reduction {
        Reduction(state: state, outcome: outcome, effects: [])
    }

    private func effect(
        _ type: EffectType,
        in state: State,
        target: Bool = false,
        deadlineMs: Milliseconds? = nil
    ) -> Effect {
        Effect(
            type: type,
            epoch: state.epoch,
            generation: target ? state.targetGeneration! : state.sourceGeneration!,
            role: target ? state.targetRole! : state.sourceRole!,
            deadlineMs: deadlineMs,
            controlFrame: nil,
            controlAttempt: .zero
        )
    }

    private func controlEffect(
        _ type: EffectType,
        in state: State,
        deadlineMs: Milliseconds? = nil
    ) -> Effect {
        Effect(
            type: type,
            epoch: state.epoch,
            generation: state.sourceGeneration!,
            role: state.sourceRole!,
            deadlineMs: deadlineMs,
            controlFrame: state.controlFrame,
            controlAttempt: state.controlAttempt
        )
    }

    private func beginControlTransmit(_ state: inout State) {
        state.controlAttempt = state.controlAttempt.isZero ? .one : state.controlAttempt.next()
        state.controlTransmitStatus = .inFlight
    }

    private func controlHandshakeSatisfied(_ state: State) -> Bool {
        guard let frame = state.controlFrame else { return true }
        return frame == .closeRequest ? state.remoteCloseEvidence != nil : state.controlTransmitAccepted
    }

    private static func isStopping(_ phase: Phase) -> Bool {
        phase == .freezing || phase == .waitingControlHandshake ||
            phase == .waitingLocalTerminal || phase == .waitingRemoteAck
    }

    private static func acceptsLocalOwnerEvidence(_ phase: Phase) -> Bool {
        isStopping(phase) || phase == .draining || phase == .quiescent || phase == .starting
    }

    private static func addingClamped(_ lhs: UInt64, _ rhs: UInt64) -> UInt64 {
        UInt64.max - lhs < rhs ? UInt64.max : lhs + rhs
    }
}
