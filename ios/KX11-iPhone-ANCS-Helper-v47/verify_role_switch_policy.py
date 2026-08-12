#!/usr/bin/env python3
"""Linux replay verifier for the Swift/Java confirmed-quiescence switch contract."""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from enum import Enum
import json
from pathlib import Path


class Role(str, Enum):
    A = "helperPeripheralAndroidCentral"
    B = "helperCentralAndroidPeripheral"


class Phase(str, Enum):
    ACTIVE = "active"
    FREEZING = "freezing"
    CONTROL = "waitingControlHandshake"
    LOCAL = "waitingLocalTerminal"
    REMOTE = "waitingRemoteAck"
    DRAINING = "draining"
    QUIESCENT = "quiescent"
    STARTING = "starting"
    FAILED = "failed"
    CLOSED = "closed"


class Evidence(str, Enum):
    ACK = "confirmedAck"
    PEER_INTENT = "peerCommittedIntent"
    PEER_SAME_ROLE = "peerSameRoleRetained"
    NO_OWNER = "noRemoteOwner"
    TERMINAL = "remoteAlreadyTerminal"
    RADIO = "radioOrPowerLoss"


class Frame(str, Enum):
    C = "closeRequest"
    A = "closeAck"


class TxStatus(str, Enum):
    IDLE = "idle"
    IN_FLIGHT = "inFlight"
    RETRY_WAIT = "retryWait"
    ACCEPTED = "accepted"


@dataclass(eq=True)
class State:
    phase: Phase
    epoch: int
    desired: Role | None
    active: Role | None
    active_generation: int | None
    source: Role | None = None
    source_generation: int | None = None
    target: Role | None = None
    target_generation: int | None = None
    ingress_frozen: bool = False
    local_terminal: bool = False
    local_owners_zero: bool = False
    remote_evidence: Evidence | None = None
    stop_deadline: int | None = None
    drain_duration: int | None = None
    drain_deadline: int | None = None
    failure: str = "none"
    control_frame: Frame | None = None
    control_attempt: int = 0
    control_status: TxStatus = TxStatus.IDLE
    control_accepted: bool = False
    local_stop_requested: bool = False

    @classmethod
    def active_state(cls, role: Role, epoch: int = 0, generation: int = 1) -> "State":
        assert generation > 0
        return cls(Phase.ACTIVE, epoch, role, role, generation)


@dataclass(eq=True)
class Reduction:
    outcome: str
    effects: list[str]


