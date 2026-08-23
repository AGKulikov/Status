# Natro 2.2.14 + Helper 67 Personal Team

- Android package: `ru.natro.statuswidget`
- Android version: `2.2.14`
- Android version code: `208021248`
- Helper bundle: `ru.natro.kx11ancshelper`
- Helper build: `67`
- Helper marketing version: `67.0`

## ANCS delivery repair

- Natro now joins both halves of route readiness regardless of callback order. The expected
  Route `READY` -> coordinator `ACTIVE` sequence opens the controller delivery gate immediately
  instead of leaving `ancsReady=false` until a route callback that never arrives.
- A freeze, retry, stop or new transport session closes and resets both readiness halves, so an
  old owner cannot authorize delivery for its replacement.
- The privacy-safe journal now records decoded ANCS item counts and presentation/filter stages
  without application identifiers, notification text, raw payloads or device identifiers.

## Preserved behavior

- Helper 67 is unchanged: direct Live Activity controls remain active on a physical iPhone and
  Personal Team targets contain no Push Notifications, `aps-environment` or App Groups.
- Existing encrypted C4 enrollment, CONTROL proof, serialized ANCS Control Point requests and C5
  command authentication remain unchanged.
- Android stays install-compatible with package `ru.natro.statuswidget`, API 28 and the existing
  update-signing certificate.
