# Natro 2.2.13 + Helper 66 Personal Team

- Android package: `ru.natro.statuswidget`
- Android version: `2.2.13`
- Android version code: `208021247`
- Helper bundle: `ru.natro.kx11ancshelper`
- Helper build: `66`
- Helper marketing version: `66.0`

## Field-log fixes

- Helper Route A no longer stops at `ingressFreezeFailed` when a new process restores an
  incompatible local CoreBluetooth namespace. It stops advertising, removes all restored
  services, observes the existing bounded grace and proceeds to a fresh Route A automatically.
- Explicit role switching and Route B keep the prior fail-closed unprovable-owner behavior.
- The Android foreground integration host uses a process-independent dead-man alarm. A live
  service continually moves that alarm forward; if the process disappears, the retained alarm
  requests a foreground restart within nine seconds. Bluetooth connect/disconnect/profile
  broadcasts also schedule an immediate recovery check.
- Android ANCS log export writes only a temporary FileProvider cache file and immediately opens
  the system share chooser. It no longer requests storage permission or writes to `/Download`.
- Helper journals read build and marketing versions from the installed bundle instead of a
  hard-coded version label.

## Preserved behavior

- Personal Team targets contain no Push Notifications, `aps-environment` or App Groups.
- Lock Screen controls remain direct `Button` + `AppIntent` controls for an already-running local
  Live Activity; the URL fallback remains only where the platform requires opening Helper.
- Existing C4 enrollment, CONTROL proof, ANCS serialization and C5 command authentication remain
  unchanged.