class Policy:
    def __init__(self, role: Role = Role.A, epoch: int = 0, generation: int = 1):
        self.state = State.active_state(role, epoch, generation)

    def request(
        self, target: Role, now: int, timeout: int, drain: int,
        remote=False, same_restart=False
    ):
        s = self.state
        assert timeout > 0 and drain > 0
        if s.phase in (Phase.FAILED, Phase.CLOSED):
            return Reduction("rejectedTerminal", [])
        if s.phase != Phase.ACTIVE:
            return Reduction("coalesced" if target == s.target else "rejectedConflict", [])
        if target == s.active and not same_restart:
            return Reduction("rejectedConflict" if remote else "coalesced", [])
        assert s.active is not None and s.active_generation is not None
        self.state = State(
            Phase.FREEZING,
            s.epoch + 1,
            target,
            None,
            None,
            source=s.active,
            source_generation=s.active_generation,
            target=target,
            target_generation=s.active_generation + 1,
            remote_evidence=(Evidence.PEER_SAME_ROLE if same_restart else
                             Evidence.PEER_INTENT if remote else None),
            stop_deadline=now + timeout,
            drain_duration=drain,
            control_frame=None if same_restart else Frame.A if remote else Frame.C,
        )
        return Reduction("applied", ["freezeSourceIngress", "armStopTimeout"])

    def request_same_role_restart(self, now: int, timeout: int, drain: int):
        assert self.state.active is not None
        return self.request(
            self.state.active, now, timeout, drain, same_restart=True
        )

    def restore(
        self, source: Role, desired: Role, epoch: int, source_gen: int, target_gen: int,
        now: int, timeout: int, drain: int, remote=False, local_only=False
    ):
        assert epoch > 0 and source_gen > 0 and target_gen > 0 and timeout > 0 and drain > 0
        self.state = State(
            Phase.FREEZING,
            epoch,
            desired,
            None,
            None,
            source=source,
            source_generation=source_gen,
            target=desired,
            target_generation=target_gen,
            remote_evidence=(Evidence.PEER_SAME_ROLE if local_only else
                             Evidence.PEER_INTENT if remote else None),
            stop_deadline=now + timeout,
            drain_duration=drain,
            control_frame=None if local_only else Frame.A if remote else Frame.C,
        )
        return Reduction("applied", ["freezeSourceIngress", "armStopTimeout"])

    def ingress(self, epoch: int, gen: int, role: Role, now: int):
        if not self._owns(epoch, gen, role) or not self._stopping():
            return Reduction("staleCallback", [])
        if (due := self._timeout(now)) is not None:
            return due
        s = self.state
        if s.phase != Phase.FREEZING:
            return Reduction("coalesced" if s.ingress_frozen else "staleCallback", [])
        s.ingress_frozen = True
        effects: list[str] = []
        if not s.local_terminal and s.control_frame == Frame.C and s.remote_evidence is None:
            self._begin_tx()
            effects.append("requestRemoteStop")
        elif not s.local_terminal and s.control_frame == Frame.A and not s.control_accepted:
            self._begin_tx()
            effects.append("acknowledgeRemoteStop")
        elif not s.local_terminal and not s.local_stop_requested:
            s.local_stop_requested = True
            effects.append("stopLocalSource")
        return self._settle(now, effects)

    def ingress_freeze_failed(self, epoch: int, gen: int, role: Role):
        if not self._owns(epoch, gen, role) or self.state.phase != Phase.FREEZING:
            return Reduction("staleCallback", [])
        self.state.phase, self.state.failure = Phase.FAILED, "ingressFreezeFailed"
        return Reduction("applied", ["failClosed"])

    def ingress_without_owner(self, epoch: int, gen: int, role: Role, now: int):
        if not self._owns(epoch, gen, role) or self.state.phase != Phase.FREEZING:
            return Reduction("staleCallback", [])
        if (due := self._timeout(now)) is not None:
            return due
        s = self.state
        s.ingress_frozen = True
        if s.remote_evidence is None:
            s.remote_evidence = Evidence.NO_OWNER
        s.control_status = TxStatus.ACCEPTED
        s.control_accepted = True
        s.local_stop_requested = True
        return self._settle(now, [] if s.local_terminal else ["stopLocalSource"])

    def tx_result(
        self, epoch: int, gen: int, role: Role, frame: Frame, attempt: int,
        result: str, now: int
    ):
        s = self.state
        if (not self._owns(epoch, gen, role) or not self._stopping() or
                s.control_frame != frame or s.control_attempt != attempt or
                s.control_status != TxStatus.IN_FLIGHT):
            return Reduction("staleCallback", [])
        if (due := self._timeout(now)) is not None:
            return due
        if result == "TERMINAL_FAILURE":
            if not s.control_accepted:
                s.phase, s.failure = Phase.FAILED, "controlTransmitFailed"
                return Reduction("applied", ["failClosed"])
            s.control_status = TxStatus.ACCEPTED
            return self._settle(now, [])
        if result == "ACCEPTED":
            s.control_status, s.control_accepted = TxStatus.ACCEPTED, True
            effects: list[str] = []
            if frame == Frame.A and not s.local_terminal and not s.local_stop_requested:
                s.local_stop_requested = True
                effects.append("stopLocalSource")
            return self._settle(now, effects)
        assert result == "RETRYABLE_FAILURE"
        s.control_attempt += 1
        s.control_status = TxStatus.RETRY_WAIT
        return self._settle(now, ["scheduleControlRetry"])

    def tx_retry(self, epoch: int, gen: int, role: Role, frame: Frame, attempt: int, now: int):
        s = self.state
        if (not self._owns(epoch, gen, role) or not self._stopping() or
                s.control_frame != frame or s.control_attempt != attempt or
                s.control_status != TxStatus.RETRY_WAIT):
            return Reduction("staleCallback", [])
        if (due := self._timeout(now)) is not None:
            return due
        s.control_status = TxStatus.IN_FLIGHT
        return Reduction("applied", ["requestRemoteStop" if frame == Frame.C
                                     else "acknowledgeRemoteStop"])

    def duplicate_remote(self, epoch: int, gen: int, role: Role, now: int):
        s = self.state
        if not self._owns(epoch, gen, role) or not self._stopping() or s.control_frame != Frame.A:
            return Reduction("staleCallback", [])
        if (due := self._timeout(now)) is not None:
            return due
        if not s.ingress_frozen or s.local_terminal:
            return Reduction("coalesced", [])
        if s.control_status == TxStatus.IN_FLIGHT:
            return Reduction("coalesced", [])
        effects = ["cancelControlRetry"] if s.control_status == TxStatus.RETRY_WAIT else []
        s.control_attempt = 1 if s.control_attempt == 0 else s.control_attempt + 1
        s.control_status = TxStatus.IN_FLIGHT
        effects.append("acknowledgeRemoteStop")
        return Reduction("applied", effects)

    def terminal(self, epoch: int, gen: int, role: Role, now: int):
        if not self._owns(epoch, gen, role) or not self._stopping():
            return Reduction("staleCallback", [])
        if (due := self._timeout(now)) is not None:
            return due
        s = self.state
        if s.local_terminal:
            return Reduction("coalesced", [])
        s.local_terminal = s.local_stop_requested = True
        return self._settle(now, [] if s.local_owners_zero else ["verifyLocalOwners"])

    def owners(self, epoch: int, gen: int, role: Role, count: int, now: int):
        assert count >= 0
        s = self.state
        if not self._owns(epoch, gen, role) or not self._accepts_owners():
            return Reduction("staleCallback", [])
        if self._stopping() and (due := self._timeout(now)) is not None:
            return due
        if count > 1:
            s.phase, s.failure = Phase.FAILED, "impossibleLocalOwnerCount"
            return Reduction("applied", ["failClosed"])
        if s.local_owners_zero and count != 0:
            s.phase, s.failure = Phase.FAILED, "contradictoryLocalOwnerEvidence"
            return Reduction("applied", ["failClosed"])
        if count == 1 or s.local_owners_zero:
            return Reduction("coalesced", [])
        s.local_owners_zero = True
        return self._settle(now, [])

    def remote(self, epoch: int, gen: int, stopped: Role, evidence: Evidence, now: int):
        s = self.state
        if not self._owns_tokens(epoch, gen) or not self._stopping():
            return Reduction("staleCallback", [])
        if (due := self._timeout(now)) is not None:
            return due
        if stopped != s.source:
            s.phase, s.failure = Phase.FAILED, "contradictoryRemoteEvidence"
            return Reduction("applied", ["failClosed"])
        if s.remote_evidence is not None:
            if s.remote_evidence != evidence:
                s.phase, s.failure = Phase.FAILED, "contradictoryRemoteEvidence"
                return Reduction("applied", ["failClosed"])
            return Reduction("coalesced", [])
        s.remote_evidence = evidence
        effects: list[str] = []
        if s.control_frame == Frame.C:
            if s.control_status == TxStatus.RETRY_WAIT:
                effects.append("cancelControlRetry")
            s.control_status, s.control_accepted = TxStatus.ACCEPTED, True
            if s.ingress_frozen and not s.local_terminal and not s.local_stop_requested:
                s.local_stop_requested = True
                effects.append("stopLocalSource")
        return self._settle(now, effects)

    def radio(self, epoch: int, gen: int, role: Role, now: int):
        if not self._owns(epoch, gen, role) or not self._stopping():
            return Reduction("staleCallback", [])
        if (due := self._timeout(now)) is not None:
            return due
        s = self.state
        if s.local_terminal and s.remote_evidence is not None:
            return Reduction("coalesced", [])
        s.local_terminal = s.local_stop_requested = s.control_accepted = True
        s.control_status = TxStatus.ACCEPTED
        if s.remote_evidence is None:
            s.remote_evidence = Evidence.RADIO
        return self._settle(now, [] if s.local_owners_zero else ["verifyLocalOwners"])

    def stop_due(self, epoch: int, gen: int, role: Role, now: int):
        if not self._owns(epoch, gen, role) or not self._stopping():
            return Reduction("staleCallback", [])
        assert self.state.stop_deadline is not None
        if now < self.state.stop_deadline:
            return Reduction("notDue", [])
        return self._fail_timeout()

    def drain_due(self, epoch: int, gen: int, role: Role, now: int):
        s = self.state
        if not self._owns(epoch, gen, role) or s.phase != Phase.DRAINING:
            return Reduction("staleCallback", [])
        assert s.drain_deadline is not None
        if now < s.drain_deadline:
            return Reduction("notDue", [])
        s.phase = Phase.QUIESCENT
        return Reduction("applied", ["cancelDrainDeadline", "quiescentReached"])

    def begin(self, epoch: int, gen: int, role: Role):
        if not self._owns_target(epoch, gen, role) or self.state.phase != Phase.QUIESCENT:
            return Reduction("staleCallback", [])
        self.state.phase = Phase.STARTING
        return Reduction("applied", ["startTarget"])

    def active(self, epoch: int, gen: int, role: Role):
        if not self._owns_target(epoch, gen, role) or self.state.phase != Phase.STARTING:
            return Reduction("staleCallback", [])
        self.state = State.active_state(role, epoch, gen)
        return Reduction("applied", ["targetActive"])

    def target_failed(self, epoch: int, gen: int, role: Role):
        if not self._owns_target(epoch, gen, role) or self.state.phase != Phase.STARTING:
            return Reduction("staleCallback", [])
        self.state.phase, self.state.failure = Phase.FAILED, "targetStartFailed"
        return Reduction("applied", ["failClosed"])

    def _settle(self, now: int, effects: list[str]):
        s = self.state
        if not s.ingress_frozen:
            s.phase = Phase.FREEZING
        elif not self._handshake() or not s.local_stop_requested:
            s.phase = Phase.CONTROL
        elif not s.local_terminal or not s.local_owners_zero:
            s.phase = Phase.LOCAL
        elif s.remote_evidence is None:
            s.phase = Phase.REMOTE
        else:
            assert s.drain_duration is not None
            if s.control_status == TxStatus.RETRY_WAIT:
                effects.append("cancelControlRetry")
            s.phase, s.drain_deadline = Phase.DRAINING, now + s.drain_duration
            effects += ["cancelStopTimeout", "armDrainDeadline"]
        return Reduction("applied", effects)

    def _begin_tx(self):
        s = self.state
        s.control_attempt = 1 if s.control_attempt == 0 else s.control_attempt + 1
        s.control_status = TxStatus.IN_FLIGHT

    def _handshake(self):
        s = self.state
        return (s.control_frame is None or
                (s.remote_evidence is not None if s.control_frame == Frame.C
                 else s.control_accepted))

    def _owns_tokens(self, epoch, gen):
        return epoch == self.state.epoch and gen == self.state.source_generation

    def _owns(self, epoch, gen, role):
        return self._owns_tokens(epoch, gen) and role == self.state.source

    def _owns_target(self, epoch, gen, role):
        return epoch == self.state.epoch and gen == self.state.target_generation and role == self.state.target

    def _stopping(self):
        return self.state.phase in (Phase.FREEZING, Phase.CONTROL, Phase.LOCAL, Phase.REMOTE)

    def _accepts_owners(self):
        return self._stopping() or self.state.phase in (Phase.DRAINING, Phase.QUIESCENT, Phase.STARTING)

    def _timeout(self, now):
        assert self.state.stop_deadline is not None
        return self._fail_timeout() if now >= self.state.stop_deadline else None

    def _fail_timeout(self):
        self.state.phase, self.state.failure = Phase.FAILED, "stopTimeout"
        return Reduction("applied", ["failClosed"])


