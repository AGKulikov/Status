# Natro 2.1.3 / Helper 52 binary-recovery release

## Identity

- Android package: `ru.natro.statuswidget`
- Version: `2.1.3`
- Version code: `208021230`
- Update certificate SHA-256: `6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`
- Android release asset SHA-256: `a3b4906b8725814d83f6257400fb1b8925e938505cb8a5c5d5268c61450b0bb4`
- Helper: build `52`, marketing `52.0`
- Helper minimum iOS: `14.0` (required by the system CryptoKit HKDF API)
- Helper source asset SHA-256: `b4dfbc6266441b5583be8dadf06f9b1af26f935d2e3b298728ca0229529b671d`
- Frozen legacy H/C/A/T fixture SHA-256: `9244dab895dd82f0d4dd381e1aba3568a8c53a9cd0acbc2edae939faacefa7a2`
- Helper 52 H/C/A/R/T fixture SHA-256: `5baaf397fd02e6ff6a476346a5496b2765d126aae8d52d7cedc5c7b793bb26c2`

## Provenance boundary

The exact Android source commit for the supplied 2.1.2 APK is unavailable. Android 2.1.3 is an explicitly documented binary recovery from base SHA-256 `16703a5594dcbd1ae96862e3a03bda39fc663cffd07e04955e1a9fd9eac7a52a`. The reviewable patch and pinned rebuild/verification scripts are committed; the signed APK and signing material are not.

This release does **not** claim source-native Android reproducibility or successful physical KX11/iPhone acceptance.

## Android delta

- Adds Helper 52 encrypted CONTROL request `R` after 30 seconds without fresh telemetry.
- Keeps one GATT operation in flight, gives ANCS priority, and uses a bounded 5-second retry/watchdog so one lost response does not cross the 65-second stale boundary.
- Preserves firmware-specific printable SystemUI blacklist tokens instead of rejecting the existing KX11 syntax.
- Updates BLE enrollment prompts to Helper v52.

## Helper 52 delta

- Keeps the v51 enrollment identity, ECDH/SAS protocol, UUIDs, Keychain WAL, and Route A/B ownership model.
- Adds authenticated one-shot `R` refresh and event-driven telemetry without a one-second poll.
- Coalesces source callbacks and preserves C/A and ANCS control priority.

## Verified gates

- Apktool assembly and independent DEX rebuild comparison.
- JADX verification for both changed Android classes.
- Exact APK package/version/sdk/label/non-debuggable identity.
- 16 KiB/4-byte zip alignment and ZIP integrity.
- APK Signature Scheme v2/v3, one signer, exact update certificate.
- Helper 52 portable source/vector/route/runtime suite against the isolated
  `ancs-v2-wire-vectors-v52.json` fixture and archive checksums; the frozen v47-v50 H/C/A/T fixture
  remains byte-for-byte unchanged.
- Standalone `KX11_Bluetooth_Collect.sh` syntax and immutable SHA-256.

## Mandatory physical gates

1. Install 2.1.3 over 2.1.2 without uninstalling; confirm settings and existing Classic pair remain.
2. Build/install Helper 52 with Xcode, keep HFP active, perform the eight-digit SAS enrollment, and confirm both screens.
3. Verify ANCS notification delivery, Helper telemetry, lock/unlock, Bluetooth off/on, process restart, ACC cycle, and 20 reconnects.
4. Leave telemetry otherwise quiet for 30 seconds and confirm one serialized `R` produces one increasing `T` without interrupting ANCS.
5. Drop one `R` response and confirm the 5-second retry prevents a false 65-second stale transition.
6. Exercise SystemUI hide/restore on HOME, settings, unrelated apps, full-screen Navigator, and windowed Navigator; verify the status bar itself remains and all firmware-declared system-content slots are restored on disable.
7. On any failure, collect evidence with the standalone `.sh` before deleting pairings or changing system Bluetooth files.
