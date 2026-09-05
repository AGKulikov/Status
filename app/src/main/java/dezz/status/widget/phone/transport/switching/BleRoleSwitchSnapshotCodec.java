/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone.transport.switching;

import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Failure;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Role;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Sequence;
import java.util.Locale;
import java.util.Objects;

/** Strict, versioned persistence codec for ACTIVE and drain-only coordinator snapshots. */
public final class BleRoleSwitchSnapshotCodec {
    private static final String VERSION = "BRS2";
    private static final String ABSENT = "-";

    public enum Kind {
        ACTIVE,
        DRAIN
    }

    public static final class Snapshot {
        private final Kind kind;
        private final BleRoleSwitchOrigin origin;
        private final long processNonce;
        private final Phase phase;
        private final Sequence epoch;
        private final Role desiredRole;
        private final Role activeRole;
        private final Sequence activeGeneration;
        private final Role sourceRole;
        private final Sequence sourceGeneration;
        private final Role targetRole;
        private final Sequence targetGeneration;
        private final BleRoleSwitchCoordinator.WireSwitchToken wireToken;
        private final Failure failure;

        private Snapshot(
                Kind kind,
                BleRoleSwitchOrigin origin,
                long processNonce,
                Phase phase,
                Sequence epoch,
                Role desiredRole,
                Role activeRole,
                Sequence activeGeneration,
                Role sourceRole,
                Sequence sourceGeneration,
                Role targetRole,
                Sequence targetGeneration,
                BleRoleSwitchCoordinator.WireSwitchToken wireToken,
                Failure failure
        ) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.origin = origin;
            if (processNonce == 0L) {
                throw new IllegalArgumentException("processNonce must be non-zero");
            }
            this.processNonce = processNonce;
            this.phase = Objects.requireNonNull(phase, "phase");
            this.epoch = Objects.requireNonNull(epoch, "epoch");
            this.desiredRole = Objects.requireNonNull(desiredRole, "desiredRole");
            this.activeRole = activeRole;
            this.activeGeneration = activeGeneration;
            this.sourceRole = sourceRole;
            this.sourceGeneration = sourceGeneration;
            this.targetRole = targetRole;
            this.targetGeneration = targetGeneration;
            this.wireToken = wireToken;
            this.failure = Objects.requireNonNull(failure, "failure");
            validate();
        }

        public static Snapshot active(
                long processNonce,
                Sequence lastEpoch,
                Role role,
                Sequence generation
        ) {
            return new Snapshot(
                    Kind.ACTIVE,
                    null,
                    processNonce,
                    Phase.ACTIVE,
                    lastEpoch,
                    role,
                    role,
                    generation,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Failure.NONE
            );
        }

        public static Snapshot drain(
                long processNonce,
                BleRoleSwitchOrigin origin,
                Phase phase,
                Sequence epoch,
                Role desiredRole,
                Role sourceRole,
                Sequence sourceGeneration,
                Role targetRole,
                Sequence targetGeneration,
                BleRoleSwitchCoordinator.WireSwitchToken wireToken,
                Failure failure
        ) {
            return new Snapshot(
                    Kind.DRAIN,
                    Objects.requireNonNull(origin, "origin"),
                    processNonce,
                    phase,
                    epoch,
                    desiredRole,
                    null,
                    null,
                    sourceRole,
                    sourceGeneration,
                    targetRole,
                    targetGeneration,
                    wireToken,
                    failure
            );
        }

        public Kind kind() {
            return kind;
        }

        public BleRoleSwitchOrigin origin() {
            return origin;
        }

        public long processNonce() {
            return processNonce;
        }

        public Phase phase() {
            return phase;
        }

        public Sequence epoch() {
            return epoch;
        }

        public Role desiredRole() {
            return desiredRole;
        }

        public Role activeRole() {
            return activeRole;
        }

        public Sequence activeGeneration() {
            return activeGeneration;
        }

        public Role sourceRole() {
            return sourceRole;
        }

        public Sequence sourceGeneration() {
            return sourceGeneration;
        }

        public Role targetRole() {
            return targetRole;
        }

        public Sequence targetGeneration() {
            return targetGeneration;
        }

        public BleRoleSwitchCoordinator.WireSwitchToken wireToken() {
            return wireToken;
        }

        public Failure failure() {
            return failure;
        }