ROLES = {"A": Role.A, "B": Role.B}
EVIDENCE = {
    "CONFIRMED_ACK": Evidence.ACK,
    "PEER_COMMITTED_INTENT": Evidence.PEER_INTENT,
    "PEER_SAME_ROLE_RETAINED": Evidence.PEER_SAME_ROLE,
    "NO_REMOTE_OWNER": Evidence.NO_OWNER,
    "REMOTE_ALREADY_TERMINAL": Evidence.TERMINAL,
    "RADIO_OR_POWER_LOSS": Evidence.RADIO,
}
FRAMES = {"CLOSE_REQUEST": Frame.C, "CLOSE_ACK": Frame.A}
PHASES = {
    Phase.ACTIVE: "ACTIVE", Phase.FREEZING: "FREEZING",
    Phase.CONTROL: "WAITING_CONTROL_HANDSHAKE", Phase.LOCAL: "WAITING_LOCAL_TERMINAL",
    Phase.REMOTE: "WAITING_REMOTE_ACK", Phase.DRAINING: "DRAINING",
    Phase.QUIESCENT: "QUIESCENT", Phase.STARTING: "STARTING",
    Phase.FAILED: "FAILED", Phase.CLOSED: "CLOSED",
}
OUTCOMES = {
    "applied": "APPLIED", "coalesced": "COALESCED",
    "rejectedConflict": "REJECTED_CONFLICT", "rejectedTerminal": "REJECTED_TERMINAL",
    "staleCallback": "STALE_CALLBACK", "notDue": "NOT_DUE",
}
EFFECTS = {
    "freezeSourceIngress": "FREEZE_SOURCE_INGRESS", "armStopTimeout": "ARM_STOP_TIMEOUT",
    "stopLocalSource": "STOP_LOCAL_SOURCE", "requestRemoteStop": "REQUEST_REMOTE_STOP",
    "acknowledgeRemoteStop": "ACKNOWLEDGE_REMOTE_STOP",
    "scheduleControlRetry": "SCHEDULE_CONTROL_RETRY", "cancelControlRetry": "CANCEL_CONTROL_RETRY",
    "verifyLocalOwners": "VERIFY_LOCAL_OWNERS", "cancelStopTimeout": "CANCEL_STOP_TIMEOUT",
    "armDrainDeadline": "ARM_DRAIN_DEADLINE", "cancelDrainDeadline": "CANCEL_DRAIN_DEADLINE",
    "quiescentReached": "QUIESCENT_REACHED", "startTarget": "START_TARGET",
    "targetActive": "TARGET_ACTIVE", "failClosed": "FAIL_CLOSED", "closeAll": "CLOSE_ALL",
}
FAILURES = {
    "none": "NONE", "stopTimeout": "STOP_TIMEOUT",
    "ingressFreezeFailed": "INGRESS_FREEZE_FAILED",
    "contradictoryRemoteEvidence": "CONTRADICTORY_REMOTE_EVIDENCE",
    "contradictoryLocalOwnerEvidence": "CONTRADICTORY_LOCAL_OWNER_EVIDENCE",
    "impossibleLocalOwnerCount": "IMPOSSIBLE_LOCAL_OWNER_COUNT",
    "controlTransmitFailed": "CONTROL_TRANSMIT_FAILED",
    "targetStartFailed": "TARGET_START_FAILED",
}


