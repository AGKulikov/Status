# Natro 2.1.3 binary recovery

This directory records the exact, reviewable delta used to produce Natro 2.1.3 from the user-supplied Natro 2.1.2 APK.

The Android 2.1.2 source commit embedded in that APK (`5da9c7d8594cf89d0ff6e4706be33e2c7e3a2bbc`) is not present in this repository or on the remote. This is therefore intentionally labelled a **binary recovery**, not a source-native Android release. The signed APK is a release asset and is not committed to Git. Signing material is never included.

The patch is limited to:

- version `2.1.3` / code `208021230`;
- Helper 52 one-shot telemetry request `R`, serialized behind ANCS with a 30-second quiet interval and bounded 5-second retry/watchdog;
- preservation of firmware-specific printable `SystemUI icon_blacklist` tokens;
- Helper v52 wording in the BLE enrollment UI.

`build-binary-recovery.sh` requires the exact supplied 2.1.2 APK and the pinned Apktool 3.0.3 JAR. It emits an unsigned, unaligned APK. Release signing is deliberately external.

`verify-release-apk.sh` verifies the exact published signed APK with pinned Android Build Tools binaries.

Helper 52 source lives at `ios/KX11-iPhone-ANCS-Helper-v52`. Its portable suite is `verify-v52-contract.sh`. A signed IPA still requires Xcode, an Apple signing identity, and physical iPhone/KX11 validation.
