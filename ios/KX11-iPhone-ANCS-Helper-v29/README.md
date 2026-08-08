# KX11 ANCS Helper v29 for iPhone

Helper v29 is the companion for Status Widget HA1191. Select **Central** both in Helper and in
**Status Widget → Phone → iPhone BLE role**.

## Why v29 changes the phase-two proof

The latest paired logs show that phase two did connect with `RequiresANCS=true`, but Android's old
350-ms delayed bootstrap cleanup fired after that connection and cancelled the correct ANCS
owner. They also show that Helper reported green after `didConnect`/B4 even when Android had not
enabled the ANCS Notification Source and Data Source subscriptions.

HA1191 never force-closes the bootstrap owner. Core Bluetooth performs the planned phase change,
then Helper rediscovers the current dynamic B3 on the `RequiresANCS` connection and writes
`ANCS-READY`. Android starts its same-link GATT client only after that explicit proof. Helper turns
green only after Android has successfully enabled both ANCS CCCDs and writes `ANCS-SUBSCRIBED`
back to the iPhone-owned F05/B4 relay.

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
6. HA1191 maps the protected link to the verified bonded iPhone, subscribes to ANCS Notification
   Source and Data Source, then writes `ANCS-SUBSCRIBED` to F05/B4.
7. Only that final acknowledgement makes Helper display green. B4 read/notify alone remains
   yellow so telemetry cannot masquerade as working ANCS.

The fixed F04 UUIDs are retained only for the legacy iPhone-peripheral route. Classic HFP/A2DP,
device names, pairing records, the system LE bond, and ANCS authorization are never changed.

## Installation

The bundle identifier remains `ru.natro.kx11ancshelper`; minimum iOS is 13.0 and both Bluetooth
background modes remain enabled. Build/install with the same Apple Development Team as before.
Install HA1191 and Helper v29 as a matched pair, open Helper once, select **Central**, and leave
it running in the background. Do not delete the car's Classic Bluetooth pairing for this test.