def assert_expected(label: str, r: Reduction, s: State, e: dict):
    assert OUTCOMES[r.outcome] == e["outcome"], f"{label}: outcome {r}"
    assert PHASES[s.phase] == e["phase"], f"{label}: phase {s.phase}"
    actual = [EFFECTS[x] for x in r.effects]
    assert actual == e["effects"], f"{label}: effects {actual} != {e['effects']}"
    assert not set(actual).intersection(e.get("forbiddenEffects", [])), f"{label}: forbidden effect"
    fields = {
        "epoch": str(s.epoch), "sourceGeneration": str(s.source_generation),
        "targetGeneration": str(s.target_generation), "controlAttempt": str(s.control_attempt),
        "stopDeadline": s.stop_deadline, "drainDeadline": s.drain_deadline,
        "localTerminal": s.local_terminal, "localOwnersZero": s.local_owners_zero,
        "localStopRequested": s.local_stop_requested,
    }
    for key, value in fields.items():
        if key in e:
            assert value == e[key], f"{label}: {key} {value} != {e[key]}"
    if "activeRole" in e:
        assert s.active == ROLES[e["activeRole"]]
    if "desiredRole" in e:
        assert s.desired == ROLES[e["desiredRole"]]
    if "remoteEvidence" in e:
        assert s.remote_evidence == EVIDENCE[e["remoteEvidence"]]
    if "controlFrame" in e:
        assert s.control_frame == FRAMES[e["controlFrame"]]
    if "failure" in e:
        assert FAILURES[s.failure] == e["failure"]