        private void validate() {
            if (kind == Kind.ACTIVE) {
                if (phase != Phase.ACTIVE
                        || activeRole == null
                        || activeGeneration == null
                        || activeGeneration.equals(Sequence.zero())
                        || desiredRole != activeRole
                        || origin != null
                        || sourceRole != null
                        || sourceGeneration != null
                        || targetRole != null
                        || targetGeneration != null
                        || wireToken != null
                        || failure != Failure.NONE) {
                    throw new IllegalArgumentException("invalid ACTIVE snapshot");
                }
                return;
            }

            if (phase == Phase.ACTIVE
                    || epoch.equals(Sequence.zero())
                    || sourceRole == null
                    || sourceGeneration == null
                    || sourceGeneration.equals(Sequence.zero())
                    || targetRole == null
                    || targetGeneration == null
                    || targetGeneration.equals(Sequence.zero())
                    || desiredRole != targetRole
                    || origin == null
                    || wireToken == null) {
                throw new IllegalArgumentException("invalid DRAIN snapshot");
            }
            if (phase == Phase.FAILED && failure == Failure.NONE) {
                throw new IllegalArgumentException("FAILED snapshot requires failure reason");
            }
            if (phase != Phase.FAILED && phase != Phase.CLOSED && failure != Failure.NONE) {
                throw new IllegalArgumentException("failure reason outside FAILED phase");
            }
        }
    }

    public String encode(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return String.join(
                "|",
                VERSION,
                snapshot.kind == Kind.ACTIVE ? "A" : "D",
                snapshot.origin == null ? ABSENT : snapshot.origin.name(),
                Long.toUnsignedString(snapshot.processNonce, 16),
                snapshot.phase.name(),
                snapshot.epoch.toString(),
                snapshot.desiredRole.name(),
                role(snapshot.activeRole),
                sequence(snapshot.activeGeneration),
                role(snapshot.sourceRole),
                sequence(snapshot.sourceGeneration),
                role(snapshot.targetRole),
                sequence(snapshot.targetGeneration),
                snapshot.wireToken == null ? ABSENT : snapshot.wireToken.hex(),
                snapshot.failure.name()
        );
    }

    public Snapshot decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] fields = encoded.split("\\|", -1);
        if (fields.length != 15 || !VERSION.equals(fields[0])) {
            throw new IllegalArgumentException("unknown or malformed role-switch snapshot");
        }
        Kind kind = switch (fields[1]) {
            case "A" -> Kind.ACTIVE;
            case "D" -> Kind.DRAIN;
            default -> throw new IllegalArgumentException("unknown snapshot kind");
        };
        BleRoleSwitchOrigin origin = nullableEnum(
                BleRoleSwitchOrigin.class,
                fields[2],
                "origin"
        );
        long processNonce;
        try {
            processNonce = Long.parseUnsignedLong(fields[3], 16);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid process nonce", invalid);
        }
        Phase phase = enumValue(Phase.class, fields[4], "phase");
        Sequence epoch = sequence(fields[5], "epoch");
        Role desired = enumValue(Role.class, fields[6], "desired role");
        Role active = nullableEnum(Role.class, fields[7], "active role");
        Sequence activeGeneration = nullableSequence(fields[8], "active generation");
        Role source = nullableEnum(Role.class, fields[9], "source role");
        Sequence sourceGeneration = nullableSequence(fields[10], "source generation");
        Role target = nullableEnum(Role.class, fields[11], "target role");
        Sequence targetGeneration = nullableSequence(fields[12], "target generation");
        BleRoleSwitchCoordinator.WireSwitchToken token = ABSENT.equals(fields[13])
                ? null
                : BleRoleSwitchCoordinator.WireSwitchToken.fromHex(fields[13]);
        Failure failure = enumValue(Failure.class, fields[14], "failure");
        return new Snapshot(
                kind,
                origin,
                processNonce,
                phase,
                epoch,
                desired,
                active,
                activeGeneration,
                source,
                sourceGeneration,
                target,
                targetGeneration,
                token,
                failure
        );
    }

    private static String role(Role role) {
        return role == null ? ABSENT : role.name();
    }

    private static String sequence(Sequence sequence) {
        return sequence == null ? ABSENT : sequence.toString();
    }

    private static Sequence sequence(String value, String label) {
        try {
            return Sequence.parse(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid " + label, invalid);
        }
    }

    private static Sequence nullableSequence(String value, String label) {
        return ABSENT.equals(value) ? null : sequence(value, label);
    }

    private static <E extends Enum<E>> E nullableEnum(
            Class<E> type,
            String value,
            String label
    ) {
        return ABSENT.equals(value) ? null : enumValue(type, value, label);
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type,
            String value,
            String label
    ) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid " + label, invalid);
        }
    }
}
