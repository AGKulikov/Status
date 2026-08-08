# KX11 ANCS Helper v30 for iPhone

Helper v30 is the companion for Status Widget HA1194. Select **Central** both in Helper and in
**Status Widget → Phone → iPhone BLE role**.

## Why v30 fixes the failed bootstrap

The 18:53 trace proves that v29 never reached phase two. The encrypted B3 read returned
`SECURE ATT OK`, but Android posted that fact to its main thread only after sending the ATT
response. Core Bluetooth immediately wrote `ANCS-HANDOFF`, and the Android Binder callback still
saw the old value and rejected the write with `CBATTErrorDomain:5`. A separate anonymous
`didConnect` path also called `createBond()` before `PAIR`; that churn coincided with repeated
`CBErrorDomain:8` characteristic discovery failures.

HA1194 commits the B3 proof synchronously before returning success and does not bond from an
unverified `didConnect`. Helper v30 also abandons an owner that returns `uuidNotAllowed` instead of
repeating the same prohibited request. Core Bluetooth can then perform the planned phase change,
Helper writes `ANCS-READY`, and Android starts its same-link ANCS client. Green now requires three
independent facts: both ANCS CCCDs, a valid B4 telemetry payload, and the acknowledged
`ANCS-SUBSCRIBED` write.

## Central-mode sequence

1. Helper scans only for the stable beacon `D2D9E4BF-47F1-4E44-A8BB-A932FD5AFFFF`.
2. It reads protocol/generation bytes from manufacturer or service data and derives the current
   Android service, CONTROL B2 and SECURE B3 UUIDs.
3. It opens a plain BLE bootstrap (`RequiresANCS=false`), discovers only that fresh namespace,
   writes `PAIR`, and completes the encrypted B3 current-link challenge.
4. It writes `ANCS-HANDOFF`, releases the bootstrap link, and reconnects to the same peripheral
   with `RequiresANCS=true`.
5. On that second link it rediscovers the current dynamic B3 and writes `ANCS-READY`; a bare
   `didConnect` is never treated as proof.
6. HA1194 maps the protected link to the verified bonded iPhone, subscribes to ANCS Notification
   Source and Data Source, then writes `ANCS-SUBSCRIBED` to F05/B4.
7. Helper displays green only when that acknowledgement and a real B4 read/subscription are both
   present. Neither ANCS nor telemetry can masquerade as the other.

The fixed F04 UUIDs are retained only for the legacy iPhone-peripheral route. Classic HFP/A2DP,
device names, pairing records, and ANCS authorization are never deleted or reset; LE security uses
the system bond only after the verified PAIR/B3 exchange requests it.

## Installation

The bundle identifier remains `ru.natro.kx11ancshelper`; minimum iOS is 13.0 and both Bluetooth
background modes remain enabled. Build/install with the same Apple Development Team as before.
Install HA1194 and Helper v30 as a matched pair, open Helper once, select **Central**, and leave
it running in the background. Do not delete the car's Classic Bluetooth pairing for this test.