def replay(p: Policy, step: dict):
    name = step["event"]
    if name in ("requestSwitch", "requestSwitchFromRemoteIntent"):
        return p.request(ROLES[step["target"]], step["now"], step["stopTimeout"],
                         step["drainDuration"], remote=name.endswith("RemoteIntent"))
    e, g = int(step["epoch"]), int(step["generation"])
    if name == "onIngressFrozen":
        return p.ingress(e, g, ROLES[step["role"]], step["now"])
    if name == "onIngressFreezeFailed":
        return p.ingress_freeze_failed(e, g, ROLES[step["role"]])
    if name == "onIngressFrozenWithoutRemoteOwner":
        return p.ingress_without_owner(e, g, ROLES[step["role"]], step["now"])
    if name == "onControlTransmitResult":
        return p.tx_result(e, g, ROLES[step["role"]], FRAMES[step["frame"]],
                           int(step["attempt"]), step["result"], step["now"])
    if name == "onControlTransmitRetry":
        return p.tx_retry(e, g, ROLES[step["role"]], FRAMES[step["frame"]],
                          int(step["attempt"]), step["now"])
    if name == "duplicateRemoteIntent":
        return p.duplicate_remote(e, g, ROLES[step["role"]], step["now"])
    if name == "onLocalTerminal":
        return p.terminal(e, g, ROLES[step["role"]], step["now"])
    if name == "onLocalOwnerCount":
        return p.owners(e, g, ROLES[step["role"]], step["ownerCount"], step["now"])
    if name == "onRemoteClosedEvidence":
        return p.remote(e, g, ROLES[step["stoppedRole"]], EVIDENCE[step["evidence"]], step["now"])
    if name == "onRadioOrPowerLoss":
        return p.radio(e, g, ROLES[step["role"]], step["now"])
    if name == "onStopTimeout":
        return p.stop_due(e, g, ROLES[step["role"]], step["now"])
    if name == "onDrainDeadline":
        return p.drain_due(e, g, ROLES[step["role"]], step["now"])
    if name == "beginTargetStart":
        return p.begin(e, g, ROLES[step["role"]])
    if name == "onTargetActive":
        return p.active(e, g, ROLES[step["role"]])
    if name == "onTargetStartFailed":
        return p.target_failed(e, g, ROLES[step["role"]])
    raise AssertionError(f"unsupported event {name}")


