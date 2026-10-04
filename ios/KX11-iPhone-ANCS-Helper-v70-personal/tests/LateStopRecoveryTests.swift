// Executes the production Swift gate and reducer, not a Python reimplementation.
@main
struct LateStopRecoveryTests {
    static func main() {
        let role = BleRoleSwitchPolicy.Role.helperPeripheralAndroidCentral
        var policy = BleRoleSwitchPolicy(activeRole: role)
        policy.requestSameRoleRestart(nowMs: 0, stopTimeoutMs: 15_000, drainDurationMs: 750)
        let epoch = policy.state.epoch
        let generation = policy.state.sourceGeneration!
        policy.onStopTimeout(epoch: epoch, sourceGeneration: generation, sourceRole: role, nowMs: 246_195)
        assert(policy.state.failure == .stopTimeout)
        func observe(_ gate: inout HelperLateStopRecoveryGate, _ evidence: HelperLateStopRecoveryGate.Evidence,
                     eventEpoch: BleRoleSwitchPolicy.Sequence? = nil, local: Bool = true,
                     timeout: Bool = true) -> Bool {
            gate.observe(state: policy.state, epoch: eventEpoch ?? epoch, generation: generation,
                         role: role, evidence: evidence, localOnly: local, runtimeFailureIsStopTimeout: timeout)
        }
        // Both callback orders after the observed 246s pause require all three facts.
        for terminalFirst in [false, true] {
            var gate = HelperLateStopRecoveryGate()
            assert(!observe(&gate, .frozen))
            assert(!observe(&gate, terminalFirst ? .terminal : .owners(0)))
            assert(observe(&gate, terminalFirst ? .owners(0) : .terminal))
            assert(!observe(&gate, .owners(0))) // duplicate cannot enqueue another retry
            assert(gate.consumed)
            // A retry timing out again does not spin until an authenticated peer is seen.
            var second = BleRoleSwitchPolicy(activeRole: role)
            second.restoreDrainLocalOnly(role: role, epoch: epoch.next(), sourceGeneration: generation,
                                         targetGeneration: generation.next(), nowMs: 246_195,
                                         stopTimeoutMs: 15_000, drainDurationMs: 750)
            second.onStopTimeout(epoch: epoch.next(), sourceGeneration: generation, sourceRole: role, nowMs: 300_000)
            assert(!gate.observe(state: second.state, epoch: epoch.next(), generation: generation, role: role,
                                 evidence: .frozen, localOnly: true, runtimeFailureIsStopTimeout: true))
        }
        var stale = HelperLateStopRecoveryGate()
        assert(!observe(&stale, .frozen, eventEpoch: epoch.next()))
        assert(!observe(&stale, .terminal))
        assert(!observe(&stale, .owners(0))) // stale freeze never proves current freeze
        assert(!observe(&stale, .frozen, local: false))
        assert(!observe(&stale, .frozen, timeout: false)) // persistence failures are not retried
        assert(observe(&stale, .frozen))
        for count in [-1, 1, 2] {
            var conflict = HelperLateStopRecoveryGate()
            assert(!observe(&conflict, .frozen))
            assert(!observe(&conflict, .owners(0)))
            assert(!observe(&conflict, .owners(count)))
            assert(!observe(&conflict, .terminal))
            assert(!observe(&conflict, .owners(0))) // contradictory ownership stays closed
        }
        // Admission never mutates the failed reducer or skips the new-epoch drain.
        assert(policy.state.phase == .failed)
        let retry = policy.restoreDrainLocalOnly(role: role, epoch: epoch.next(), sourceGeneration: generation,
                                                 targetGeneration: generation.next(), nowMs: 246_195,
                                                 stopTimeoutMs: 15_000, drainDurationMs: 750)
        assert(retry.state.phase == .freezing)
        assert(!retry.effects.contains { $0.type == .startTarget })
        policy.onIngressFrozenWithoutRemoteOwner(epoch: epoch.next(), sourceGeneration: generation, sourceRole: role, nowMs: 246_196)
        policy.onLocalTerminal(epoch: epoch.next(), sourceGeneration: generation, sourceRole: role, nowMs: 246_197)
        policy.onLocalOwnerCount(epoch: epoch.next(), sourceGeneration: generation, sourceRole: role, ownerCount: 0, nowMs: 246_198)
        assert(policy.state.phase == .draining)
        assert(policy.state.drainDeadlineMs == 246_948)
        print("PASS: production Swift late-stop gate, stale/conflicting evidence, bounded retry and full fresh drain")
    }
}
