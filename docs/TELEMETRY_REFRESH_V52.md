# Helper v52 one-shot telemetry refresh contract

This extension keeps ANCS wire protocol version 2 and every v51 UUID/frame unchanged. It adds one
new CONTROL frame type. The paired Android implementation is Natro 2.1.3 or newer.

## R frame

`R` is exactly 20 bytes and is sent over the existing encrypted CONTROL characteristic:

| Offset | Bytes | Value |
| --- | ---: | --- |
| 0 | 1 | `0x52` (`R`) |
| 1 | 1 | protocol version `0x02` |
| 2 | 1 | current Android role: `1` central (Route A), `2` peripheral (Route B) |
| 3 | 1 | flags `0x00` |
| 4 | 16 | fresh, non-zero random request token |

The frozen vector `refresh-current-android-central` is in `ancs-v2-wire-vectors.json`. The token is
not an identity, secret, switch token or response correlator. A valid increasing `T.sequence` is the
response. Replaying R cannot change enrollment or role state.

## Android scheduling and GATT serialization

Android owns the policy. Helper has no periodic timer.

1. Start/restart the quiet interval only after the exact authenticated CONTROL owner is ready.
2. Reset the interval on ANCS discovery/control/data work, an ANCS notification, or valid telemetry.
3. After 30 uninterrupted seconds, enqueue one R at lower priority than all ANCS work.
4. Issue R with ATT write-with-response only when the single GATT dispatcher has no operation in
   flight. Never start R beside an ANCS read/write/CCCD/service-discovery operation.
5. After the write callback, wait for a valid T with a sequence newer modulo 16 bits. Do not poll.
   A missing T may schedule another R only after another complete 30-second quiet interval.
6. Cancel/fence the timer and outstanding expectation on owner, connection or generation change.

## Helper acceptance and response

Helper accepts R only from the exact C4-authenticated, encrypted CONTROL owner of the current ACTIVE
generation and only when the role byte describes that active route. Route A rejects R during freeze
or stop. Route B ignores a late R outside ACTIVE. Multiple R callbacks received before the public
iOS status source returns are coalesced into one refresh.

The source re-reads battery/power/lock state and reclassifies `NWPathMonitor.currentPath` using current
CoreTelephony service state. The runtime assigns the next generation-local T sequence. Route A
drains pending C/A indications before the latest coalesced T. Diagnostic Route B retains a single
ATT-with-response write in flight and selects queued C/A before T.

## Compatibility

- Helper v52 preserves the v51 bundle ID, stable restoration identifiers, H/C/A/T bytes, C4
  enrollment protocol and device-only active/pending Keychain WAL.
- Natro 2.1.2 can still connect, authenticate and receive initial/event-driven telemetry from
  Helper v52, but it does not send R and therefore does not gain forced stale-data recovery.
- Natro 2.1.3 with Helper v51 may have its R write rejected as an unknown frame. Treat that as
  “refresh unsupported”; do not disconnect, re-enroll, toggle Bluetooth or retry in a tight loop.