def verify_swift_surface():
    source = Path(__file__).with_name("BleRoleSwitchPolicy.swift").read_text()
    required = (
        "case waitingControlHandshake", "case controlTransmitFailed", "case scheduleControlRetry",
        "public mutating func onControlTransmitResult", "public mutating func onControlTransmitRetry",
        "public mutating func onDuplicateRemoteIntent", "public mutating func onLocalOwnerCount",
        "public mutating func requestSameRoleRestart",
        "public mutating func onIngressFreezeFailed",
        "public mutating func onIngressFrozenWithoutRemoteOwner",
        "next.localStopRequested = true", "state.controlTransmitAccepted",
    )
    for token in required:
        assert token in source, f"missing Swift contract {token}"
    assert "CoreBluetooth" not in "\n".join(x for x in source.splitlines() if x.startswith("import "))


def verify_shared_vectors():
    fixture_path = (Path(__file__).resolve().parents[2] / "app/src/main/java/dezz/status/widget/phone/transport/switching/ble-role-switch-transition-vectors.json")
    fixture = json.loads(fixture_path.read_text())
    assert fixture["schema"] == "ble-role-switch/v2"
    for vector in fixture["vectors"]:
        initial = vector["initial"]
        if initial["kind"] == "active":
            p = Policy(ROLES[initial["role"]], int(initial["lastEpoch"]), int(initial["generation"]))
        else:
            p = Policy()
            p.restore(
                ROLES[initial["sourceRole"]], ROLES[initial["desiredRole"]],
                int(initial["epoch"]), int(initial["sourceGeneration"]), int(initial["targetGeneration"]),
                initial["now"], initial["stopTimeout"], initial["drainDuration"],
                remote=initial["kind"] == "restoreDrainFromRemoteIntent",
                local_only=initial["kind"] == "restoreTargetLocalOnly",
            )
            assert_expected(vector["name"] + ":initial", Reduction("applied", ["freezeSourceIngress", "armStopTimeout"]), p.state, initial["expect"])
        for index, step in enumerate(vector["steps"]):
            assert_expected(f"{vector['name']}:{index}", replay(p, step), p.state, step["expect"])


def complete_local_switch(p: Policy, now: int):
    old = p.state.active
    target = Role.B if old == Role.A else Role.A
    assert old is not None
    p.request(target, now, 100, 20)
    e, sg, tg = p.state.epoch, p.state.source_generation, p.state.target_generation
    assert sg is not None and tg is not None
    snapshot = deepcopy(p.state)
    assert p.request(target, now + 1, 100, 20).outcome == "coalesced"
    assert p.request(old, now + 2, 100, 20).outcome == "rejectedConflict"
    assert p.ingress(e - 1, sg, old, now + 3).outcome == "staleCallback"
    assert p.state == snapshot
    assert p.ingress(e, sg, old, now + 4).effects == ["requestRemoteStop"]
    assert p.tx_result(e, sg, old, Frame.C, 1, "ACCEPTED", now + 5).effects == []
    assert p.remote(e, sg, old, Evidence.ACK, now + 6).effects == ["stopLocalSource"]
    p.terminal(e, sg, old, now + 7)
    r = p.owners(e, sg, old, 0, now + 8)
    assert r.effects == ["cancelStopTimeout", "armDrainDeadline"]
    deadline = p.state.drain_deadline
    assert deadline == now + 28
    assert p.drain_due(e, sg, old, deadline - 1).outcome == "notDue"
    p.drain_due(e, sg, old, deadline)
    p.begin(e, tg, target)
    p.active(e, tg, target)


def verify_20_each_direction_and_negatives():
    p = Policy(Role.A, 10, 20)
    counts = {(Role.A, Role.B): 0, (Role.B, Role.A): 0}
    for index in range(40):
        old = p.state.active
        assert old is not None
        complete_local_switch(p, 10_000 + index * 1_000)
        counts[(old, p.state.active)] += 1
    assert counts == {(Role.A, Role.B): 20, (Role.B, Role.A): 20}

    timeout = Policy()
    timeout.request(Role.B, 100, 50, 10)
    assert timeout.ingress(1, 1, Role.A, 150).effects == ["failClosed"]
    assert timeout.state.failure == "stopTimeout"

    tx = Policy()
    tx.request(Role.B, 0, 100, 10, remote=True)
    tx.ingress(1, 1, Role.A, 1)
    assert tx.tx_result(1, 1, Role.A, Frame.A, 1, "TERMINAL_FAILURE", 2).effects == ["failClosed"]
    assert tx.state.failure == "controlTransmitFailed"

    local_only = Policy()
    local_only.restore(Role.B, Role.B, 42, 8, 9, 0, 100, 10, local_only=True)
    assert local_only.ingress(42, 8, Role.B, 1).effects == ["stopLocalSource"]

    offline = Policy()
    offline.request(Role.B, 0, 100, 10)
    atomic = offline.ingress_without_owner(1, 1, Role.A, 1)
    assert atomic.effects == ["stopLocalSource"]
    assert offline.state.remote_evidence == Evidence.NO_OWNER
    assert offline.state.control_attempt == 0
    assert "requestRemoteStop" not in atomic.effects

    frozen_loss = Policy(Role.B)
    frozen_loss.request(Role.A, 0, 100, 10)
    frozen_loss.radio(1, 1, Role.B, 1)
    atomic = frozen_loss.ingress_without_owner(1, 1, Role.B, 2)
    assert atomic.effects == []
    assert frozen_loss.state.remote_evidence == Evidence.RADIO

    freeze_failure = Policy()
    freeze_failure.request(Role.B, 0, 100, 10)
    assert freeze_failure.ingress_freeze_failed(1, 1, Role.A).effects == ["failClosed"]
    assert freeze_failure.state.failure == "ingressFreezeFailed"


def verify_repeated_same_role_recovery():
    p = Policy(Role.B, 100, 200)
    for index in range(20):
        generation = p.state.active_generation
        assert generation is not None
        now = 100_000 + index * 1_000
        assert p.request_same_role_restart(now, 100, 10).effects == [
            "freezeSourceIngress", "armStopTimeout"
        ]
        epoch, target_generation = p.state.epoch, p.state.target_generation
        assert target_generation == generation + 1
        assert p.state.source == p.state.target == Role.B
        assert p.ingress(epoch, generation, Role.B, now + 1).effects == ["stopLocalSource"]
        p.terminal(epoch, generation, Role.B, now + 2)
        result = p.owners(epoch, generation, Role.B, 0, now + 3)
        assert result.effects[-2:] == ["cancelStopTimeout", "armDrainDeadline"]
        deadline = p.state.drain_deadline
        assert deadline is not None
        p.drain_due(epoch, generation, Role.B, deadline)
        p.begin(epoch, target_generation, Role.B)
        p.active(epoch, target_generation, Role.B)
    assert p.state.active == Role.B and p.state.active_generation == 220


def main():
    verify_swift_surface()
    verify_shared_vectors()
    verify_20_each_direction_and_negatives()
    verify_repeated_same_role_recovery()
    print("PASS: shared ble-role-switch/v2 vectors + 40 switches + 20 same-role recoveries + stale/timeout/conflict/retry/remote-C")


if __name__ == "__main__":
    main()
